package com.mirkori.inplacex.backend.online

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeOnlineDuelServiceTest {
    private val playerId = UUID.randomUUID().toString()
    private val service = AuthoritativeOnlineDuelService(
        Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `matchmaking creates a server-owned bot fallback session`() {
        val commandId = UUID.randomUUID().toString()

        val first = service.createTicket(playerId, commandId, OnlineMatchMode.CLASSIC)
        val replay = service.createTicket(playerId, commandId, OnlineMatchMode.CLASSIC)
        val snapshot = service.readSession(playerId, requireNotNull(first.sessionId))

        assertEquals(first, replay)
        assertEquals(MatchmakingStatus.MATCHED, first.status)
        assertTrue(first.matchedWithBot)
        assertEquals("setup", snapshot.phase)
        assertEquals(0, snapshot.revision)
        assertTrue(snapshot.attempts.isEmpty())
    }

    @Test
    fun `caller cannot select or reuse another player membership`() {
        val ticket = service.createTicket(
            playerId,
            UUID.randomUUID().toString(),
            OnlineMatchMode.CLASSIC,
        )
        val attacker = UUID.randomUUID().toString()

        assertThrows(OnlineMembershipRejectedException::class.java) {
            service.readTicket(attacker, ticket.ticketId)
        }
        assertThrows(OnlineMembershipRejectedException::class.java) {
            service.readSession(attacker, requireNotNull(ticket.sessionId))
        }
    }

    @Test
    fun `secret and turn commands are idempotent and return authoritative bot progress`() {
        val sessionId = service.createTicket(
            playerId,
            UUID.randomUUID().toString(),
            OnlineMatchMode.CLASSIC,
        ).sessionId!!
        val secretCommand = UUID.randomUUID().toString()
        val active = service.submitSecret(playerId, sessionId, secretCommand, 0, "1234")
        val replayedSecret = service.submitSecret(playerId, sessionId, secretCommand, 0, "1234")

        assertEquals(active, replayedSecret)
        assertEquals("active", active.phase)
        assertEquals("player", active.currentTurn)
        assertEquals(1, active.revision)
        assertTrue(active.participants.all { it.secretConfigured })

        val turn = service.submitGuess(
            playerId = playerId,
            sessionId = sessionId,
            commandId = UUID.randomUUID().toString(),
            expectedRevision = active.revision,
            guess = "0123",
        )

        assertTrue(turn.revision >= 2)
        assertFalse(turn.attempts.isEmpty())
        assertEquals("player", turn.attempts.first().actor)
        assertTrue(turn.attempts.none { it.exactMatches !in 0..turn.codeLength })
    }

    @Test
    fun `stale revisions and changed replay payloads fail closed`() {
        val sessionId = service.createTicket(
            playerId,
            UUID.randomUUID().toString(),
            OnlineMatchMode.CLASSIC,
        ).sessionId!!
        val commandId = UUID.randomUUID().toString()
        service.submitSecret(playerId, sessionId, commandId, 0, "1234")

        assertThrows(OnlineRevisionConflictException::class.java) {
            service.submitGuess(
                playerId,
                sessionId,
                UUID.randomUUID().toString(),
                expectedRevision = 0,
                guess = "0123",
            )
        }
        assertThrows(OnlineCommandIdReusedException::class.java) {
            service.submitSecret(playerId, sessionId, commandId, 0, "5678")
        }
    }
}
