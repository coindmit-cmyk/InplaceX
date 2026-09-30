package com.mirkori.inplacex.core.campaign

import org.junit.Assert.assertEquals
import org.junit.Test

class CampaignRatingCalculatorTest {

    @Test
    fun `loss never awards rating or stars`() {
        val level = CampaignLevelGenerator.generate(1)
        val rating = CampaignRatingCalculator.rate(
            level,
            CampaignPerformance(
                won = false,
                attemptsUsed = 1,
                elapsedSeconds = 1,
                hintUses = 0,
                boostUses = 0,
            ),
        )

        assertEquals(0, rating)
        assertEquals(0, CampaignRatingCalculator.starsForRating(rating))
    }

    @Test
    fun `final allowed attempt is always one star`() {
        listOf(1, 10, 81, 500).forEach { levelNumber ->
            val level = CampaignLevelGenerator.generate(levelNumber)
            val rating = CampaignRatingCalculator.rate(
                level,
                CampaignPerformance(
                    won = true,
                    attemptsUsed = level.config.attemptLimit,
                    elapsedSeconds = level.ratingPolicy.targetTimeSecondsForPerfect,
                    hintUses = 0,
                    boostUses = 0,
                ),
            )

            assertEquals("rating at level $levelNumber", 1, rating)
            assertEquals("stars at level $levelNumber", 1, CampaignRatingCalculator.starsForRating(rating))
        }
    }

    @Test
    fun `solver reference is defined for every campaign length`() {
        assertEquals(
            listOf(13, 14, 15, 17, 19, 21, 24),
            (4..10).map(CampaignSolverBudget::expertReferenceAttempts),
        )
    }
}
