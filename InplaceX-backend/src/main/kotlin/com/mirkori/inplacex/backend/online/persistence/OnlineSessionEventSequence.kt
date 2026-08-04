package com.mirkori.inplacex.backend.online.persistence

import java.sql.Statement
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

/** Allocates a durable, strictly increasing server-event cursor for one session. */
fun interface OnlineSessionEventSequence {
    fun next(sessionId: String, eventType: String, createdAt: Instant): Long
}

class InMemoryOnlineSessionEventSequence : OnlineSessionEventSequence {
    private val sequences = ConcurrentHashMap<String, AtomicLong>()

    override fun next(sessionId: String, eventType: String, createdAt: Instant): Long =
        sequences.computeIfAbsent(sessionId) { AtomicLong() }.incrementAndGet()
}

/** Uses the existing duel event identity as the cross-instance WebSocket cursor. */
class JdbcOnlineSessionEventSequence(
    private val dataSource: DataSource,
) : OnlineSessionEventSequence {
    override fun next(sessionId: String, eventType: String, createdAt: Instant): Long =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO duel_events(session_id, event_type, payload_json, created_at)
                VALUES (?, ?, '{}', ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setString(2, eventType)
                statement.setObject(3, createdAt.atOffset(ZoneOffset.UTC))
                check(statement.executeUpdate() == 1) { "WebSocket event cursor was not persisted" }
                statement.generatedKeys.use { keys ->
                    check(keys.next()) { "WebSocket event cursor was not returned" }
                    keys.getLong(1)
                }
            }
        }
}
