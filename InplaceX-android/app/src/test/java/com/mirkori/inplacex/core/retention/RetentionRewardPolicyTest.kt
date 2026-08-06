package com.mirkori.inplacex.core.retention

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionRewardPolicyTest {
    @Test
    fun dailyKeyUsesThePlayersLocalCalendarDate() {
        val nowMs = Instant.parse("2026-01-01T00:30:00Z").toEpochMilli()

        assertEquals(
            "2025-12-31",
            RetentionRewardPolicy.periodKey(
                RetentionRewardType.DAILY,
                nowMs,
                ZoneOffset.ofHours(-1),
            ),
        )
    }

    @Test
    fun weeklyKeyUsesIsoWeekBasedYearAtNewYear() {
        val nowMs = Instant.parse("2026-01-01T12:00:00Z").toEpochMilli()

        assertEquals(
            "2026-W01",
            RetentionRewardPolicy.periodKey(
                RetentionRewardType.WEEKLY,
                nowMs,
                ZoneOffset.UTC,
            ),
        )
    }

    @Test
    fun rewardAmountsStayExplicitAndConfigurable() {
        assertEquals(20, RetentionRewardPolicy.dailyGrant.coins)
        assertEquals(1, RetentionRewardPolicy.dailyGrant.checkDigitHints)
        assertEquals(100, RetentionRewardPolicy.weeklyGrant.coins)
        assertEquals(1, RetentionRewardPolicy.weeklyGrant.extraMovesBoosts)
        assertEquals(1, RetentionRewardPolicy.weeklyGrant.extraTimeBoosts)
    }
}
