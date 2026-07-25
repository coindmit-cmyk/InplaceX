package com.mirkori.inplacex.backend.persistence.session

import com.mirkori.inplacex.backend.persistence.IdempotencyKeyReusedException
import com.mirkori.inplacex.backend.persistence.transaction
import com.mirkori.inplacex.logging.InplaceXLogger
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import javax.sql.DataSource

private val SESSION_ID = Regex("[A-Za-z0-9._~-]{1,64}")
private val COMMAND_ID = Regex("[A-Za-z0-9._~-]{1,128}")

class SessionRevisionConflictException(
    val sessionId: String,
    val expectedRevision: Long,
    val currentRevision: Long,
) : IllegalStateException(
    "Duel session revision conflict at expected revision $expectedRevision; current revision is $currentRevision",
)

class SessionPublicStateUnavailableException(sessionId: String) : IllegalStateException(
    "Duel session $sessionId has no durable public state and must be rehydrated before mutation",
)

data class DurableDuelSessionCommand(
    val sessionId: String,
    val actorId: String,
    val clientCommandId: String,
    val expectedRevision: Long,
    val content: DurableDuelCommandContent,
    val resultingSnapshot: PublicDuelSessionSnapshot,
    val event: PublicDuelSessionEvent,
    val result: PublicDuelCommandResult,
) {
    init {
        require(SESSION_ID.matches(sessionId)) { "sessionId must be an opaque id" }
        require(SESSION_ID.matches(actorId)) { "actorId must be an opaque id" }
        require(COMMAND_ID.matches(clientCommandId)) {
            "clientCommandId must contain 1..128 idempotency-safe characters"
        }
        require(expectedRevision >= 0) { "Expected revision must not be negative" }
    }
}

data class DurableDuelSessionCommandReceipt(
    val sessionId: String,
    val actorId: String,
    val clientCommandId: String,
    val revision: Long,
    val eventSequence: Long,
    val result: PublicDuelCommandResult,
    val snapshot: PublicDuelSessionSnapshot,
)

data class DurableDuelSessionCommitResult(
    val receipt: DurableDuelSessionCommandReceipt,
    val replayed: Boolean,
)

data class DurableDuelSessionEvent(
    val sequence: Long,
    val event: PublicDuelSessionEvent,
    val createdAt: Instant,
)

sealed interface DurableDuelSessionReconnect {
    val upperBoundEventSequence: Long
    val firstRetainedEventSequence: Long

    data class ContiguousReplay(
        val requestedAfterEventSequence: Long,
        override val upperBoundEventSequence: Long,
        override val firstRetainedEventSequence: Long,
        val events: List<DurableDuelSessionEvent>,
    ) : DurableDuelSessionReconnect

    data class SnapshotAndEvents(
        val snapshot: PublicDuelSessionSnapshot,
        override val upperBoundEventSequence: Long,
        override val firstRetainedEventSequence: Long,
        val events: List<DurableDuelSessionEvent>,
    ) : DurableDuelSessionReconnect

    data class ReplayGap(
        val snapshot: PublicDuelSessionSnapshot?,
        override val upperBoundEventSequence: Long,
        override val firstRetainedEventSequence: Long,
        val reason: ReplayGapReason,
    ) : DurableDuelSessionReconnect
}

enum class ReplayGapReason {
    LEGACY_STATE_NOT_DURABLE,
    CURSOR_BEFORE_RETENTION,
    CURSOR_AHEAD,
    INVALID_CURSOR,
}

internal interface DurableSessionTestHooks {
    fun beforeEventInsert() = Unit

    fun afterReconnectUpperBoundCaptured() = Unit
}

private object NoDurableSessionTestHooks : DurableSessionTestHooks

/**
 * Durable persistence boundary for viewer-neutral public duel state.
 *
 * Sensitive command values are reduced to a server-derived fingerprint in
 * memory. Only closed, validated public snapshots, events and results cross the
 * database boundary.
 */
