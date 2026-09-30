package com.mirkori.inplacex.core.monetization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryProPolicyTest {
    @Test
    fun activationExpiresExactlyAtDeadline() {
        val expiresAtMs = NOW_MS + TemporaryProPolicy.DURATION_MS

        assertTrue(TemporaryProPolicy.isActive(expiresAtMs, NOW_MS))
        assertTrue(TemporaryProPolicy.isActive(expiresAtMs, expiresAtMs - 1L))
        assertFalse(TemporaryProPolicy.isActive(expiresAtMs, expiresAtMs))
    }

    @Test
    fun repeatedPurchaseExtendsExistingTime() {
        val firstExpiration = TemporaryProPolicy.extendExpiration(0L, NOW_MS)
        val secondExpiration = TemporaryProPolicy.extendExpiration(firstExpiration, NOW_MS + 15 * 60_000L)

        assertEquals(NOW_MS + 2 * TemporaryProPolicy.DURATION_MS, secondExpiration)
    }

    @Test
    fun expiredPurchaseStartsFromCurrentTimeAndFormatsCountdown() {
        val expiration = TemporaryProPolicy.extendExpiration(NOW_MS - 1L, NOW_MS)

        assertEquals(NOW_MS + TemporaryProPolicy.DURATION_MS, expiration)
        assertEquals("01:00:00", TemporaryProPolicy.formatRemaining(expiration, NOW_MS))
        assertEquals("00:00:01", TemporaryProPolicy.formatRemaining(expiration, expiration - 1L))
        assertEquals("00:00:00", TemporaryProPolicy.formatRemaining(expiration, expiration))
    }

    companion object {
        private const val NOW_MS = 1_725_000_000_000L
    }
}
