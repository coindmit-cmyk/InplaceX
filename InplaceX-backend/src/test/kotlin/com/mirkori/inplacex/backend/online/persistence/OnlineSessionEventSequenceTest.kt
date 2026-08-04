package com.mirkori.inplacex.backend.online.persistence

import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.h2.jdbcx.JdbcDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSessionEventSequenceTest {
    @Test
    fun `jdbc allocator stays increasing across backend instances`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:ws-sequence-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        }
        JdbcMigrationRunner().migrate(dataSource)
        val sessionId = UUID.randomUUID().toString()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO duel_sessions(id, mode, status, config_json, version, created_at)
                VALUES (?, 'ONLINE_DUEL', 'SETUP', '{}', 0, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setObject(2, Instant.parse("2026-08-04T12:00:00Z").atOffset(ZoneOffset.UTC))
                statement.executeUpdate()
            }
        }

        val firstInstance = JdbcOnlineSessionEventSequence(dataSource)
        val secondInstance = JdbcOnlineSessionEventSequence(dataSource)
        val first = firstInstance.next(
            sessionId,
            "session.snapshot",
            Instant.parse("2026-08-04T12:00:01Z"),
        )
        val second = secondInstance.next(
            sessionId,
            "connection.heartbeat",
            Instant.parse("2026-08-04T12:00:02Z"),
        )

        assertTrue(second > first)
        assertTrue(firstInstance.isReplayable(sessionId, first))
        assertFalse(firstInstance.isReplayable(sessionId, second + 100))

        val change = secondInstance.sessionChanged(
            sessionId,
            revision = 3,
            Instant.parse("2026-08-04T12:00:03Z"),
        )
        assertEquals(
            listOf(
                OnlineSessionEvent(second, null),
                OnlineSessionEvent(change, 3),
            ),
            firstInstance.readAfter(sessionId, first, 10),
        )

        JdbcOnlineSessionRepository(
            dataSource,
            OnlineStateCipher(ByteArray(32) { index -> (index + 1).toByte() }),
        ).use { repository -> repository.delete(sessionId) }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM duel_events").use { results ->
                    assertTrue(results.next())
                    assertEquals(0, results.getInt(1))
                }
            }
        }
    }

    @Test
    fun `session revision update atomically appends only a safe change marker`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:ws-revision-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        }
        JdbcMigrationRunner().migrate(dataSource)
        val sessionId = UUID.randomUUID().toString()
        val createdAt = Instant.parse("2026-08-04T13:00:00Z")
        JdbcOnlineSessionRepository(
            dataSource,
            OnlineStateCipher(ByteArray(32) { index -> (index + 11).toByte() }),
        ).use { repository ->
            val initial = DurableOnlineSession(
                sessionId = sessionId,
                revision = 0,
                status = "SETUP",
                stateJson = "{\"privateSecret\":\"987654\"}",
                createdAt = createdAt,
                startedAt = null,
                finishedAt = null,
                expiresAt = createdAt.plusSeconds(300),
            )
            repository.create(initial)
            repository.update(initial.copy(revision = 1, status = "ACTIVE"), expectedRevision = 0)

            assertEquals(
                listOf(OnlineSessionEvent(eventSequence = 1, sessionRevision = 1)),
                JdbcOnlineSessionEventSequence(dataSource).readAfter(sessionId, 0, 10),
            )
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT event_type, payload_json FROM duel_events WHERE session_id = ?",
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.executeQuery().use { results ->
                        assertTrue(results.next())
                        assertEquals("session.changed", results.getString("event_type"))
                        assertEquals("{}", results.getString("payload_json"))
                        assertFalse(results.next())
                    }
                }
            }
        }
    }
}
