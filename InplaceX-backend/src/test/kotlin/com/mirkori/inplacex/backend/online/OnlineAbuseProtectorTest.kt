package com.mirkori.inplacex.backend.online

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineAbuseProtectorTest {
    @Test
    fun `WebSocket caps release exactly once`() {
        val protector = OnlineAbuseProtector(
            maximumConcurrentWebSocketsPerPrincipal = 1,
            maximumConcurrentWebSockets = 2,
        )

        val first = requireNotNull(protector.openWebSocket("player-a"))
        assertNull(protector.openWebSocket("player-a"))
        requireNotNull(protector.openWebSocket("player-b"))
        assertNull(protector.openWebSocket("player-c"))

        first.close()
        first.close()
        requireNotNull(protector.openWebSocket("player-a"))
    }

    @Test
    fun `expired operation window permits a new request`() {
        val clock = MutableAbuseClock(Instant.parse("2026-08-07T00:00:00Z"))
        val protector = OnlineAbuseProtector(
            clock = clock,
            windowMillis = 1_000,
            operationLimits = OnlineOperation.entries.associateWith { 1 },
        )

        assertSame(
            OnlineAbuseDecision.Allowed,
            protector.acquire("player-a", OnlineOperation.SubmitTurn),
        )
        assertTrue(
            protector.acquire("player-a", OnlineOperation.SubmitTurn) is OnlineAbuseDecision.Rejected,
        )
        clock.current = clock.current.plusMillis(1_000)
        assertSame(
            OnlineAbuseDecision.Allowed,
            protector.acquire("player-a", OnlineOperation.SubmitTurn),
        )
    }

    @Test
    fun `authentication pre-check is non-consuming and sees both failed-auth budgets`() {
        val attemptProtector = OnlineAbuseProtector(
            authenticationAttemptLimit = 1,
            invalidAuthenticationLimit = 2,
        )

        assertSame(
            OnlineAbuseDecision.Allowed,
            attemptProtector.checkAuthenticationFailureBudget("198.51.100.10"),
        )
        assertSame(
            OnlineAbuseDecision.Allowed,
            attemptProtector.checkAuthenticationFailureBudget("198.51.100.10"),
        )
        assertSame(
            OnlineAbuseDecision.Allowed,
            attemptProtector.acquireAuthenticationAttempt("198.51.100.10"),
        )
        assertTrue(
            attemptProtector.checkAuthenticationFailureBudget("198.51.100.10") is
                OnlineAbuseDecision.Rejected,
        )

        val invalidProtector = OnlineAbuseProtector(
            authenticationAttemptLimit = 2,
            invalidAuthenticationLimit = 1,
        )
        assertSame(
            OnlineAbuseDecision.Allowed,
            invalidProtector.checkAuthenticationFailureBudget("198.51.100.11"),
        )
        assertSame(
            OnlineAbuseDecision.Allowed,
            invalidProtector.acquireInvalidAuthentication("198.51.100.11"),
        )
        assertTrue(
            invalidProtector.checkAuthenticationFailureBudget("198.51.100.11") is
                OnlineAbuseDecision.Rejected,
        )
    }
}

private class MutableAbuseClock(
    var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current
}
