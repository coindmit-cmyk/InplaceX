package com.mirkori.inplacex.backend.mirkori

import com.mirkori.inplacex.backend.online.persistence.DurableOnlineSession
import com.mirkori.platform.sdk.PlatformAchievementFact
import com.mirkori.platform.sdk.PlatformGameplayEventType
import com.mirkori.platform.sdk.PlatformGameplayFact
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

data class DurableMirkoriTelemetryProjection(
    val gameProfileIds: Set<String>,
    val winnerGameProfileId: String?,
) {
    init {
        require(gameProfileIds.all(String::isCanonicalUuid))
        require(winnerGameProfileId == null || winnerGameProfileId in gameProfileIds)
    }
}

sealed class StoredMirkoriTelemetryFact {
    abstract val eventId: String
    abstract val gameProfileId: String
    abstract val occurredAt: Instant
    abstract val claimToken: String
    abstract val attemptCount: Int

    data class Achievement(
        override val eventId: String,
        override val gameProfileId: String,
        override val occurredAt: Instant,
        override val claimToken: String,
        override val attemptCount: Int,
        val achievementId: String,
    ) : StoredMirkoriTelemetryFact() {
        fun toPlatformFact(): PlatformAchievementFact = PlatformAchievementFact(
            eventId = eventId,
            gameProfileId = gameProfileId,
            achievementId = achievementId,
            achievedAt = occurredAt,
        )
    }

    data class Gameplay(
        override val eventId: String,
        override val gameProfileId: String,
        override val occurredAt: Instant,
        override val claimToken: String,
        override val attemptCount: Int,
        val platformSessionId: String,
        val sequenceNumber: Long,
        val eventType: PlatformGameplayEventType,
    ) : StoredMirkoriTelemetryFact() {
        fun toPlatformFact(): PlatformGameplayFact = PlatformGameplayFact(
            eventId = eventId,
            gameProfileId = gameProfileId,
            sessionId = platformSessionId,
            sequenceNumber = sequenceNumber,
            eventType = eventType,
            occurredAt = occurredAt,
        )
    }
}

data class MirkoriTelemetryDeliveryReceipt(
    val decision: String,
    val sessionStatus: String? = null,
    val trustedDurationSeconds: Long? = null,
)

fun interface MirkoriTelemetrySessionJournal {
    fun recordSessionState(connection: Connection, session: DurableOnlineSession)
}

