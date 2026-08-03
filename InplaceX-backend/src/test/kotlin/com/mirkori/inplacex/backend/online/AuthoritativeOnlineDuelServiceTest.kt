package com.mirkori.inplacex.backend.online

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeOnlineDuelServiceTest {
    private val playerId = UUID.randomUUID().toString()
    private val clock = MutableClock(Instant.parse("2026-07-27T12:00:00Z"))
    private val service = AuthoritativeOnlineDuelService(
        clock = clock,
        botFallbackDelay = Duration.ofSeconds(5),
    )

    @Test
    fun `ticket preserves selected rules when it becomes a server bot match`() {
        val commandId = UUID.randomUUID().toString()
        val rules = OnlineMatchRules(
            playStyle = OnlineFriendPlayStyle.RACE,
            codeLength = 6,
        )

        val created = service.createTicket(playerId, commandId, OnlineMatchMode.CLASSIC, rules)
        val replayWhileSearching = service.createTicket(
            playerId,
            commandId,
            OnlineMatchMode.CLASSIC,
            rules,
        )

        assertEquals(created, replayWhileSearching)
        assertEquals(MatchmakingStatus.SEARCHING, created.status)
        assertNull(created.sessionId)
        assertFalse(created.matchedWithBot)

        clock.advance(Duration.ofSeconds(4))
        assertEquals(MatchmakingStatus.SEARCHING, service.readTicket(playerId, created.ticketId).status)

        clock.advance(Duration.ofSeconds(1))
        val matched = service.readTicket(playerId, created.ticketId)
        val replayAfterPromotion = service.createTicket(
            playerId,
            commandId,
            OnlineMatchMode.CLASSIC,
            rules,
        )
        val snapshot = service.readSession(playerId, requireNotNull(matched.sessionId))

        assertEquals(matched, replayAfterPromotion)
        assertEquals(MatchmakingStatus.MATCHED, matched.status)
        assertTrue(matched.matchedWithBot)
        assertEquals("setup", snapshot.phase)
        assertEquals("race", snapshot.playStyle)
        assertEquals(6, snapshot.codeLength)
        assertNull(snapshot.attemptLimit)
        assertTrue(snapshot.allowDuplicates)
        assertEquals(3, snapshot.maxConsecutiveDuplicateDigits)
        assertEquals(0, snapshot.revision)
        assertTrue(snapshot.attempts.isEmpty())

        val active = service.submitSecret(
            playerId = playerId,
            sessionId = snapshot.sessionId,
            commandId = UUID.randomUUID().toString(),
            expectedRevision = snapshot.revision,
            secret = "111234",
        )
        val turn = service.submitGuess(
            playerId = playerId,
            sessionId = snapshot.sessionId,
            commandId = UUID.randomUUID().toString(),
            expectedRevision = active.revision,
            guess = "001001",
        )
        assertTrue(turn.attempts.any { it.actor == "player" })
    }

    @Test
    fun `two different waiting players are paired before bot timeout`() {
        val secondPlayer = UUID.randomUUID().toString()
        val firstTicket = service.createTicket(
            playerId,
            UUID.randomUUID().toString(),
            OnlineMatchMode.CLASSIC,
        )
        clock.advance(Duration.ofSeconds(2))
        val secondTicket = service.createTicket(
            secondPlayer,
            UUID.randomUUID().toString(),
            OnlineMatchMode.CLASSIC,
        )
        val updatedFirst = service.readTicket(playerId, firstTicket.ticketId)

        assertEquals(MatchmakingStatus.MATCHED, updatedFirst.status)
        assertEquals(MatchmakingStatus.MATCHED, secondTicket.status)
        assertEquals(updatedFirst.sessionId, secondTicket.sessionId)
        assertFalse(updatedFirst.matchedWithBot)
        assertFalse(secondTicket.matchedWithBot)

        val sessionId = requireNotNull(secondTicket.sessionId)
        val firstSetup = service.readSession(playerId, sessionId)
        val secondSetup = service.readSession(secondPlayer, sessionId)
        assertEquals("player", firstSetup.participants.first().actor)
        assertEquals("opponent", secondSetup.participants.first().actor)

        val secondSecret = service.submitSecret(
            secondPlayer,
            sessionId,
            UUID.randomUUID().toString(),
            0,
            "5678",
        )
        assertTrue(secondSecret.participants.first { it.actor == "player" }.secretConfigured)
        val firstSecret = service.submitSecret(
            playerId,
            sessionId,
            UUID.randomUUID().toString(),
            secondSecret.revision,
            "1234",
        )
        assertEquals("active", firstSecret.phase)
        assertEquals("player", firstSecret.currentTurn)

        val firstTurn = service.submitGuess(
            playerId,
            sessionId,
            UUID.randomUUID().toString(),
            firstSecret.revision,
            "0123",
        )
        val secondView = service.readSession(secondPlayer, sessionId)
        val reconnectedFirstView = service.readSession(playerId, sessionId)
        assertEquals("opponent", firstTurn.currentTurn)
        assertEquals("player", secondView.currentTurn)
        assertEquals("0123", firstTurn.attempts.single().ownGuess)
        assertEquals("0123", reconnectedFirstView.attempts.single().ownGuess)
        assertEquals("opponent", secondView.attempts.single().actor)
        assertNull(secondView.attempts.single().ownGuess)
    }

    @Test
    fun `matchmaking only pairs players with identical selected rules`() {
        val secondPlayer = UUID.randomUUID().toString()
        val first = service.createTicket(
            playerId = playerId,
            commandId = UUID.randomUUID().toString(),
            mode = OnlineMatchMode.CLASSIC,
            rules = OnlineMatchRules(OnlineFriendPlayStyle.RACE, 4),
        )
        val second = service.createTicket(
            playerId = secondPlayer,
            commandId = UUID.randomUUID().toString(),
            mode = OnlineMatchMode.CLASSIC,
            rules = OnlineMatchRules(OnlineFriendPlayStyle.RACE, 6),
        )

        assertEquals(MatchmakingStatus.SEARCHING, first.status)
        assertEquals(MatchmakingStatus.SEARCHING, second.status)
        assertNull(first.sessionId)
        assertNull(second.sessionId)
    }

    @Test
    fun `private invite pairs exactly two authenticated players without bot fallback`() {
        val secondPlayer = UUID.randomUUID().toString()
        val createCommand = UUID.randomUUID().toString()
        val created = service.createPrivateInvite(
            playerId,
            createCommand,
            OnlineFriendPlayStyle.TURN_BASED,
            4,
        )
        val replayed = service.createPrivateInvite(
            playerId,
            createCommand,
            OnlineFriendPlayStyle.TURN_BASED,
            4,
        )

        assertEquals(created, replayed)
        assertTrue(created.inviteCode.matches(Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8}")))
        assertEquals(PrivateInviteStatus.WAITING, created.status)
        assertNull(created.sessionId)

        val accepted = service.acceptPrivateInvite(
            secondPlayer,
            UUID.randomUUID().toString(),
            created.inviteCode,
        )
        val ownerView = service.readPrivateInvite(playerId, created.inviteCode)

        assertEquals(PrivateInviteStatus.MATCHED, accepted.status)
        assertEquals(accepted, ownerView)
        val sessionId = requireNotNull(accepted.sessionId)
        assertFalse(sessionId.isBlank())

        val secondSecret = service.submitSecret(
            secondPlayer,
            sessionId,
            UUID.randomUUID().toString(),
            0,
            "5678",
        )
        val active = service.submitSecret(
            playerId,
            sessionId,
            UUID.randomUUID().toString(),
            secondSecret.revision,
            "1234",
        )
        assertEquals("active", active.phase)
        assertEquals("player", active.currentTurn)
    }

    @Test
    fun `private invite expires and cannot be joined by its owner or a late guest`() {
        val invite = service.createPrivateInvite(
            playerId,
            UUID.randomUUID().toString(),
            OnlineFriendPlayStyle.TURN_BASED,
            4,
        )

        assertThrows(OnlineInviteUnavailableException::class.java) {
            service.acceptPrivateInvite(
                playerId,
                UUID.randomUUID().toString(),
                invite.inviteCode,
            )
        }

        clock.advance(Duration.ofMinutes(10))
        val expired = service.readPrivateInvite(playerId, invite.inviteCode)
        assertEquals(PrivateInviteStatus.EXPIRED, expired.status)
        assertThrows(OnlineInviteUnavailableException::class.java) {
            service.acceptPrivateInvite(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                invite.inviteCode,
            )
        }
    }

    @Test
    fun `same player cannot match their own second ticket`() {
        val first = service.createTicket(
            playerId,
            UUID.randomUUID().toString(),
            OnlineMatchMode.CLASSIC,
        )
        val second = service.createTicket(
            playerId,
            UUID.randomUUID().toString(),
            OnlineMatchMode.CLASSIC,
        )

        assertEquals(MatchmakingStatus.SEARCHING, first.status)
        assertEquals(MatchmakingStatus.SEARCHING, second.status)
        assertNotEquals(first.ticketId, second.ticketId)
    }

    @Test
    fun `friend race uses creator length allows repeats and accepts simultaneous guesses`() {
        val secondPlayer = UUID.randomUUID().toString()
        val invite = service.createPrivateInvite(
            playerId,
            UUID.randomUUID().toString(),
            OnlineFriendPlayStyle.RACE,
            6,
        )
        assertEquals(6, invite.codeLength)
        assertTrue(invite.allowDuplicates)
        assertEquals(3, invite.maxConsecutiveDuplicateDigits)

        val accepted = service.acceptPrivateInvite(
            secondPlayer,
            UUID.randomUUID().toString(),
            invite.inviteCode,
        )
        val sessionId = requireNotNull(accepted.sessionId)
        val secondReady = service.submitSecret(
            secondPlayer,
            sessionId,
            UUID.randomUUID().toString(),
            0,
            "111234",
        )
        val active = service.submitSecret(
            playerId,
            sessionId,
            UUID.randomUUID().toString(),
            secondReady.revision,
            "998877",
        )

        assertEquals("race", active.playStyle)
        assertNull(active.currentTurn)
        assertNull(active.attemptLimit)
        assertTrue(active.deadlineAtEpochMs != null)

        val guestGuess = service.submitGuess(
            secondPlayer,
            sessionId,
            UUID.randomUUID().toString(),
            active.revision,
            "001001",
        )
        val solved = service.submitGuess(
            playerId,
            sessionId,
            UUID.randomUUID().toString(),
            active.revision,
            "111234",
        )
        assertEquals("finished", solved.phase)
        assertEquals("player", solved.winner)
        assertEquals("solved", solved.finishReason)
    }

    @Test
    fun `friend duel has no move limit and server clock ends the match`() {
        val secondPlayer = UUID.randomUUID().toString()
        val invite = service.createPrivateInvite(
            playerId,
            UUID.randomUUID().toString(),
            OnlineFriendPlayStyle.TURN_BASED,
            4,
        )
        val accepted = service.acceptPrivateInvite(
            secondPlayer,
            UUID.randomUUID().toString(),
            invite.inviteCode,
        )
        val sessionId = requireNotNull(accepted.sessionId)
        var snapshot = service.submitSecret(
            secondPlayer,
            sessionId,
            UUID.randomUUID().toString(),
            0,
            "5678",
        )
        snapshot = service.submitSecret(
            playerId,
            sessionId,
            UUID.randomUUID().toString(),
            snapshot.revision,
            "1234",
        )
        repeat(20) { index ->
            val actor = if (index % 2 == 0) playerId else secondPlayer
            snapshot = service.submitGuess(
                actor,
                sessionId,
                UUID.randomUUID().toString(),
                snapshot.revision,
                "0001",
            )
        }
        assertEquals("active", snapshot.phase)
        assertNull(snapshot.attemptLimit)

        clock.advance(Duration.ofMinutes(10))
        val timedOut = service.readSession(playerId, sessionId)
        assertEquals("finished", timedOut.phase)
        assertNull(timedOut.winner)
        assertEquals("time_expired", timedOut.finishReason)
        assertTrue(timedOut.revision > snapshot.revision)
    }

    @Test
    fun `friend secret rejects four equal digits in a row`() {
        val secondPlayer = UUID.randomUUID().toString()
        val invite = service.createPrivateInvite(
            playerId,
            UUID.randomUUID().toString(),
            OnlineFriendPlayStyle.RACE,
            6,
        )
        val accepted = service.acceptPrivateInvite(
            secondPlayer,
            UUID.randomUUID().toString(),
            invite.inviteCode,
        )

        assertThrows(
            com.mirkori.inplacex.backend.domain.duel.DuelCommandRejectedException::class.java,
        ) {
            service.submitSecret(
                playerId,
                requireNotNull(accepted.sessionId),
                UUID.randomUUID().toString(),
                0,
                "111123",
            )
        }
    }

    @Test
    fun `caller cannot select or reuse another player membership`() {
        val ticket = matchedBotTicket()
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
        val sessionId = requireNotNull(matchedBotTicket().sessionId)
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
        assertEquals("0123", turn.attempts.first().ownGuess)
        assertTrue(turn.attempts.filter { it.actor == "opponent" }.all { it.ownGuess == null })
        assertTrue(turn.attempts.none { it.exactMatches !in 0..turn.codeLength })
    }

    @Test
    fun `stale revisions and changed replay payloads fail closed`() {
        val sessionId = requireNotNull(matchedBotTicket().sessionId)
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

    private fun matchedBotTicket(): MatchmakingTicket {
        val ticket = service.createTicket(
            playerId,
            UUID.randomUUID().toString(),
            OnlineMatchMode.CLASSIC,
        )
        clock.advance(Duration.ofSeconds(5))
        return service.readTicket(playerId, ticket.ticketId)
    }
}

private class MutableClock(
    private var current: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

    override fun instant(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
