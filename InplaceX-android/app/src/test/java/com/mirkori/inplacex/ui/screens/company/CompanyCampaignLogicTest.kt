package com.mirkori.inplacex.ui.screens.company

import com.mirkori.inplacex.data.local.CampaignLevelProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanyCampaignLogicTest {

    @Test
    fun `campaign list is ascending and preserves recorded ratings`() {
        val items = buildCampaignLevelItems(
            campaignProgress = listOf(
                CampaignLevelProgress(levelNumber = 3, bestBackendRating = 8),
                CampaignLevelProgress(levelNumber = 1, bestBackendRating = 4),
            ),
            visibleTopLevel = 4,
        )

        assertEquals(listOf(1, 2, 3, 4), items.map { it.definition.levelNumber })
        assertEquals(4, items[0].progress.bestBackendRating)
        assertEquals(0, items[1].progress.bestBackendRating)
        assertEquals(8, items[2].progress.bestBackendRating)
    }

    @Test
    fun `stars follow backend rating thresholds`() {
        assertEquals(0, starsForRating(0))
        assertEquals(1, starsForRating(1))
        assertEquals(2, starsForRating(4))
        assertEquals(3, starsForRating(8))
        assertEquals("★★★", starsLabel(3))
    }

    @Test
    fun `next chapter stays locked until completion and star thresholds pass`() {
        assertEquals(1, computeUnlockedBlock(completedLevelsCount = 9, totalStars = 30))
        assertEquals(1, computeUnlockedBlock(completedLevelsCount = 10, totalStars = 14))
        assertEquals(2, computeUnlockedBlock(completedLevelsCount = 10, totalStars = 15))
    }
}