/** Durable transactional outbox for trusted Mirkori gameplay and achievement facts. */
class JdbcMirkoriTelemetryJournal(
    private val dataSource: DataSource,
) : MirkoriTelemetrySessionJournal {
    override fun recordSessionState(connection: Connection, session: DurableOnlineSession) {
        val projection = session.telemetry ?: return
        val startedAt = session.startedAt ?: return
        projection.gameProfileIds.sorted().forEach { gameProfileId ->
            var tracking = loadTracking(connection, session.sessionId, gameProfileId, lock = true)
            if (tracking == null) {
                val platformSessionId = stableId("duel-", "${session.sessionId}:$gameProfileId")
                insertTracking(
                    connection = connection,
                    duelSessionId = session.sessionId,
                    gameProfileId = gameProfileId,
                    platformSessionId = platformSessionId,
                    occurredAt = startedAt,
                )
                insertGameplayFact(
                    connection = connection,
                    gameProfileId = gameProfileId,
                    platformSessionId = platformSessionId,
                    sequenceNumber = 0,
                    eventType = PlatformGameplayEventType.START,
                    occurredAt = startedAt,
                )
                tracking = GameplayTracking(platformSessionId, nextSequence = 1, status = TrackingStatusOpen)
            }
            val finishedAt = session.finishedAt
            if (finishedAt != null && tracking.status == TrackingStatusOpen) {
                insertGameplayFact(
                    connection = connection,
                    gameProfileId = gameProfileId,
                    platformSessionId = tracking.platformSessionId,
                    sequenceNumber = tracking.nextSequence,
                    eventType = PlatformGameplayEventType.END,
                    occurredAt = finishedAt,
                )
                connection.prepareStatement(
                    """
                    UPDATE mirkori_gameplay_sessions
                    SET next_sequence = ?, last_event_at = ?, status = 'ended', updated_at = ?
                    WHERE duel_session_id = ? AND game_profile_id = ? AND status = 'open'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, tracking.nextSequence + 1)
                    statement.setInstant(2, finishedAt)
                    statement.setInstant(3, finishedAt)
                    statement.setString(4, session.sessionId)
                    statement.setString(5, gameProfileId)
                    check(statement.executeUpdate() == 1) { "Mirkori gameplay tracking changed during duel update" }
                }
            }
        }

        val winner = projection.winnerGameProfileId
        val finishedAt = session.finishedAt
        if (winner != null && finishedAt != null) {
            insertAchievementFact(
                connection = connection,
                eventId = stableId("ach-", "${session.sessionId}:$winner:$FirstWinAchievementId"),
                gameProfileId = winner,
                achievementId = FirstWinAchievementId,
                occurredAt = finishedAt,
            )
        }
    }

    fun enqueueDueHeartbeats(
        now: Instant,
        heartbeatInterval: Duration,
        limit: Int = DefaultBatchSize,
    ): Int = dataSource.transaction { connection ->
        require(!heartbeatInterval.isNegative && !heartbeatInterval.isZero)
        require(limit in 1..MaximumBatchSize)
        val due = connection.prepareStatement(
            """
            SELECT duel_session_id, game_profile_id, platform_session_id, next_sequence
            FROM mirkori_gameplay_sessions
            WHERE status = 'open' AND last_event_at <= ?
            ORDER BY last_event_at, platform_session_id
            LIMIT ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setInstant(1, now.minus(heartbeatInterval))
            statement.setInt(2, limit)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) {
                        add(
                            DueGameplaySession(
                                duelSessionId = results.getString("duel_session_id"),
                                gameProfileId = results.getString("game_profile_id"),
                                platformSessionId = results.getString("platform_session_id"),
                                nextSequence = results.getLong("next_sequence"),
                            ),
                        )
                    }
                }
            }
        }
        due.forEach { session ->
            insertGameplayFact(
                connection = connection,
                gameProfileId = session.gameProfileId,
                platformSessionId = session.platformSessionId,
                sequenceNumber = session.nextSequence,
                eventType = PlatformGameplayEventType.HEARTBEAT,
                occurredAt = now,
            )
            connection.prepareStatement(
                """
                UPDATE mirkori_gameplay_sessions
                SET next_sequence = ?, last_event_at = ?, updated_at = ?
                WHERE duel_session_id = ? AND game_profile_id = ? AND status = 'open'
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, session.nextSequence + 1)
                statement.setInstant(2, now)
                statement.setInstant(3, now)
                statement.setString(4, session.duelSessionId)
                statement.setString(5, session.gameProfileId)
                check(statement.executeUpdate() == 1) { "Mirkori gameplay tracking changed during heartbeat" }
            }
        }
        due.size
    }

    fun claimDue(
        now: Instant,
        leaseDuration: Duration,
    ): StoredMirkoriTelemetryFact? = dataSource.transaction { connection ->
        require(!leaseDuration.isNegative && !leaseDuration.isZero)
        connection.prepareStatement(
            """
            UPDATE mirkori_telemetry_outbox
            SET status = 'pending', claim_token = NULL, claimed_until = NULL,
                next_attempt_at = ?, updated_at = ?
            WHERE status = 'processing' AND claimed_until <= ?
            """.trimIndent(),
        ).use { statement ->
            statement.setInstant(1, now)
            statement.setInstant(2, now)
            statement.setInstant(3, now)
            statement.executeUpdate()
        }
        val row = connection.prepareStatement(
            """
            SELECT candidate.*
            FROM mirkori_telemetry_outbox candidate
            WHERE candidate.status = 'pending'
              AND candidate.next_attempt_at <= ?
              AND (
                  candidate.fact_type = 'achievement' OR NOT EXISTS (
                      SELECT 1 FROM mirkori_telemetry_outbox predecessor
                      WHERE predecessor.platform_session_id = candidate.platform_session_id
                        AND predecessor.sequence_number < candidate.sequence_number
                        AND predecessor.status <> 'delivered'
                  )
              )
            ORDER BY candidate.created_at, candidate.event_id
            LIMIT 1
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setInstant(1, now)
            statement.executeQuery().use { results -> if (results.next()) results.toClaimCandidate() else null }
        } ?: return@transaction null

        val claimToken = UUID.randomUUID().toString()
        connection.prepareStatement(
            """
            UPDATE mirkori_telemetry_outbox
            SET status = 'processing', attempt_count = attempt_count + 1,
                claim_token = ?, claimed_until = ?, updated_at = ?
            WHERE event_id = ? AND status = 'pending'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, claimToken)
            statement.setInstant(2, now.plus(leaseDuration))
            statement.setInstant(3, now)
            statement.setString(4, row.eventId)
            check(statement.executeUpdate() == 1) { "Mirkori telemetry claim raced" }
        }
        row.toStoredFact(claimToken)
    }

    fun markDelivered(
        fact: StoredMirkoriTelemetryFact,
        receipt: MirkoriTelemetryDeliveryReceipt,
        now: Instant,
    ) {
        dataSource.transaction { connection ->
            connection.prepareStatement(
                """
                UPDATE mirkori_telemetry_outbox
                SET status = 'delivered', claim_token = NULL, claimed_until = NULL,
                    platform_decision = ?, platform_session_status = ?, trusted_duration_seconds = ?,
                    last_error_code = NULL, updated_at = ?
                WHERE event_id = ? AND status = 'processing' AND claim_token = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, receipt.decision)
                statement.setString(2, receipt.sessionStatus)
                statement.setNullableLong(3, receipt.trustedDurationSeconds)
                statement.setInstant(4, now)
                statement.setString(5, fact.eventId)
                statement.setString(6, fact.claimToken)
                check(statement.executeUpdate() == 1) { "Mirkori telemetry delivery lease was lost" }
            }
        }
    }

    fun markRetry(
        fact: StoredMirkoriTelemetryFact,
        errorCode: String,
        nextAttemptAt: Instant,
        now: Instant,
    ) {
        require(errorCode.matches(ErrorCodePattern))
        dataSource.transaction { connection ->
            connection.prepareStatement(
                """
                UPDATE mirkori_telemetry_outbox
                SET status = 'pending', claim_token = NULL, claimed_until = NULL,
                    next_attempt_at = ?, last_error_code = ?, updated_at = ?
                WHERE event_id = ? AND status = 'processing' AND claim_token = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInstant(1, nextAttemptAt)
                statement.setString(2, errorCode)
                statement.setInstant(3, now)
                statement.setString(4, fact.eventId)
                statement.setString(5, fact.claimToken)
                check(statement.executeUpdate() == 1) { "Mirkori telemetry retry lease was lost" }
            }
        }
    }

    fun releaseClaim(
        fact: StoredMirkoriTelemetryFact,
        now: Instant,
    ) {
        dataSource.transaction { connection ->
            connection.prepareStatement(
                """
                UPDATE mirkori_telemetry_outbox
                SET status = 'pending', attempt_count = CASE WHEN attempt_count > 0 THEN attempt_count - 1 ELSE 0 END,
                    claim_token = NULL, claimed_until = NULL, next_attempt_at = ?, updated_at = ?
                WHERE event_id = ? AND status = 'processing' AND claim_token = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInstant(1, now)
                statement.setInstant(2, now)
                statement.setString(3, fact.eventId)
                statement.setString(4, fact.claimToken)
                check(statement.executeUpdate() == 1) { "Mirkori telemetry cancellation lease was lost" }
            }
        }
    }

    fun markDead(
        fact: StoredMirkoriTelemetryFact,
        errorCode: String,
        now: Instant,
    ) {
        require(errorCode.matches(ErrorCodePattern))
        dataSource.transaction { connection ->
            connection.prepareStatement(
                """
                UPDATE mirkori_telemetry_outbox
                SET status = 'dead', claim_token = NULL, claimed_until = NULL,
                    last_error_code = ?, updated_at = ?
                WHERE event_id = ? AND status = 'processing' AND claim_token = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, errorCode)
                statement.setInstant(2, now)
                statement.setString(3, fact.eventId)
                statement.setString(4, fact.claimToken)
                check(statement.executeUpdate() == 1) { "Mirkori telemetry dead-letter lease was lost" }
            }
            if (fact is StoredMirkoriTelemetryFact.Gameplay) {
                connection.prepareStatement(
                    """
                    UPDATE mirkori_telemetry_outbox
                    SET status = 'dead', claim_token = NULL, claimed_until = NULL,
                        last_error_code = 'predecessor_dead', updated_at = ?
                    WHERE platform_session_id = ? AND sequence_number > ?
                      AND status IN ('pending', 'processing')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInstant(1, now)
                    statement.setString(2, fact.platformSessionId)
                    statement.setLong(3, fact.sequenceNumber)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun loadTracking(
        connection: Connection,
        duelSessionId: String,
        gameProfileId: String,
        lock: Boolean,
    ): GameplayTracking? = connection.prepareStatement(
        """
        SELECT platform_session_id, next_sequence, status
        FROM mirkori_gameplay_sessions
        WHERE duel_session_id = ? AND game_profile_id = ?
        ${if (lock) "FOR UPDATE" else ""}
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, duelSessionId)
        statement.setString(2, gameProfileId)
        statement.executeQuery().use { results ->
            if (!results.next()) return@use null
            GameplayTracking(
                platformSessionId = results.getString("platform_session_id"),
                nextSequence = results.getLong("next_sequence"),
                status = results.getString("status"),
            )
        }
    }

    private fun insertTracking(
        connection: Connection,
        duelSessionId: String,
        gameProfileId: String,
        platformSessionId: String,
        occurredAt: Instant,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO mirkori_gameplay_sessions(
                duel_session_id, game_profile_id, platform_session_id, next_sequence,
                last_event_at, status, created_at, updated_at
            ) VALUES (?, ?, ?, 1, ?, 'open', ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, duelSessionId)
            statement.setString(2, gameProfileId)
            statement.setString(3, platformSessionId)
            statement.setInstant(4, occurredAt)
            statement.setInstant(5, occurredAt)
            statement.setInstant(6, occurredAt)
            statement.executeUpdate()
        }
    }

    private fun insertGameplayFact(
        connection: Connection,
        gameProfileId: String,
        platformSessionId: String,
        sequenceNumber: Long,
        eventType: PlatformGameplayEventType,
        occurredAt: Instant,
    ) {
        val eventId = stableId("play-", "$platformSessionId:$sequenceNumber:${eventType.wireName}")
        connection.prepareStatement(
            """
            INSERT INTO mirkori_telemetry_outbox(
                event_id, fact_type, game_profile_id, platform_session_id, sequence_number,
                gameplay_event_type, occurred_at, status, next_attempt_at, created_at, updated_at
            ) VALUES (?, 'gameplay', ?, ?, ?, ?, ?, 'pending', ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, eventId)
            statement.setString(2, gameProfileId)
            statement.setString(3, platformSessionId)
            statement.setLong(4, sequenceNumber)
            statement.setString(5, eventType.wireName)
            statement.setInstant(6, occurredAt)
            statement.setInstant(7, occurredAt)
            statement.setInstant(8, occurredAt)
            statement.setInstant(9, occurredAt)
            statement.executeUpdate()
        }
    }

    private fun insertAchievementFact(
        connection: Connection,
        eventId: String,
        gameProfileId: String,
        achievementId: String,
        occurredAt: Instant,
    ) {
        val exists = connection.prepareStatement(
            "SELECT 1 FROM mirkori_telemetry_outbox WHERE event_id = ?",
        ).use { statement ->
            statement.setString(1, eventId)
            statement.executeQuery().use(ResultSet::next)
        }
        if (exists) return
        connection.prepareStatement(
            """
            INSERT INTO mirkori_telemetry_outbox(
                event_id, fact_type, game_profile_id, achievement_id, occurred_at,
                status, next_attempt_at, created_at, updated_at
            ) VALUES (?, 'achievement', ?, ?, ?, 'pending', ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, eventId)
            statement.setString(2, gameProfileId)
            statement.setString(3, achievementId)
            statement.setInstant(4, occurredAt)
            statement.setInstant(5, occurredAt)
            statement.setInstant(6, occurredAt)
            statement.setInstant(7, occurredAt)
            statement.executeUpdate()
        }
    }

    private data class GameplayTracking(
        val platformSessionId: String,
        val nextSequence: Long,
        val status: String,
    )

    private data class DueGameplaySession(
        val duelSessionId: String,
        val gameProfileId: String,
        val platformSessionId: String,
        val nextSequence: Long,
    )

    private data class ClaimCandidate(
        val eventId: String,
        val factType: String,
        val gameProfileId: String,
        val achievementId: String?,
        val platformSessionId: String?,
        val sequenceNumber: Long?,
        val gameplayEventType: String?,
        val occurredAt: Instant,
        val attemptCount: Int,
    ) {
        fun toStoredFact(claimToken: String): StoredMirkoriTelemetryFact = when (factType) {
            FactTypeAchievement -> StoredMirkoriTelemetryFact.Achievement(
                eventId = eventId,
                gameProfileId = gameProfileId,
                occurredAt = occurredAt,
                claimToken = claimToken,
                attemptCount = attemptCount + 1,
                achievementId = requireNotNull(achievementId),
            )
            FactTypeGameplay -> StoredMirkoriTelemetryFact.Gameplay(
                eventId = eventId,
                gameProfileId = gameProfileId,
                occurredAt = occurredAt,
                claimToken = claimToken,
                attemptCount = attemptCount + 1,
                platformSessionId = requireNotNull(platformSessionId),
                sequenceNumber = requireNotNull(sequenceNumber),
                eventType = PlatformGameplayEventType.entries.firstOrNull { it.wireName == gameplayEventType }
                    ?: error("Invalid durable gameplay event type"),
            )
            else -> error("Invalid durable Mirkori telemetry fact type")
        }
    }

    private fun ResultSet.toClaimCandidate(): ClaimCandidate = ClaimCandidate(
        eventId = getString("event_id"),
        factType = getString("fact_type"),
        gameProfileId = getString("game_profile_id"),
        achievementId = getString("achievement_id"),
        platformSessionId = getString("platform_session_id"),
        sequenceNumber = getObject("sequence_number")?.let { getLong("sequence_number") },
        gameplayEventType = getString("gameplay_event_type"),
        occurredAt = requireNotNull(getObject("occurred_at", OffsetDateTime::class.java)?.toInstant()),
        attemptCount = getInt("attempt_count"),
    )

    private companion object {
        const val FactTypeAchievement = "achievement"
        const val FactTypeGameplay = "gameplay"
        const val TrackingStatusOpen = "open"
        const val FirstWinAchievementId = "first_win"
        const val DefaultBatchSize = 100
        const val MaximumBatchSize = 1000
        val ErrorCodePattern = Regex("[a-z][a-z0-9_]{0,63}")
    }
}

private fun stableId(prefix: String, source: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(StandardCharsets.UTF_8))
    return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private fun String.isCanonicalUuid(): Boolean =
    runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)

private fun java.sql.PreparedStatement.setInstant(index: Int, value: Instant) {
    setObject(index, value.atOffset(ZoneOffset.UTC))
}

private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) setObject(index, null) else setLong(index, value)
}

private inline fun <T> DataSource.transaction(block: (Connection) -> T): T = connection.use { connection ->
    val previousAutoCommit = connection.autoCommit
    connection.autoCommit = false
    try {
        block(connection).also { connection.commit() }
    } catch (error: Exception) {
        connection.rollback()
        throw error
    } finally {
        connection.autoCommit = previousAutoCommit
    }
}
