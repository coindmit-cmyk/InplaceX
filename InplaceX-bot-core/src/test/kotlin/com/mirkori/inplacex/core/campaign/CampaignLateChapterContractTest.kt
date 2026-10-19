package com.mirkori.inplacex.core.campaign

import org.junit.Assert.assertEquals
import org.junit.Test

class CampaignLateChapterContractTest {
    private val unlockConfig = CampaignUnlockConfig(requiredStarsPerLevel = 1.75)

    @Test
    fun lateChapterRequiresConfiguredStarThreshold() {
        val stars87 = mapOf(
            1 to 2, 2 to 2, 3 to 2, 4 to 2, 5 to 2, 6 to 2, 7 to 2, 8 to 2, 9 to 2, 10 to 2,
            11 to 2, 12 to 2, 13 to 2, 14 to 2, 15 to 2, 16 to 2, 17 to 2, 18 to 2, 19 to 2, 20 to 2,
            21 to 2, 22 to 2, 23 to 2, 24 to 2, 25 to 2, 26 to 2, 27 to 2, 28 to 2, 29 to 2, 30 to 2,
            31 to 2, 32 to 2, 33 to 2, 34 to 2, 35 to 2, 36 to 2, 37 to 2,
            38 to 1, 39 to 1, 40 to 1, 41 to 1, 42 to 1, 43 to 1, 44 to 1, 45 to 1, 46 to 1, 47 to 1,
            48 to 1, 49 to 1, 50 to 1,
        )
        val stars88 = mapOf(
            1 to 2, 2 to 2, 3 to 2, 4 to 2, 5 to 2, 6 to 2, 7 to 2, 8 to 2, 9 to 2, 10 to 2,
            11 to 2, 12 to 2, 13 to 2, 14 to 2, 15 to 2, 16 to 2, 17 to 2, 18 to 2, 19 to 2, 20 to 2,
            21 to 2, 22 to 2, 23 to 2, 24 to 2, 25 to 2, 26 to 2, 27 to 2, 28 to 2, 29 to 2, 30 to 2,
            31 to 2, 32 to 2, 33 to 2, 34 to 2, 35 to 2, 36 to 2, 37 to 2, 38 to 2,
            39 to 1, 40 to 1, 41 to 1, 42 to 1, 43 to 1, 44 to 1, 45 to 1, 46 to 1, 47 to 1, 48 to 1,
            49 to 1, 50 to 1,
        )

        assertEquals(88, CampaignProgressionRules.requiredStarsForNextBlock(6, unlockConfig))
        assertEquals(5, CampaignProgressionRules.computeUnlockedBlock(stars87, unlockConfig))
        assertEquals(6, CampaignProgressionRules.computeUnlockedBlock(stars88, unlockConfig))
    }

    @Test
    fun starsCannotUnlockLateChapterBeforePriorLevelsAreComplete() {
        val stars147 = mapOf(
            1 to 3, 2 to 3, 3 to 3, 4 to 3, 5 to 3, 6 to 3, 7 to 3, 8 to 3, 9 to 3, 10 to 3,
            11 to 3, 12 to 3, 13 to 3, 14 to 3, 15 to 3, 16 to 3, 17 to 3, 18 to 3, 19 to 3, 20 to 3,
            21 to 3, 22 to 3, 23 to 3, 24 to 3, 25 to 3, 26 to 3, 27 to 3, 28 to 3, 29 to 3, 30 to 3,
            31 to 3, 32 to 3, 33 to 3, 34 to 3, 35 to 3, 36 to 3, 37 to 3, 38 to 3, 39 to 3, 40 to 3,
            41 to 3, 42 to 3, 43 to 3, 44 to 3, 45 to 3, 46 to 3, 47 to 3, 48 to 3, 49 to 3,
        )
        val stars150 = mapOf(
            1 to 3, 2 to 3, 3 to 3, 4 to 3, 5 to 3, 6 to 3, 7 to 3, 8 to 3, 9 to 3, 10 to 3,
            11 to 3, 12 to 3, 13 to 3, 14 to 3, 15 to 3, 16 to 3, 17 to 3, 18 to 3, 19 to 3, 20 to 3,
            21 to 3, 22 to 3, 23 to 3, 24 to 3, 25 to 3, 26 to 3, 27 to 3, 28 to 3, 29 to 3, 30 to 3,
            31 to 3, 32 to 3, 33 to 3, 34 to 3, 35 to 3, 36 to 3, 37 to 3, 38 to 3, 39 to 3, 40 to 3,
            41 to 3, 42 to 3, 43 to 3, 44 to 3, 45 to 3, 46 to 3, 47 to 3, 48 to 3, 49 to 3, 50 to 3,
        )

        assertEquals(5, CampaignProgressionRules.computeUnlockedBlock(stars147, unlockConfig))
        assertEquals(6, CampaignProgressionRules.computeUnlockedBlock(stars150, unlockConfig))
    }
}
