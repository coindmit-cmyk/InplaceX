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
    fun `authentication attempts are bounded before expensive verification`() {
        val protector = OnlineAbuseProtector(authenticationAttemptLimit = 1)

        assertSame(OnlineAbuseDecision.Allowed, protector.acquireAuthenticationAttempt("198.51.100.10"))
        assertTrue(protector.acquireAuthenticationAttempt("198.51.100.10") is OnlineAbuseDecision.Rejected)
        assertSame(OnlineAbuseDecision.Allowed, protector.acquireAuthenticationAttempt("198.51.100.11"))
    }
}

private class MutableAbuseClock(
    var current: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneId.of("UTC")

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current
}
