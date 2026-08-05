package com.mirkori.inplacex.ui.screens.company

import com.mirkori.inplacex.core.campaign.CampaignLevelGenerator
import com.mirkori.inplacex.core.campaign.CampaignProgressionRules
import com.mirkori.inplacex.data.local.CampaignLevelProgress
import com.mirkori.inplacex.ui.screens.game.MatchSessionSummary
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
    fun `chapter screen contains exactly its ten levels`() {
        val items = buildCampaignLevelItems(
            campaignProgress = emptyList(),
            visibleTopLevel = 20,
        )

        assertEquals((1..10).toList(), campaignLevelItemsForChapter(items, 1).map {
            it.definition.levelNumber
        })
        assertEquals((11..20).toList(), campaignLevelItemsForChapter(items, 2).map {
            it.definition.levelNumber
        })
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
    fun `early efficient win earns three stars`() {
        val level = CampaignLevelGenerator.generate(1)
        val rating = rateCampaignMatch(
            level,
            summary(
                attemptsUsed = level.ratingPolicy.targetAttemptsForPerfect,
                elapsedSeconds = level.ratingPolicy.targetTimeSecondsForPerfect,
            ),
        )

        assertEquals(10, rating)
        assertEquals(3, starsForRating(rating))
    }

    @Test
    fun `spending about half of the reserve earns two stars`() {
        val level = CampaignLevelGenerator.generate(1)
        val reserve = level.config.attemptLimit - level.ratingPolicy.targetAttemptsForPerfect
        val rating = rateCampaignMatch(
            level,
            summary(
                attemptsUsed = level.ratingPolicy.targetAttemptsForPerfect + reserve / 2,
                elapsedSeconds = level.ratingPolicy.targetTimeSecondsForPerfect,
            ),
        )

        assertEquals(2, starsForRating(rating))
    }

    @Test
    fun `winning on the final allowed attempt earns one star`() {
        for (levelNumber in listOf(1, 10, 81, 500)) {
            val level = CampaignLevelGenerator.generate(levelNumber)
            val rating = rateCampaignMatch(
                level,
                summary(
                    attemptsUsed = level.config.attemptLimit,
                    elapsedSeconds = level.ratingPolicy.targetTimeSecondsForPerfect,
                ),
            )

            assertEquals("rating at level $levelNumber", 1, rating)
            assertEquals("stars at level $levelNumber", 1, starsForRating(rating))
        }
    }

    @Test
    fun `next chapter stays locked until completion and star thresholds pass`() {
        assertEquals(1, computeUnlockedBlock((1..9).associateWith { 3 }))
        assertEquals(1, computeUnlockedBlock((1..9).associateWith { 2 } + (10 to 1)))
        assertEquals(2, computeUnlockedBlock((1..10).associateWith { 2 }))
        assertEquals(20, CampaignProgressionRules.requiredStarsForNextBlock(2))
    }

    private fun summary(
        attemptsUsed: Int,
        elapsedSeconds: Int,
    ) = MatchSessionSummary(
        won = true,
        attemptsUsed = attemptsUsed,
        elapsedSeconds = elapsedSeconds,
        hintUses = 0,
        boostUses = 0,
        openPositionHintUses = 0,
        checkDigitHintUses = 0,
        checkPositionHintUses = 0,
        extraMovesBoostUses = 0,
        extraTimeBoostUses = 0,
    )
}
