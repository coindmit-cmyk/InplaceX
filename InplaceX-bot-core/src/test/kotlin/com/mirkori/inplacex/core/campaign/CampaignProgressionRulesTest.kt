package com.mirkori.inplacex.core.campaign

import org.junit.Assert.assertEquals
import org.junit.Test

class CampaignProgressionRulesTest {
    @Test
    fun `default chapter unlock requires every level and two stars per level`() {
        assertEquals(10, CampaignProgressionRules.unlockConfig.levelsPerChapter)
        assertEquals(20, CampaignProgressionRules.requiredStarsForNextBlock(2))
        assertEquals(40, CampaignProgressionRules.requiredStarsForNextBlock(3))

        assertEquals(1, CampaignProgressionRules.computeUnlockedBlock(stars(9, 3)))
        assertEquals(1, CampaignProgressionRules.computeUnlockedBlock(stars(9, 2) + (10 to 1)))
        assertEquals(2, CampaignProgressionRules.computeUnlockedBlock(stars(10, 2)))
        assertEquals(2, CampaignProgressionRules.computeUnlockedBlock(stars(19, 2) + (20 to 1)))
        assertEquals(3, CampaignProgressionRules.computeUnlockedBlock(stars(20, 2)))
    }

    @Test
    fun `unlock coefficient is configurable without changing chapter math`() {
        val config = CampaignUnlockConfig(
            levelsPerChapter = 10,
            requiredStarsPerLevel = 1.5,
        )

        assertEquals(15, CampaignProgressionRules.requiredStarsForNextBlock(2, config))
        assertEquals(30, CampaignProgressionRules.requiredStarsForNextBlock(3, config))
        val chapterStars = stars(5, 2) + (6..10).associateWith { 1 }
        assertEquals(2, CampaignProgressionRules.computeUnlockedBlock(chapterStars, config))
    }

    @Test
    fun `stars from later chapters cannot repair an earlier chapter gate`() {
        val legacyProgress = stars(5, 2) +
            (6..10).associateWith { 1 } +
            (11..15).associateWith { 1 }

        assertEquals(15, CampaignProgressionRules.earnedStarsForNextBlock(2, legacyProgress))
        assertEquals(1, CampaignProgressionRules.computeUnlockedBlock(legacyProgress))
    }

    @Test
    fun `chapter ranges and level ownership share the same configured size`() {
        assertEquals(1..10, CampaignProgressionRules.levelRange(1))
        assertEquals(11..20, CampaignProgressionRules.levelRange(2))
        assertEquals(1, CampaignProgressionRules.chapterForLevel(10))
        assertEquals(2, CampaignProgressionRules.chapterForLevel(11))
    }

    private fun stars(levels: Int, stars: Int): Map<Int, Int> =
        (1..levels).associateWith { stars }
}
