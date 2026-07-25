package com.mirkori.inplacex.backend.persistence.session

import com.mirkori.inplacex.backend.persistence.IdempotencyKeyReusedException
import com.mirkori.inplacex.backend.persistence.RevisionConflictException
import com.mirkori.inplacex.backend.persistence.transaction
import java.sql.Connection
import java.time.Instant
import javax.sql.DataSource

data class DurableDuelSessionState(
    val sessionId: String,
    val version: Long,
    val eventCursor: Long,
    val firstRetainedEventSeq: Long,
    val snapshotJson: String,
    val updatedAt: Instant,
)

data class DurableDuelSessionEvent(
    val sequence: Long,
    val type: String,
    val payloadJson: String,
    val createdAt: Instant,
)

data class DurableDuelSessionCommand(
    val sessionId: String,
    val actorId: String,
    val clientCommandId: String,
    val expectedVersion: Long,
    val fingerprint: String,
    val eventType: String,
    val eventPayloadJson: String,
    val resultingSnapshotJson: String,
    val resultJson: String,
)

data class DurableDuelSessionCommandResult(
    val version: Long,
    val eventCursor: Long,
    val resultJson: String,
    val replayed: Boolean,
)

data class DurableDuelSessionReconnect(
    val snapshot: DurableDuelSessionState,
    val events: List<DurableDuelSessionEvent>,
    val replayGap: Boolean,
)

/**
 * Граница durable persistence для авторитетного состояния дуэли. Команда,
 * её receipt, следующий snapshot и событие фиксируются одной транзакцией.
 */