class JdbcDurableDuelSessionRepository internal constructor(
    private val dataSource: DataSource,
    private val eventRetention: Int,
    private val maximumReconnectEvents: Int,
    private val logger: InplaceXLogger,
    private val testHooks: DurableSessionTestHooks,
) {
    constructor(
        dataSource: DataSource,
        eventRetention: Int = 128,
        maximumReconnectEvents: Int = 128,
        logger: InplaceXLogger = InplaceXLogger(),
    ) : this(
        dataSource = dataSource,
        eventRetention = eventRetention,
        maximumReconnectEvents = maximumReconnectEvents,
        logger = logger,
        testHooks = NoDurableSessionTestHooks,
    )

    init {
        require(eventRetention in 1..10_000) { "Event retention must be in 1..10000" }
        require(maximumReconnectEvents in 1..256) { "Reconnect event bound must be in 1..256" }
    }

    fun createSession(
        sessionId: String,
        mode: String,
        initialSnapshot: PublicDuelSessionSnapshot,
    ) {
        require(SESSION_ID.matches(sessionId)) { "sessionId must be an opaque id" }
        require(SESSION_ID.matches(mode)) { "mode must be a bounded public identifier" }
        require(initialSnapshot.sessionId == sessionId) { "Initial snapshot belongs to a different session" }
        require(initialSnapshot.revision == 0L) { "Initial snapshot revision must be zero" }
        require(initialSnapshot.eventSequence == 0L) { "Initial snapshot event sequence must be zero" }
        val snapshotJson = PublicSessionJson.encodeSnapshot(initialSnapshot)
        val configJson = PublicSessionJson.encodeConfig(initialSnapshot.config)

        dataSource.transaction { connection ->
            connection.prepareStatement(
                """
                INSERT INTO duel_sessions(id, mode, status, config_json, version)
                VALUES (?, ?, ?, ?, 0)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setString(2, mode)
                statement.setString(3, initialSnapshot.phase.name)
                statement.setString(4, configJson)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO duel_session_states(
                    session_id,
                    revision,
                    event_cursor,
                    first_retained_event_seq,
                    public_state_available,
                    snapshot_event_seq,
                    snapshot_json
                ) VALUES (?, 0, 0, 1, TRUE, 0, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setString(2, snapshotJson)
                statement.executeUpdate()
            }
            insertSnapshot(connection, initialSnapshot, snapshotJson)
        }
        logger.info(
            tag = LOG_TAG,
            message = "Durable duel session created",
            attributes = mapOf("sessionId" to sessionId, "revision" to "0", "eventSeq" to "0"),
        )
    }

    fun readCurrentSnapshot(sessionId: String): PublicDuelSessionSnapshot = dataSource.connection.use { connection ->
        val state = readState(connection, sessionId)
        state.snapshotOrNull()
            ?: throw SessionPublicStateUnavailableException(sessionId)
    }

    fun apply(command: DurableDuelSessionCommand): DurableDuelSessionCommitResult {
        val fingerprint = PublicSessionJson.requestFingerprint(command.expectedRevision, command.content)
        val commit = try {
            dataSource.transaction { connection ->
                existingReceipt(connection, command, fingerprint)?.let { return@transaction it }

                val state = readState(connection, command.sessionId)
                if (!state.publicStateAvailable) {
                    throw SessionPublicStateUnavailableException(command.sessionId)
                }
                if (state.revision != command.expectedRevision) {
                    existingReceipt(connection, command, fingerprint)?.let { return@transaction it }
                    throw SessionRevisionConflictException(
                        sessionId = command.sessionId,
                        expectedRevision = command.expectedRevision,
                        currentRevision = state.revision,
                    )
                }

                val nextRevision = state.revision + 1
                val nextEventSequence = state.eventCursor + 1
                validateTransition(command, nextRevision, nextEventSequence)
                val snapshotJson = PublicSessionJson.encodeSnapshot(command.resultingSnapshot)
                val eventJson = PublicSessionJson.encodeEventPayload(command.event)
                val resultJson = PublicSessionJson.encodeResultPayload(command.result)

                val sessionChanged = connection.prepareStatement(
                    """
                    UPDATE duel_sessions
                    SET version = ?, status = ?
                    WHERE id = ? AND version = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, nextRevision)
                    statement.setString(2, command.resultingSnapshot.phase.name)
                    statement.setString(3, command.sessionId)
                    statement.setLong(4, state.revision)
                    statement.executeUpdate()
                }
                if (sessionChanged != 1) {
                    existingReceipt(connection, command, fingerprint)?.let { return@transaction it }
                    throw currentRevisionConflict(connection, command)
                }

                val stateChanged = connection.prepareStatement(
                    """
                    UPDATE duel_session_states
                    SET revision = ?,
                        event_cursor = ?,
                        snapshot_event_seq = ?,
                        snapshot_json = ?,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE session_id = ? AND revision = ? AND event_cursor = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, nextRevision)
                    statement.setLong(2, nextEventSequence)
                    statement.setLong(3, nextEventSequence)
                    statement.setString(4, snapshotJson)
                    statement.setString(5, command.sessionId)
                    statement.setLong(6, state.revision)
                    statement.setLong(7, state.eventCursor)
                    statement.executeUpdate()
                }
                check(stateChanged == 1) { "Durable duel state changed during atomic commit" }

                testHooks.beforeEventInsert()
                insertEvent(connection, command.sessionId, nextEventSequence, command.event, eventJson)
                insertSnapshot(connection, command.resultingSnapshot, snapshotJson)
                insertReceipt(
                    connection = connection,
                    command = command,
                    fingerprint = fingerprint,
                    revision = nextRevision,
                    eventSequence = nextEventSequence,
                    resultJson = resultJson,
                    snapshotJson = snapshotJson,
                )
                retainBoundedHistory(connection, command.sessionId, nextEventSequence)

                DurableDuelSessionCommitResult(
                    receipt = DurableDuelSessionCommandReceipt(
                        sessionId = command.sessionId,
                        actorId = command.actorId,
                        clientCommandId = command.clientCommandId,
                        revision = nextRevision,
                        eventSequence = nextEventSequence,
                        result = command.result,
                        snapshot = command.resultingSnapshot,
                    ),
                    replayed = false,
                )
            }
        } catch (error: IdempotencyKeyReusedException) {
            logger.warn(
                tag = LOG_TAG,
                message = "Duel command idempotency key reuse rejected",
                attributes = safeCommandAttributes(command, outcome = "idempotency_key_reused"),
            )
            throw error
        } catch (error: SessionRevisionConflictException) {
            logger.warn(
                tag = LOG_TAG,
                message = "Duel command revision conflict",
                attributes = safeCommandAttributes(command, outcome = "revision_conflict") +
                    ("currentRevision" to error.currentRevision.toString()),
            )
            throw error
        }

        logger.info(
            tag = LOG_TAG,
            message = if (commit.replayed) "Duel command receipt replayed" else "Duel command committed",
            attributes = safeCommandAttributes(
                command,
                outcome = if (commit.replayed) "duplicate" else "committed",
            ) + mapOf(
                "revision" to commit.receipt.revision.toString(),
                "eventSeq" to commit.receipt.eventSequence.toString(),
            ),
        )
        return commit
    }

    fun reconnect(
        sessionId: String,
        lastSeenEventSequence: Long,
        maximumEvents: Int = maximumReconnectEvents,
    ): DurableDuelSessionReconnect {
        require(SESSION_ID.matches(sessionId)) { "sessionId must be an opaque id" }
        require(maximumEvents in 1..maximumReconnectEvents) {
            "Reconnect maximum must be in 1..$maximumReconnectEvents"
        }

        val result = dataSource.consistentRead { connection ->
            val state = readState(connection, sessionId)
            val upperBound = state.eventCursor
            testHooks.afterReconnectUpperBoundCaptured()

            val snapshot = state.snapshotOrNull()
            when {
                snapshot == null -> DurableDuelSessionReconnect.ReplayGap(
                    snapshot = null,
                    upperBoundEventSequence = upperBound,
                    firstRetainedEventSequence = state.firstRetainedEventSequence,
                    reason = ReplayGapReason.LEGACY_STATE_NOT_DURABLE,
                )

                lastSeenEventSequence < 0 -> DurableDuelSessionReconnect.ReplayGap(
                    snapshot = snapshot,
                    upperBoundEventSequence = upperBound,
                    firstRetainedEventSequence = state.firstRetainedEventSequence,
                    reason = ReplayGapReason.INVALID_CURSOR,
                )

                lastSeenEventSequence > upperBound -> DurableDuelSessionReconnect.ReplayGap(
                    snapshot = snapshot,
                    upperBoundEventSequence = upperBound,
                    firstRetainedEventSequence = state.firstRetainedEventSequence,
                    reason = ReplayGapReason.CURSOR_AHEAD,
                )

                lastSeenEventSequence < state.firstRetainedEventSequence - 1 ->
                    DurableDuelSessionReconnect.ReplayGap(
                        snapshot = snapshot,
                        upperBoundEventSequence = upperBound,
                        firstRetainedEventSequence = state.firstRetainedEventSequence,
                        reason = ReplayGapReason.CURSOR_BEFORE_RETENTION,
                    )

                else -> reconnectFromRetainedHistory(
                    connection = connection,
                    sessionId = sessionId,
                    lastSeenEventSequence = lastSeenEventSequence,
                    state = state,
                    maximumEvents = maximumEvents,
                )
            }
        }

        logger.info(
            tag = LOG_TAG,
            message = "Durable duel reconnect prepared",
            attributes = mapOf(
                "sessionId" to sessionId,
                "mode" to result.modeName(),
                "upperEventSeq" to result.upperBoundEventSequence.toString(),
                "firstRetainedEventSeq" to result.firstRetainedEventSequence.toString(),
            ),
        )
        return result
    }

    private fun reconnectFromRetainedHistory(
        connection: Connection,
        sessionId: String,
        lastSeenEventSequence: Long,
        state: StoredState,
        maximumEvents: Int,
    ): DurableDuelSessionReconnect {
        val events = readEvents(
            connection = connection,
            sessionId = sessionId,
            afterExclusive = lastSeenEventSequence,
            throughInclusive = state.eventCursor,
            limit = maximumEvents + 1,
        )
        if (events.size <= maximumEvents) {
            requireContiguous(events, lastSeenEventSequence, state.eventCursor)
            return DurableDuelSessionReconnect.ContiguousReplay(
                requestedAfterEventSequence = lastSeenEventSequence,
                upperBoundEventSequence = state.eventCursor,
                firstRetainedEventSequence = state.firstRetainedEventSequence,
                events = events,
            )
        }

        val checkpointSequence = maxOf(
            lastSeenEventSequence,
            state.eventCursor - maximumEvents,
            state.firstRetainedEventSequence - 1,
        )
        val checkpoint = readSnapshot(connection, sessionId, checkpointSequence)
            ?: error("Missing retained public snapshot checkpoint")
        val laterEvents = readEvents(
            connection = connection,
            sessionId = sessionId,
            afterExclusive = checkpoint.eventSequence,
            throughInclusive = state.eventCursor,
            limit = maximumEvents,
        )
        requireContiguous(laterEvents, checkpoint.eventSequence, state.eventCursor)
        return DurableDuelSessionReconnect.SnapshotAndEvents(
            snapshot = checkpoint,
            upperBoundEventSequence = state.eventCursor,
            firstRetainedEventSequence = state.firstRetainedEventSequence,
            events = laterEvents,
        )
    }

    private fun existingReceipt(
        connection: Connection,
        command: DurableDuelSessionCommand,
        fingerprint: String,
    ): DurableDuelSessionCommitResult? = readReceipt(connection, command)?.let { stored ->
        if (stored.fingerprint != fingerprint) throw IdempotencyKeyReusedException()
        DurableDuelSessionCommitResult(
            receipt = DurableDuelSessionCommandReceipt(
                sessionId = command.sessionId,
                actorId = command.actorId,
                clientCommandId = command.clientCommandId,
                revision = stored.revision,
                eventSequence = stored.eventSequence,
                result = PublicSessionJson.decodeResult(stored.resultType, stored.resultJson),
                snapshot = PublicSessionJson.decodeSnapshot(stored.snapshotJson),
            ),
            replayed = true,
        )
    }

    private fun readReceipt(connection: Connection, command: DurableDuelSessionCommand): StoredReceipt? =
        connection.prepareStatement(
            """
            SELECT request_fingerprint, revision, event_seq, result_type, result_json, snapshot_json
            FROM duel_command_receipts
            WHERE session_id = ? AND actor_id = ? AND client_command_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, command.sessionId)
            statement.setString(2, command.actorId)
            statement.setString(3, command.clientCommandId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) {
                    StoredReceipt(
                        fingerprint = resultSet.getString("request_fingerprint"),
                        revision = resultSet.getLong("revision"),
                        eventSequence = resultSet.getLong("event_seq"),
                        resultType = resultSet.getString("result_type"),
                        resultJson = resultSet.getString("result_json"),
                        snapshotJson = resultSet.getString("snapshot_json"),
                    )
                } else {
                    null
                }
            }
        }

    private fun insertReceipt(
        connection: Connection,
        command: DurableDuelSessionCommand,
        fingerprint: String,
        revision: Long,
        eventSequence: Long,
        resultJson: String,
        snapshotJson: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO duel_command_receipts(
                session_id,
                actor_id,
                client_command_id,
                request_fingerprint,
                revision,
                event_seq,
                result_type,
                result_json,
                snapshot_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, command.sessionId)
            statement.setString(2, command.actorId)
            statement.setString(3, command.clientCommandId)
            statement.setString(4, fingerprint)
            statement.setLong(5, revision)
            statement.setLong(6, eventSequence)
            statement.setString(7, command.result.type)
            statement.setString(8, resultJson)
            statement.setString(9, snapshotJson)
            statement.executeUpdate()
        }
    }

    private fun insertEvent(
        connection: Connection,
        sessionId: String,
        eventSequence: Long,
        event: PublicDuelSessionEvent,
        eventJson: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO duel_session_events(session_id, event_seq, event_type, payload_json)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setLong(2, eventSequence)
            statement.setString(3, event.type)
            statement.setString(4, eventJson)
            statement.executeUpdate()
        }
    }

    private fun insertSnapshot(
        connection: Connection,
        snapshot: PublicDuelSessionSnapshot,
        snapshotJson: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO duel_session_snapshots(session_id, event_seq, revision, snapshot_json)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, snapshot.sessionId)
            statement.setLong(2, snapshot.eventSequence)
            statement.setLong(3, snapshot.revision)
            statement.setString(4, snapshotJson)
            statement.executeUpdate()
        }
    }

    private fun retainBoundedHistory(connection: Connection, sessionId: String, latestEventSequence: Long) {
        val firstRetained = maxOf(1L, latestEventSequence - eventRetention + 1)
        connection.prepareStatement(
            "DELETE FROM duel_session_events WHERE session_id = ? AND event_seq < ?",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setLong(2, firstRetained)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "DELETE FROM duel_session_snapshots WHERE session_id = ? AND event_seq < ?",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setLong(2, firstRetained - 1)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "UPDATE duel_session_states SET first_retained_event_seq = ? WHERE session_id = ?",
        ).use { statement ->
            statement.setLong(1, firstRetained)
            statement.setString(2, sessionId)
            check(statement.executeUpdate() == 1) { "Missing durable duel session state during retention" }
        }
    }

    private fun readState(connection: Connection, sessionId: String): StoredState =
        connection.prepareStatement(
            """
            SELECT
                revision,
                event_cursor,
                first_retained_event_seq,
                public_state_available,
                snapshot_event_seq,
                snapshot_json,
                updated_at
            FROM duel_session_states
            WHERE session_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Unknown duel session" }
                StoredState(
                    sessionId = sessionId,
                    revision = resultSet.getLong("revision"),
                    eventCursor = resultSet.getLong("event_cursor"),
                    firstRetainedEventSequence = resultSet.getLong("first_retained_event_seq"),
                    publicStateAvailable = resultSet.getBoolean("public_state_available"),
                    snapshotEventSequence = resultSet.nullableLong("snapshot_event_seq"),
                    snapshotJson = resultSet.getString("snapshot_json"),
                    updatedAt = resultSet.getObject("updated_at", java.time.OffsetDateTime::class.java).toInstant(),
                )
            }
        }

    private fun readSnapshot(
        connection: Connection,
        sessionId: String,
        eventSequence: Long,
    ): PublicDuelSessionSnapshot? = connection.prepareStatement(
        """
        SELECT snapshot_json
        FROM duel_session_snapshots
        WHERE session_id = ? AND event_seq = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, sessionId)
        statement.setLong(2, eventSequence)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) PublicSessionJson.decodeSnapshot(resultSet.getString("snapshot_json")) else null
        }
    }

    private fun readEvents(
        connection: Connection,
        sessionId: String,
        afterExclusive: Long,
        throughInclusive: Long,
        limit: Int,
    ): List<DurableDuelSessionEvent> = connection.prepareStatement(
        """
        SELECT event_seq, event_type, payload_json, created_at
        FROM duel_session_events
        WHERE session_id = ? AND event_seq > ? AND event_seq <= ?
        ORDER BY event_seq ASC
        LIMIT ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, sessionId)
        statement.setLong(2, afterExclusive)
        statement.setLong(3, throughInclusive)
        statement.setInt(4, limit)
        statement.executeQuery().use { resultSet ->
            buildList {
                while (resultSet.next()) {
                    add(
                        DurableDuelSessionEvent(
                            sequence = resultSet.getLong("event_seq"),
                            event = PublicSessionJson.decodeEvent(
                                type = resultSet.getString("event_type"),
                                payloadJson = resultSet.getString("payload_json"),
                            ),
                            createdAt = resultSet.getObject(
                                "created_at",
                                java.time.OffsetDateTime::class.java,
                            ).toInstant(),
                        ),
                    )
                }
            }
        }
    }

    private fun currentRevisionConflict(
        connection: Connection,
        command: DurableDuelSessionCommand,
    ): SessionRevisionConflictException {
        val currentRevision = connection.prepareStatement(
            "SELECT version FROM duel_sessions WHERE id = ?",
        ).use { statement ->
            statement.setString(1, command.sessionId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Unknown duel session" }
                resultSet.getLong("version")
            }
        }
        return SessionRevisionConflictException(
            sessionId = command.sessionId,
            expectedRevision = command.expectedRevision,
            currentRevision = currentRevision,
        )
    }

    private fun validateTransition(
        command: DurableDuelSessionCommand,
        nextRevision: Long,
        nextEventSequence: Long,
    ) {
        val snapshot = command.resultingSnapshot
        require(snapshot.sessionId == command.sessionId) { "Resulting snapshot belongs to a different session" }
        require(snapshot.revision == nextRevision) { "Resulting snapshot has an invalid revision" }
        require(snapshot.eventSequence == nextEventSequence) { "Resulting snapshot has an invalid event sequence" }
        val participants = snapshot.participants.mapTo(mutableSetOf()) { it.participantId }

        when (val content = command.content) {
            DurableDuelCommandContent.RecordSecretStatus,
            is DurableDuelCommandContent.SubmitSecret,
            -> {
                val event = command.event as? PublicDuelSessionEvent.SecretStatusChanged
                    ?: throw IllegalArgumentException("Secret command requires a secret-status event")
                val result = command.result as? PublicDuelCommandResult.SecretAccepted
                    ?: throw IllegalArgumentException("Secret command requires a secret receipt")
                require(event.participantId == command.actorId && result.participantId == command.actorId) {
                    "Secret command actor does not match its public result"
                }
                require(event.secretSubmitted && result.secretSubmitted) {
                    "Accepted secret command must expose submitted status only"
                }
            }

            is DurableDuelCommandContent.SubmitGuess -> {
                val event = command.event as? PublicDuelSessionEvent.TurnResult
                    ?: throw IllegalArgumentException("Guess command requires a turn-result event")
                val result = command.result as? PublicDuelCommandResult.TurnAccepted
                    ?: throw IllegalArgumentException("Guess command requires a turn receipt")
                require(event.actorParticipantId == command.actorId) { "Turn actor does not match command actor" }
                require(event.turnNumber == result.turnNumber) { "Turn result number mismatch" }
                require(event.exactMatches == result.exactMatches && event.solved == result.solved) {
                    "Turn result metadata mismatch"
                }
                require(event.exactMatches <= snapshot.config.codeLength) {
                    "Turn exact matches exceed code length"
                }
            }

            is DurableDuelCommandContent.SetPresence -> {
                val event = command.event as? PublicDuelSessionEvent.ParticipantPresenceChanged
                    ?: throw IllegalArgumentException("Presence command requires a presence event")
                val result = command.result as? PublicDuelCommandResult.PresenceAccepted
                    ?: throw IllegalArgumentException("Presence command requires a presence receipt")
                require(event.participantId == command.actorId && result.participantId == command.actorId) {
                    "Presence command actor does not match its public result"
                }
                require(event.connected == content.connected && result.connected == content.connected) {
                    "Presence command result does not match validated content"
                }
            }

            is DurableDuelCommandContent.AdvancePhase -> {
                val event = command.event as? PublicDuelSessionEvent.PhaseChanged
                    ?: throw IllegalArgumentException("Phase command requires a phase event")
                val result = command.result as? PublicDuelCommandResult.PhaseAccepted
                    ?: throw IllegalArgumentException("Phase command requires a phase receipt")
                require(event.phase == content.phase && result.phase == content.phase && snapshot.phase == content.phase) {
                    "Phase command result does not match validated content"
                }
            }
        }

        val eventParticipantId = when (val event = command.event) {
            is PublicDuelSessionEvent.ParticipantPresenceChanged -> event.participantId
            is PublicDuelSessionEvent.SecretStatusChanged -> event.participantId
            is PublicDuelSessionEvent.TurnResult -> event.actorParticipantId
            is PublicDuelSessionEvent.PhaseChanged -> event.currentActorParticipantId
            is PublicDuelSessionEvent.Finished -> event.winnerParticipantId
        }
        require(eventParticipantId == null || eventParticipantId in participants) {
            "Public event participant must belong to the resulting snapshot"
        }
    }

    private data class StoredState(
        val sessionId: String,
        val revision: Long,
        val eventCursor: Long,
        val firstRetainedEventSequence: Long,
        val publicStateAvailable: Boolean,
        val snapshotEventSequence: Long?,
        val snapshotJson: String?,
        val updatedAt: Instant,
    ) {
        fun snapshotOrNull(): PublicDuelSessionSnapshot? {
            if (!publicStateAvailable) return null
            val decoded = PublicSessionJson.decodeSnapshot(requireNotNull(snapshotJson))
            check(decoded.sessionId == sessionId)
            check(decoded.revision == revision)
            check(decoded.eventSequence == snapshotEventSequence)
            check(decoded.eventSequence == eventCursor)
            return decoded
        }
    }

    private data class StoredReceipt(
        val fingerprint: String,
        val revision: Long,
        val eventSequence: Long,
        val resultType: String,
        val resultJson: String,
        val snapshotJson: String,
    )

    companion object {
        private const val LOG_TAG = "DurableDuelSession"
    }
}

private inline fun <T> DataSource.consistentRead(block: (Connection) -> T): T = connection.use { connection ->
    val previousAutoCommit = connection.autoCommit
    val previousIsolation = connection.transactionIsolation
    connection.autoCommit = false
    connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
    try {
        block(connection).also { connection.commit() }
    } catch (error: Exception) {
        connection.rollback()
        throw error
    } finally {
        connection.transactionIsolation = previousIsolation
        connection.autoCommit = previousAutoCommit
    }
}

private fun ResultSet.nullableLong(column: String): Long? =
    getLong(column).let { value -> if (wasNull()) null else value }

private fun requireContiguous(
    events: List<DurableDuelSessionEvent>,
    afterExclusive: Long,
    throughInclusive: Long,
) {
    val expected = if (throughInclusive == afterExclusive) {
        emptyList()
    } else {
        (afterExclusive + 1..throughInclusive).toList()
    }
    check(events.map { it.sequence } == expected) { "Retained duel event history is not contiguous" }
}

private fun safeCommandAttributes(
    command: DurableDuelSessionCommand,
    outcome: String,
): Map<String, String> = mapOf(
    "sessionId" to command.sessionId,
    "actorId" to command.actorId,
    "commandId" to command.clientCommandId,
    "expectedRevision" to command.expectedRevision.toString(),
    "outcome" to outcome,
)

private fun DurableDuelSessionReconnect.modeName(): String = when (this) {
    is DurableDuelSessionReconnect.ContiguousReplay -> "contiguous_replay"
    is DurableDuelSessionReconnect.SnapshotAndEvents -> "snapshot_and_events"
    is DurableDuelSessionReconnect.ReplayGap -> "replay_gap"
}
