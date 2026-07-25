package com.mirkori.inplacex.backend.persistence

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

class JdbcSessionRepository(private val dataSource: DataSource) {
    fun createSession(sessionId: String, mode: String, configJson: String) = dataSource.transaction { connection ->
        connection.prepareStatement(
            "INSERT INTO duel_sessions(id, mode, status, config_json, version) VALUES (?, ?, 'SETUP', ?, 0)",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, mode)
            statement.setString(3, configJson)
            statement.executeUpdate()
        }
    }

    fun appendCommand(
        sessionId: String,
        clientCommandId: String,
        expectedVersion: Long,
        commandType: String,
        payloadJson: String,
    ): StoredSessionCommand = dataSource.transaction { connection ->
        existingCommand(connection, sessionId, clientCommandId)?.let { version ->
            return@transaction StoredSessionCommand(sessionId, clientCommandId, version, replayed = true)
        }
        val nextVersion = expectedVersion + 1
        val updated = connection.prepareStatement(
            "UPDATE duel_sessions SET version = ? WHERE id = ? AND version = ?",
        ).use { statement ->
            statement.setLong(1, nextVersion)
            statement.setString(2, sessionId)
            statement.setLong(3, expectedVersion)
            statement.executeUpdate()
        }
        if (updated != 1) {
            existingCommand(connection, sessionId, clientCommandId)?.let { version ->
                return@transaction StoredSessionCommand(sessionId, clientCommandId, version, replayed = true)
            }
            throw RevisionConflictException(sessionId, expectedVersion)
        }
        connection.prepareStatement(
            """
            INSERT INTO duel_commands(session_id, client_command_id, version, command_type, payload_json)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, clientCommandId)
            statement.setLong(3, nextVersion)
            statement.setString(4, commandType)
            statement.setString(5, payloadJson)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO duel_events(session_id, event_type, payload_json) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, commandType)
            statement.setString(3, payloadJson)
            statement.executeUpdate()
        }
        StoredSessionCommand(sessionId, clientCommandId, nextVersion, replayed = false)
    }

    private fun existingCommand(connection: Connection, sessionId: String, clientCommandId: String): Long? =
        connection.prepareStatement(
            "SELECT version FROM duel_commands WHERE session_id = ? AND client_command_id = ?",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, clientCommandId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getLong("version") else null
            }
        }
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
