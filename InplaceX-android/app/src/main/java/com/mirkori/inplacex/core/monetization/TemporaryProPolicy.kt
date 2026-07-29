package com.mirkori.inplacex.core.monetization

import java.util.Locale

object TemporaryProPolicy {
    const val PRICE_COINS = 60
    const val DURATION_MS = 60 * 60 * 1_000L

    fun isActive(expiresAtMs: Long, nowMs: Long): Boolean = expiresAtMs > nowMs

    fun extendExpiration(
        currentExpiresAtMs: Long,
        nowMs: Long,
        durationMs: Long = DURATION_MS,
    ): Long {
        require(durationMs > 0L) { "durationMs must be > 0" }
        val baseMs = maxOf(currentExpiresAtMs, nowMs)
        return if (Long.MAX_VALUE - baseMs < durationMs) Long.MAX_VALUE else baseMs + durationMs
    }

    fun remainingSeconds(expiresAtMs: Long, nowMs: Long): Long {
        val remainingMs = (expiresAtMs - nowMs).coerceAtLeast(0L)
        return (remainingMs + 999L) / 1_000L
    }

    fun formatRemaining(expiresAtMs: Long, nowMs: Long): String {
        val totalSeconds = remainingSeconds(expiresAtMs, nowMs)
        val hours = totalSeconds / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
