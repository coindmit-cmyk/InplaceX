package com.mirkori.inplacex.backend.persistence

import com.mirkori.inplacex.backend.persistence.session.DurableDuelCommandContent
import com.mirkori.inplacex.backend.persistence.session.DurableDuelSessionCommand
import com.mirkori.inplacex.backend.persistence.session.JdbcDurableDuelSessionRepository
import com.mirkori.inplacex.backend.persistence.session.PublicDuelCommandResult
import com.mirkori.inplacex.backend.persistence.session.PublicDuelParticipant
import com.mirkori.inplacex.backend.persistence.session.PublicDuelPhase
import com.mirkori.inplacex.backend.persistence.session.PublicDuelSessionEvent
import com.mirkori.inplacex.backend.persistence.session.PublicDuelSessionSnapshot
import com.mirkori.inplacex.backend.persistence.session.PublicGameConfig
import com.mirkori.inplacex.backend.persistence.session.PublicParticipantSlot
import com.mirkori.inplacex.backend.persistence.session.PublicParticipantType
import com.mirkori.inplacex.backend.persistence.session.PublicSessionJson
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

class RevisionConflictException(playerId: String, expectedRevision: Long) : IllegalStateException(
    "Save revision conflict for player $playerId at revision $expectedRevision",
)

data class StoredSaveRevision(
    val playerId: String,
    val revision: Long,
    val payloadJson: String,
    val schemaVersion: Int,
)

data class StoredSaveSnapshot(
    val playerId: String,
    val revision: Long,
    val payloadJson: String,
    val schemaVersion: Int,
    val updatedAt: Instant,
)

data class IdempotentSaveWrite(
    val snapshot: StoredSaveSnapshot,
    val replayed: Boolean,
)

class IdempotencyKeyReusedException : IllegalStateException("Idempotency key was reused with a different request")

data class MatchmakingTicket(
    val id: String,
    val playerId: String,
    val mode: String,
    val expiresAt: Instant,
)

data class StoredSessionCommand(
    val sessionId: String,
    val clientCommandId: String,
    val version: Long,
    val replayed: Boolean,
)

class JdbcPlayerRepository(private val dataSource: DataSource) {
    fun create(id: String, displayName: String) = dataSource.transaction { connection ->
        connection.prepareStatement(
            "INSERT INTO players(id, display_name) VALUES (?, ?)",
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, displayName)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO save_heads(player_id, latest_revision) VALUES (?, 0)",
        ).use { statement ->
            statement.setString(1, id)
            statement.executeUpdate()
        }
    }
}

class JdbcSaveRepository(private val dataSource: DataSource) {
    fun write(
        playerId: String,
        expectedRevision: Long,
        payloadJson: String,
        schemaVersion: Int,
    ): StoredSaveRevision = dataSource.transaction { connection ->
        val changed = connection.prepareStatement(
            """
            UPDATE save_heads
            SET latest_revision = latest_revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE player_id = ? AND latest_revision = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, playerId)
            statement.setLong(2, expectedRevision)
            statement.executeUpdate()
        }
        if (changed != 1) throw RevisionConflictException(playerId, expectedRevision)

        val revision = expectedRevision + 1
        connection.prepareStatement(
            """
            INSERT INTO save_revisions(player_id, revision, payload_json, schema_version)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, playerId)
            statement.setLong(2, revision)
            statement.setString(3, payloadJson)
            statement.setInt(4, schemaVersion)
            statement.executeUpdate()
        }
        StoredSaveRevision(playerId, revision, payloadJson, schemaVersion)
    }

    fun read(playerId: String): StoredSaveSnapshot = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT heads.latest_revision, heads.updated_at, revisions.payload_json, revisions.schema_version
            FROM save_heads heads
            LEFT JOIN save_revisions revisions
                ON revisions.player_id = heads.player_id AND revisions.revision = heads.latest_revision
            WHERE heads.player_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, playerId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Unknown player" }
                StoredSaveSnapshot(
                    playerId = playerId,
                    revision = resultSet.getLong("latest_revision"),
                    payloadJson = resultSet.getString("payload_json") ?: "{}",
                    schemaVersion = resultSet.getInt("schema_version").takeIf { !resultSet.wasNull() } ?: 1,
                    updatedAt = resultSet.getObject("updated_at", java.time.OffsetDateTime::class.java).toInstant(),
                )
            }
        }
    }

    fun writeIdempotent(
        playerId: String,
        commandId: String,
        expectedRevision: Long,
        payloadJson: String,
        schemaVersion: Int,
        fingerprint: String,
    ): IdempotentSaveWrite = dataSource.transaction { connection ->
        existingSaveCommand(connection, playerId, commandId)?.let { existing ->
            if (existing.fingerprint != fingerprint) throw IdempotencyKeyReusedException()
            return@transaction IdempotentSaveWrite(readRevision(connection, playerId, existing.revision), replayed = true)
        }
        val changed = connection.prepareStatement(
            """
            UPDATE save_heads
            SET latest_revision = latest_revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE player_id = ? AND latest_revision = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, playerId)
            statement.setLong(2, expectedRevision)
            statement.executeUpdate()
        }
        if (changed != 1) {
            existingSaveCommand(connection, playerId, commandId)?.let { existing ->
                if (existing.fingerprint != fingerprint) throw IdempotencyKeyReusedException()
                return@transaction IdempotentSaveWrite(readRevision(connection, playerId, existing.revision), replayed = true)
            }
            throw RevisionConflictException(playerId, expectedRevision)
        }
        val revision = expectedRevision + 1
        connection.prepareStatement(
            "INSERT INTO save_revisions(player_id, revision, payload_json, schema_version) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, playerId)
            statement.setLong(2, revision)
            statement.setString(3, payloadJson)
            statement.setInt(4, schemaVersion)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO save_commands(player_id, command_id, fingerprint, revision) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, playerId)
            statement.setString(2, commandId)
            statement.setString(3, fingerprint)
            statement.setLong(4, revision)
            statement.executeUpdate()
        }
        IdempotentSaveWrite(readRevision(connection, playerId, revision), replayed = false)
    }

    private fun existingSaveCommand(connection: Connection, playerId: String, commandId: String): SaveCommand? =
        connection.prepareStatement(
            "SELECT fingerprint, revision FROM save_commands WHERE player_id = ? AND command_id = ?",
        ).use { statement ->
            statement.setString(1, playerId)
            statement.setString(2, commandId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) SaveCommand(resultSet.getString("fingerprint"), resultSet.getLong("revision")) else null
            }
        }

    private fun readRevision(connection: Connection, playerId: String, revision: Long): StoredSaveSnapshot =
        connection.prepareStatement(
            """
            SELECT revisions.payload_json, revisions.schema_version, heads.updated_at
            FROM save_revisions revisions
            JOIN save_heads heads ON heads.player_id = revisions.player_id
            WHERE revisions.player_id = ? AND revisions.revision = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, playerId)
            statement.setLong(2, revision)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Missing save revision" }
                StoredSaveSnapshot(
                    playerId = playerId,
                    revision = revision,
                    payloadJson = resultSet.getString("payload_json"),
                    schemaVersion = resultSet.getInt("schema_version"),
                    updatedAt = resultSet.getObject("updated_at", java.time.OffsetDateTime::class.java).toInstant(),
                )
            }
        }

    private data class SaveCommand(val fingerprint: String, val revision: Long)
}

