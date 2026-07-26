package com.mirkori.inplacex.core.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignLevelGeneratorTest {

    @Test
    fun firstBlockStaysForgivingForOnboarding() {
        val firstLevel = CampaignLevelGenerator.generate(1)
        val firstHardcore = CampaignLevelGenerator.generate(10)

        assertEquals(CampaignDifficultyTier.EASY, firstLevel.difficultyTier)
        assertEquals(4, firstLevel.config.codeLength)
        assertEquals(19, firstLevel.config.attemptLimit)
        assertEquals(CampaignBlockRole.HARDCORE, firstHardcore.blockRole)
        assertEquals(4, firstHardcore.config.codeLength)
        assertTrue(firstHardcore.config.attemptLimit < firstLevel.config.attemptLimit)
    }

    @Test
    fun levelSeventeenIsMeaningfullyHarderThanLevelOne() {
        val firstLevel = CampaignLevelGenerator.generate(1)
        val levelSeventeen = CampaignLevelGenerator.generate(17)

        assertEquals(CampaignDifficultyTier.MEDIUM, levelSeventeen.difficultyTier)
        assertEquals(5, levelSeventeen.config.codeLength)
        assertEquals(19, levelSeventeen.config.attemptLimit)
        assertEquals(400, levelSeventeen.raceTimeLimitSeconds)
        assertTrue(levelSeventeen.config.codeLength > firstLevel.config.codeLength)
        assertTrue(levelSeventeen.raceTimeLimitSeconds < firstLevel.raceTimeLimitSeconds)
        assertEquals(8, levelSeventeen.ratingPolicy.assistsBudget.perfectHintsBudget)
        assertEquals(2, levelSeventeen.ratingPolicy.assistsBudget.perfectBoostsBudget)
    }

    @Test
    fun everyDifficultyTierAndCodeLengthBandHasAStableBoundary() {
        assertLevel(level = 10, tier = CampaignDifficultyTier.EASY, codeLength = 4)
        assertLevel(level = 11, tier = CampaignDifficultyTier.MEDIUM, codeLength = 5)
        assertLevel(level = 40, tier = CampaignDifficultyTier.MEDIUM, codeLength = 5)
        assertLevel(level = 41, tier = CampaignDifficultyTier.MEDIUM, codeLength = 6)
        assertLevel(level = 80, tier = CampaignDifficultyTier.MEDIUM, codeLength = 6)
        assertLevel(level = 81, tier = CampaignDifficultyTier.HARD, codeLength = 6)
        assertLevel(level = 90, tier = CampaignDifficultyTier.HARD, codeLength = 6)
        assertLevel(level = 91, tier = CampaignDifficultyTier.HARD, codeLength = 7)
        assertLevel(level = 150, tier = CampaignDifficultyTier.HARD, codeLength = 7)
        assertLevel(level = 151, tier = CampaignDifficultyTier.HARD, codeLength = 8)
        assertLevel(level = 220, tier = CampaignDifficultyTier.HARD, codeLength = 8)
        assertLevel(level = 221, tier = CampaignDifficultyTier.HARDCORE, codeLength = 9)
        assertLevel(level = 300, tier = CampaignDifficultyTier.HARDCORE, codeLength = 9)
        assertLevel(level = 301, tier = CampaignDifficultyTier.HARDCORE, codeLength = 10)
        assertLevel(level = 500, tier = CampaignDifficultyTier.HARDCORE, codeLength = 10)
    }

    @Test
    fun tenLevelBlocksKeepTheirDifficultyWaves() {
        for (blockStart in listOf(1, 11, 101, 301)) {
            assertEquals(
                CampaignBlockRole.STANDARD,
                CampaignLevelGenerator.generate(blockStart).blockRole,
            )
            assertEquals(
                CampaignBlockRole.SPIKE,
                CampaignLevelGenerator.generate(blockStart + 4).blockRole,
            )
            assertEquals(
                CampaignBlockRole.HARDCORE,
                CampaignLevelGenerator.generate(blockStart + 9).blockRole,
            )
        }
    }

    private fun assertLevel(
        level: Int,
        tier: CampaignDifficultyTier,
        codeLength: Int,
    ) {
        val definition = CampaignLevelGenerator.generate(level)

        assertEquals(tier, definition.difficultyTier)
        assertEquals(codeLength, definition.config.codeLength)
    }
}
