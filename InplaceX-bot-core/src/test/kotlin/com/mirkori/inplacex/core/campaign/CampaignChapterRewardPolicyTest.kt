package com.mirkori.inplacex.core.campaign

import org.junit.Assert.assertEquals
import org.junit.Test

class CampaignChapterRewardPolicyTest {
    @Test
    fun `chapter reward uses ten level boundaries and existing inventory types`() {
        assertEquals(1..10, CampaignChapterRewardPolicy.levelRange(1))
        assertEquals(11..20, CampaignChapterRewardPolicy.levelRange(2))
        assertEquals(
            CampaignChapterReward(
                coins = 50,
                openPositionHints = 1,
                checkDigitHints = 1,
                checkPositionHints = 1,
            ),
            CampaignChapterRewardPolicy.rewardFor(1),
        )
    }
}