class JdbcTicketRepository(private val dataSource: DataSource) {
    fun create(ticket: MatchmakingTicket) = dataSource.transaction { connection ->
        connection.prepareStatement(
            """
            INSERT INTO matchmaking_tickets(id, player_id, mode, status, expires_at)
            VALUES (?, ?, ?, 'QUEUED', ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, ticket.id)
            statement.setString(2, ticket.playerId)
            statement.setString(3, ticket.mode)
            statement.setObject(4, ticket.expiresAt)
            statement.executeUpdate()
        }
    }
}

class JdbcSessionRepository(dataSource: DataSource) {
    private val durable = JdbcDurableDuelSessionRepository(dataSource)

    fun createSession(sessionId: String, mode: String, configJson: String) {
        val config: PublicGameConfig = PublicSessionJson.decodeConfig(configJson)
        durable.createSession(
            sessionId = sessionId,
            mode = mode,
            initialSnapshot = PublicDuelSessionSnapshot(
                sessionId = sessionId,
                revision = 0,
                eventSequence = 0,
                phase = PublicDuelPhase.SETUP_WAITING_FOR_PLAYERS,
                config = config,
                participants = listOf(
                    PublicDuelParticipant(
                        participantId = LEGACY_ACTOR_ID,
                        slot = PublicParticipantSlot.A,
                        participantType = PublicParticipantType.HUMAN,
                        secretSubmitted = false,
                        connected = true,
                    ),
                ),
            ),
        )
    }

    @Deprecated("Use JdbcDurableDuelSessionRepository with typed command content")
    fun appendCommand(
        sessionId: String,
        clientCommandId: String,
        expectedVersion: Long,
        commandType: String,
        payloadJson: String,
    ): StoredSessionCommand {
        require(commandType == "SET_SECRET") {
            "Legacy session commands are restricted to the closed SET_SECRET compatibility path"
        }
        val current = durable.readCurrentSnapshot(sessionId)
        val event = PublicSessionJson.decodeLegacySecretStatus(payloadJson, LEGACY_ACTOR_ID)
        val participants = current.participants.map { participant ->
            if (participant.participantId == event.participantId) {
                participant.copy(secretSubmitted = true)
            } else {
                participant
            }
        }
        val commit = durable.apply(
            DurableDuelSessionCommand(
                sessionId = sessionId,
                actorId = event.participantId,
                clientCommandId = clientCommandId,
                expectedRevision = expectedVersion,
                content = DurableDuelCommandContent.RecordSecretStatus,
                resultingSnapshot = current.copy(
                    revision = expectedVersion + 1,
                    eventSequence = current.eventSequence + 1,
                    participants = participants,
                ),
                event = event,
                result = PublicDuelCommandResult.SecretAccepted(event.participantId),
            ),
        )
        return StoredSessionCommand(
            sessionId = sessionId,
            clientCommandId = clientCommandId,
            version = commit.receipt.revision,
            replayed = commit.replayed,
        )
    }

    companion object {
        private const val LEGACY_ACTOR_ID = "legacy-actor"
    }
}

internal inline fun <T> DataSource.transaction(block: (Connection) -> T): T = connection.use { connection ->
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
