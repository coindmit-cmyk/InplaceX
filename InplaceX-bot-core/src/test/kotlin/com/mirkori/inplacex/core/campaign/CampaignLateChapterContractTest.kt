package com.mirkori.inplacex.core.campaign

import org.junit.Assert.assertEquals
import org.junit.Test

class CampaignLateChapterContractTest {
    @Test
    fun lateChapterRequiresConfiguredStarThreshold() {
        assertEquals(88, CampaignProgressionRules.requiredStarsForNextBlock(6, 50))
        assertEquals(5, CampaignProgressionRules.computeUnlockedBlock(50, 87))
        assertEquals(6, CampaignProgressionRules.computeUnlockedBlock(50, 88))
    }

    @Test
    fun starsCannotUnlockLateChapterBeforePriorLevelsAreComplete() {
        assertEquals(5, CampaignProgressionRules.computeUnlockedBlock(49, 147))
        assertEquals(6, CampaignProgressionRules.computeUnlockedBlock(50, 150))
    }
}
