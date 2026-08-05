package com.mirkori.inplacex.core.campaign

import org.junit.Assert.assertEquals
import org.junit.Test

class CampaignProgressionRulesTest {
    @Test
    fun `default chapter unlock requires every level and two stars per level`() {
        assertEquals(10, CampaignProgressionRules.unlockConfig.levelsPerChapter)
        assertEquals(20, CampaignProgressionRules.requiredStarsForNextBlock(2))
        assertEquals(40, CampaignProgressionRules.requiredStarsForNextBlock(3))

        assertEquals(1, CampaignProgressionRules.computeUnlockedBlock(9, 30))
        assertEquals(1, CampaignProgressionRules.computeUnlockedBlock(10, 19))
        assertEquals(2, CampaignProgressionRules.computeUnlockedBlock(10, 20))
        assertEquals(2, CampaignProgressionRules.computeUnlockedBlock(20, 39))
        assertEquals(3, CampaignProgressionRules.computeUnlockedBlock(20, 40))
    }

    @Test
    fun `unlock coefficient is configurable without changing chapter math`() {
        val config = CampaignUnlockConfig(
            levelsPerChapter = 10,
            requiredStarsPerLevel = 1.5,
        )

        assertEquals(15, CampaignProgressionRules.requiredStarsForNextBlock(2, config))
        assertEquals(30, CampaignProgressionRules.requiredStarsForNextBlock(3, config))
        assertEquals(2, CampaignProgressionRules.computeUnlockedBlock(10, 15, config))
    }

    @Test
    fun `chapter ranges and level ownership share the same configured size`() {
        assertEquals(1..10, CampaignProgressionRules.levelRange(1))
        assertEquals(11..20, CampaignProgressionRules.levelRange(2))
        assertEquals(1, CampaignProgressionRules.chapterForLevel(10))
        assertEquals(2, CampaignProgressionRules.chapterForLevel(11))
    }
}
