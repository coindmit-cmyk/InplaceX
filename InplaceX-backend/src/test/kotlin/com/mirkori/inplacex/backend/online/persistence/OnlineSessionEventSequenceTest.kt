package com.mirkori.inplacex.backend.online.persistence

import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.h2.jdbcx.JdbcDataSource
import org.junit.Assert.assertEquals
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
}
