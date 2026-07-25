package com.mirkori.inplacex.backend.persistence.session

import com.mirkori.inplacex.backend.persistence.IdempotencyKeyReusedException
import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
import org.h2.jdbcx.JdbcDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JdbcDurableDuelSessionRepositoryTest {
    @Test
    fun commandIsAtomicIdempotentAndReconnectReplaysOrderedEvents() {
        val repository = repository()
        repository.createSession("session-1", "duel", "{\"length\":4}", "{\"phase\":\"SETUP\"}")

        val first = repository.apply(command("command-1", 0, "fingerprint-1", "{\"phase\":\"TURN\"}"))
        val replay = repository.apply(command("command-1", 0, "fingerprint-1", "{\"ignored\":true}"))
        val second = repository.apply(command("command-2", 1, "fingerprint-2", "{\"phase\":\"FINISHED\"}"))

        assertFalse(first.replayed)
        assertEquals(first.copy(replayed = true), replay)
        assertEquals(2, second.version)
        val reconnect = repository.reconnect("session-1", 0)
        assertFalse(reconnect.replayGap)
        assertEquals(listOf(1L, 2L), reconnect.events.map { it.sequence })
        assertEquals("{\"phase\":\"FINISHED\"}", reconnect.snapshot.snapshotJson)
        assertEquals(2, reconnect.snapshot.version)
    }

    @Test
    fun commandIdCannotBeReusedWithDifferentFingerprintAndInvalidCursorNeedsSnapshot() {
        val repository = repository()
        repository.createSession("session-1", "duel", "{}", "{}")
        repository.apply(command("command-1", 0, "fingerprint-1", "{\"version\":1}"))

        assertThrows(IdempotencyKeyReusedException::class.java) {
            repository.apply(command("command-1", 0, "different-fingerprint", "{\"version\":1}"))
        }
        val reconnect = repository.reconnect("session-1", 2)
        assertTrue(reconnect.replayGap)
        assertTrue(reconnect.events.isEmpty())
        assertEquals(1, reconnect.snapshot.version)
    }

    private fun command(id: String, expectedVersion: Long, fingerprint: String, snapshot: String) = DurableDuelSessionCommand(
        sessionId = "session-1",
        actorId = "participant-1",
        clientCommandId = id,
        expectedVersion = expectedVersion,
        fingerprint = fingerprint,
        eventType = "duel.turnResult",
        eventPayloadJson = "{\"command\":\"$id\"}",
        resultingSnapshotJson = snapshot,
        resultJson = "{\"accepted\":true,\"command\":\"$id\"}",
    )

    private fun repository(): JdbcDurableDuelSessionRepository {
        val source = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:durable-session-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        JdbcMigrationRunner().migrate(source)
        return JdbcDurableDuelSessionRepository(source)
    }
}
