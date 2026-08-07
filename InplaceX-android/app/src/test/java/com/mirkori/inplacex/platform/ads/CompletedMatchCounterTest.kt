package com.mirkori.inplacex.platform.ads

import com.mirkori.inplacex.data.local.ModeStats
import org.junit.Assert.assertEquals
import org.junit.Test

class CompletedMatchCounterTest {
    @Test
    fun `baseline includes only completed results across modes`() {
        assertEquals(
            21,
            completedMatchCountForAds(
                pve = ModeStats(wins = 4, losses = 3),
                pvp = ModeStats(wins = 5, losses = 2),
                company = ModeStats(wins = 6, losses = 1),
            ),
        )
    }

    @Test
    fun `corrupt negative counters are ignored and total is bounded`() {
        assertEquals(
            Int.MAX_VALUE,
            completedMatchCountForAds(
                pve = ModeStats(wins = Int.MAX_VALUE, losses = Int.MAX_VALUE),
                pvp = ModeStats(wins = -10, losses = -20),
                company = ModeStats(wins = 1, losses = 1),
            ),
        )
    }
}
