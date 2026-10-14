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

    @Test
    fun `target attempts retain maximum rating and three stars`() {
        val level = explicitRatingFixture()

        listOf(9, 10).forEach { attempts ->
            val rating = CampaignRatingCalculator.rate(
                level,
                CampaignPerformance(
                    won = true,
                    attemptsUsed = attempts,
                    elapsedSeconds = 60,
                    hintUses = 0,
                    boostUses = 0,
                ),
            )

            assertEquals("rating at attempt $attempts", 10, rating)
            assertEquals("stars at attempt $attempts", 3, CampaignRatingCalculator.starsForRating(rating))
        }
    }

    @Test
    fun `halfway through attempt reserve earns two stars`() {
        val rating = CampaignRatingCalculator.rate(
            explicitRatingFixture(),
            CampaignPerformance(
                won = true,
                attemptsUsed = 15,
                elapsedSeconds = 60,
                hintUses = 0,
                boostUses = 0,
            ),
        )

        assertEquals(2, CampaignRatingCalculator.starsForRating(rating))
    }

    @Test
    fun `Easy unlimited assists do not reduce timely target rating`() {
        val level = explicitRatingFixture(CampaignDifficultyTier.EASY)
        val noHelpRating = CampaignRatingCalculator.rate(
            level,
            CampaignPerformance(true, 10, 60, 0, 0),
        )
        val assistedRating = CampaignRatingCalculator.rate(
            level,
            CampaignPerformance(true, 10, 60, 100, 100),
        )

        assertEquals(10, noHelpRating)
        assertEquals(3, CampaignRatingCalculator.starsForRating(noHelpRating))
        assertEquals(10, assistedRating)
        assertEquals(3, CampaignRatingCalculator.starsForRating(assistedRating))
    }

    private fun explicitRatingFixture(
        difficulty: CampaignDifficultyTier = CampaignDifficultyTier.HARD,
    ): CampaignLevelDefinition {
        val generated = CampaignLevelGenerator.generate(1)
        return generated.copy(
            difficultyTier = difficulty,
            config = generated.config.copy(attemptLimit = 20),
            ratingPolicy = CampaignRatingPolicy(
                targetAttemptsForPerfect = 10,
                targetTimeSecondsForPerfect = 60,
                assistsBudget = CampaignAssistBudget(
                    perfectHintsBudget = null,
                    perfectBoostsBudget = null,
                ),
            ),
        )
    }

    @Test
    fun `time beyond target reduces rating within winning range`() {
        val level = explicitRatingFixture()
        val beforeTargetRating = CampaignRatingCalculator.rate(
            level,
            CampaignPerformance(true, 10, 59, 0, 0),
        )
        val atTargetRating = CampaignRatingCalculator.rate(
            level,
            CampaignPerformance(true, 10, 60, 0, 0),
        )
        val delayedRating = CampaignRatingCalculator.rate(
            level,
            CampaignPerformance(true, 10, 120, 0, 0),
        )

        assertEquals(10, beforeTargetRating)
        assertEquals(3, CampaignRatingCalculator.starsForRating(beforeTargetRating))
        assertEquals(10, atTargetRating)
        assertEquals(3, CampaignRatingCalculator.starsForRating(atTargetRating))
        org.junit.Assert.assertTrue(delayedRating < 10)
        org.junit.Assert.assertTrue(delayedRating in 1..10)
    }

    @Test
    fun `finite assist budgets preserve target rating and independently bound over-budget help`() {
        val level = explicitRatingFixture().let { fixture ->
            fixture.copy(
                ratingPolicy = fixture.ratingPolicy.copy(
                    assistsBudget = CampaignAssistBudget(
                        perfectHintsBudget = 2,
                        perfectBoostsBudget = 1,
                    ),
                ),
            )
        }
        val withinBudgetRating = CampaignRatingCalculator.rate(
            level,
            CampaignPerformance(true, 10, 60, 2, 1),
        )
        val excessHintsRating = CampaignRatingCalculator.rate(
            level,
            CampaignPerformance(true, 10, 60, 100, 1),
        )
        val excessBoostsRating = CampaignRatingCalculator.rate(
            level,
            CampaignPerformance(true, 10, 60, 2, 100),
        )

        assertEquals(10, withinBudgetRating)
        assertEquals(3, CampaignRatingCalculator.starsForRating(withinBudgetRating))
        org.junit.Assert.assertTrue(excessHintsRating < 10)
        org.junit.Assert.assertTrue(excessHintsRating in 1..10)
        org.junit.Assert.assertTrue(excessBoostsRating < 10)
        org.junit.Assert.assertTrue(excessBoostsRating in 1..10)
    }
}