class JdbcDurableDuelSessionRepository(private val dataSource: DataSource) {
    fun createSession(sessionId: String, mode: String, configJson: String, initialSnapshotJson: String) =
        dataSource.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO duel_sessions(id, mode, status, config_json, version) VALUES (?, ?, 'SETUP', ?, 0)",
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setString(2, mode)
                statement.setString(3, configJson)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO duel_session_states(session_id, version, event_cursor, first_retained_event_seq, snapshot_json)
                VALUES (?, 0, 0, 0, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setString(2, initialSnapshotJson)
                statement.executeUpdate()
            }
        }

    fun readState(sessionId: String): DurableDuelSessionState = dataSource.connection.use { connection ->
        readState(connection, sessionId, lock = false)
    }

    fun apply(command: DurableDuelSessionCommand): DurableDuelSessionCommandResult = dataSource.transaction { connection ->
        existingReceipt(connection, command)?.let { receipt ->
            if (receipt.fingerprint != command.fingerprint) throw IdempotencyKeyReusedException()
            return@transaction receipt.toResult(replayed = true)
        }
        val state = readState(connection, command.sessionId, lock = true)
        if (state.version != command.expectedVersion) {
            existingReceipt(connection, command)?.let { receipt ->
                if (receipt.fingerprint != command.fingerprint) throw IdempotencyKeyReusedException()
                return@transaction receipt.toResult(replayed = true)
            }
            throw RevisionConflictException(command.sessionId, command.expectedVersion)
        }
        val nextVersion = state.version + 1
        val nextEventCursor = state.eventCursor + 1
        connection.prepareStatement(
            "UPDATE duel_sessions SET version = ? WHERE id = ? AND version = ?",
        ).use { statement ->
            statement.setLong(1, nextVersion)
            statement.setString(2, command.sessionId)
            statement.setLong(3, state.version)
            check(statement.executeUpdate() == 1) { "Duel session version changed while persisting command" }
        }
        connection.prepareStatement(
            """
            UPDATE duel_session_states
            SET version = ?, event_cursor = ?, snapshot_json = ?, updated_at = CURRENT_TIMESTAMP
            WHERE session_id = ? AND version = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, nextVersion)
            statement.setLong(2, nextEventCursor)
            statement.setString(3, command.resultingSnapshotJson)
            statement.setString(4, command.sessionId)
            statement.setLong(5, state.version)
            check(statement.executeUpdate() == 1) { "Durable duel state version changed while persisting command" }
        }
        connection.prepareStatement(
            "INSERT INTO duel_session_events(session_id, event_seq, event_type, payload_json) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, command.sessionId)
            statement.setLong(2, nextEventCursor)
            statement.setString(3, command.eventType)
            statement.setString(4, command.eventPayloadJson)
            statement.executeUpdate()
        }
        connection.prepareStatement(
            """
            INSERT INTO duel_command_receipts(
                session_id, actor_id, client_command_id, fingerprint, version, event_cursor, result_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, command.sessionId)
            statement.setString(2, command.actorId)
            statement.setString(3, command.clientCommandId)
            statement.setString(4, command.fingerprint)
            statement.setLong(5, nextVersion)
            statement.setLong(6, nextEventCursor)
            statement.setString(7, command.resultJson)
            statement.executeUpdate()
        }
        DurableDuelSessionCommandResult(nextVersion, nextEventCursor, command.resultJson, replayed = false)
    }

    fun reconnect(sessionId: String, lastSeenEventSeq: Long): DurableDuelSessionReconnect = dataSource.connection.use { connection ->
        val state = readState(connection, sessionId, lock = false)
        val replayGap = lastSeenEventSeq < state.firstRetainedEventSeq - 1 || lastSeenEventSeq > state.eventCursor
        val events = if (replayGap) emptyList() else readEventsAfter(connection, sessionId, lastSeenEventSeq)
        DurableDuelSessionReconnect(state, events, replayGap)
    }

    private fun readState(connection: Connection, sessionId: String, lock: Boolean): DurableDuelSessionState =
        connection.prepareStatement(
            """
            SELECT version, event_cursor, first_retained_event_seq, snapshot_json, updated_at
            FROM duel_session_states WHERE session_id = ?${if (lock) " FOR UPDATE" else ""}
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Unknown duel session" }
                DurableDuelSessionState(
                    sessionId = sessionId,
                    version = resultSet.getLong("version"),
                    eventCursor = resultSet.getLong("event_cursor"),
                    firstRetainedEventSeq = resultSet.getLong("first_retained_event_seq"),
                    snapshotJson = resultSet.getString("snapshot_json"),
                    updatedAt = resultSet.getObject("updated_at", java.time.OffsetDateTime::class.java).toInstant(),
                )
            }
        }

    private fun readEventsAfter(connection: Connection, sessionId: String, cursor: Long): List<DurableDuelSessionEvent> =
        connection.prepareStatement(
            """
            SELECT event_seq, event_type, payload_json, created_at FROM duel_session_events
            WHERE session_id = ? AND event_seq > ? ORDER BY event_seq ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setLong(2, cursor)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(DurableDuelSessionEvent(
                            sequence = resultSet.getLong("event_seq"),
                            type = resultSet.getString("event_type"),
                            payloadJson = resultSet.getString("payload_json"),
                            createdAt = resultSet.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant(),
                        ))
                    }
                }
            }
        }

    private fun existingReceipt(connection: Connection, command: DurableDuelSessionCommand): Receipt? =
        connection.prepareStatement(
            """
            SELECT fingerprint, version, event_cursor, result_json FROM duel_command_receipts
            WHERE session_id = ? AND actor_id = ? AND client_command_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, command.sessionId)
            statement.setString(2, command.actorId)
            statement.setString(3, command.clientCommandId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) Receipt(
                    fingerprint = resultSet.getString("fingerprint"),
                    version = resultSet.getLong("version"),
                    eventCursor = resultSet.getLong("event_cursor"),
                    resultJson = resultSet.getString("result_json"),
                ) else null
            }
        }

    private data class Receipt(val fingerprint: String, val version: Long, val eventCursor: Long, val resultJson: String) {
        fun toResult(replayed: Boolean) = DurableDuelSessionCommandResult(version, eventCursor, resultJson, replayed)
    }
}
