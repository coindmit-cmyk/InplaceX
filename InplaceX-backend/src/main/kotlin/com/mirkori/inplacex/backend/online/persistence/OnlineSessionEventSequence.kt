package com.mirkori.inplacex.backend.online.persistence

import java.sql.Connection
import java.sql.Statement
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

data class OnlineSessionEvent(
    val eventSequence: Long,
    val sessionRevision: Long?,
)

/** Durable cursor store used for reconnect recovery and cross-instance change discovery. */
interface OnlineSessionEventSequence {
    fun next(sessionId: String, eventType: String, createdAt: Instant): Long
    fun sessionChanged(sessionId: String, revision: Long, createdAt: Instant): Long
    fun isReplayable(sessionId: String, eventSequence: Long): Boolean
    fun readAfter(sessionId: String, eventSequence: Long, limit: Int): List<OnlineSessionEvent>
}

class InMemoryOnlineSessionEventSequence : OnlineSessionEventSequence {
    private val sequences = ConcurrentHashMap<String, AtomicLong>()
    private val events = ConcurrentHashMap<String, MutableList<OnlineSessionEvent>>()

    override fun next(sessionId: String, eventType: String, createdAt: Instant): Long =
        append(sessionId, null)

    override fun sessionChanged(sessionId: String, revision: Long, createdAt: Instant): Long =
        append(sessionId, revision)

    override fun isReplayable(sessionId: String, eventSequence: Long): Boolean =
        eventSequence == 0L || synchronized(events) {
            events[sessionId]?.any { it.eventSequence == eventSequence } == true
        }

    override fun readAfter(sessionId: String, eventSequence: Long, limit: Int): List<OnlineSessionEvent> {
        require(limit > 0)
        return synchronized(events) {
            events[sessionId].orEmpty().asSequence()
                .filter { it.eventSequence > eventSequence }
                .sortedBy(OnlineSessionEvent::eventSequence)
                .take(limit)
                .toList()
        }
    }

    private fun append(sessionId: String, revision: Long?): Long {
        val sequence = sequences.computeIfAbsent(sessionId) { AtomicLong() }.incrementAndGet()
        synchronized(events) {
            events.computeIfAbsent(sessionId) { mutableListOf() }
                .add(OnlineSessionEvent(sequence, revision))
        }
        return sequence
    }
}

/** Uses the duel event identity as the durable cross-instance WebSocket cursor. */
class JdbcOnlineSessionEventSequence(
    private val dataSource: DataSource,
) : OnlineSessionEventSequence {
    override fun next(sessionId: String, eventType: String, createdAt: Instant): Long =
        dataSource.connection.use { connection ->
            insertOnlineSessionEvent(connection, sessionId, eventType, null, createdAt)
        }

    override fun sessionChanged(sessionId: String, revision: Long, createdAt: Instant): Long =
        dataSource.connection.use { connection ->
            insertOnlineSessionEvent(connection, sessionId, SessionChangedEventType, revision, createdAt)
        }

    override fun isReplayable(sessionId: String, eventSequence: Long): Boolean {
        if (eventSequence == 0L) return true
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM duel_events WHERE session_id = ? AND id = ?",
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setLong(2, eventSequence)
                statement.executeQuery().use { it.next() }
            }
        }
    }

    override fun readAfter(sessionId: String, eventSequence: Long, limit: Int): List<OnlineSessionEvent> {
        require(limit > 0)
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, session_revision
                FROM duel_events
                WHERE session_id = ? AND id > ?
                ORDER BY id
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setLong(2, eventSequence)
                statement.setInt(3, limit)
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) {
                            add(
                                OnlineSessionEvent(
                                    eventSequence = results.getLong("id"),
                                    sessionRevision = results.getLong("session_revision")
                                        .takeUnless { results.wasNull() },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal const val SessionChangedEventType = "session.changed"

internal fun insertOnlineSessionEvent(
    connection: Connection,
    sessionId: String,
    eventType: String,
    sessionRevision: Long?,
    createdAt: Instant,
): Long = connection.prepareStatement(
    """
    INSERT INTO duel_events(session_id, event_type, payload_json, session_revision, created_at)
    VALUES (?, ?, '{}', ?, ?)
    """.trimIndent(),
    Statement.RETURN_GENERATED_KEYS,
).use { statement ->
    statement.setString(1, sessionId)
    statement.setString(2, eventType)
    if (sessionRevision == null) statement.setObject(3, null) else statement.setLong(3, sessionRevision)
    statement.setObject(4, createdAt.atOffset(ZoneOffset.UTC))
    check(statement.executeUpdate() == 1) { "WebSocket event cursor was not persisted" }
    statement.generatedKeys.use { keys ->
        check(keys.next()) { "WebSocket event cursor was not returned" }
        keys.getLong(1)
    }
}
