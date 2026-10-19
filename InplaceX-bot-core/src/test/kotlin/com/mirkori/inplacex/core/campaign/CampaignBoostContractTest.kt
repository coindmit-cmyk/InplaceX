package com.mirkori.inplacex.core.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignBoostContractTest {

    @Test
    fun `boost packs match documented values for every tier and block role`() {
        val fixtures = listOf(
            BoostFixture(1, CampaignDifficultyTier.EASY, CampaignBlockRole.STANDARD, 3, 120),
            BoostFixture(5, CampaignDifficultyTier.EASY, CampaignBlockRole.SPIKE, 3, 120),
            BoostFixture(10, CampaignDifficultyTier.EASY, CampaignBlockRole.HARDCORE, 3, 120),
            BoostFixture(11, CampaignDifficultyTier.MEDIUM, CampaignBlockRole.STANDARD, 2, 60),
            BoostFixture(15, CampaignDifficultyTier.MEDIUM, CampaignBlockRole.SPIKE, 2, 60),
            BoostFixture(20, CampaignDifficultyTier.MEDIUM, CampaignBlockRole.HARDCORE, 2, 60),
            BoostFixture(81, CampaignDifficultyTier.HARD, CampaignBlockRole.STANDARD, 2, 60),
            BoostFixture(85, CampaignDifficultyTier.HARD, CampaignBlockRole.SPIKE, 2, 60),
            BoostFixture(90, CampaignDifficultyTier.HARD, CampaignBlockRole.HARDCORE, 2, 60),
            BoostFixture(221, CampaignDifficultyTier.HARDCORE, CampaignBlockRole.STANDARD, 1, 30),
            BoostFixture(225, CampaignDifficultyTier.HARDCORE, CampaignBlockRole.SPIKE, 1, 30),
            BoostFixture(230, CampaignDifficultyTier.HARDCORE, CampaignBlockRole.HARDCORE, 1, 30),
        )

        fixtures.forEach { fixture ->
            val level = CampaignLevelGenerator.generate(fixture.levelNumber)
            assertEquals("level ${fixture.levelNumber} tier", fixture.tier, level.difficultyTier)
            assertEquals("level ${fixture.levelNumber} moves", fixture.extraMoves, level.boostPack.extraMoves)
            assertEquals("level ${fixture.levelNumber} seconds", fixture.extraSeconds, level.boostPack.extraSeconds)
        }
    }

    @Test
    fun `hints and auto mode are available for every documented tier and block role`() {
        val fixtures = listOf(
            AvailabilityFixture(1, CampaignDifficultyTier.EASY, CampaignBlockRole.STANDARD),
            AvailabilityFixture(5, CampaignDifficultyTier.EASY, CampaignBlockRole.SPIKE),
            AvailabilityFixture(10, CampaignDifficultyTier.EASY, CampaignBlockRole.HARDCORE),
            AvailabilityFixture(11, CampaignDifficultyTier.MEDIUM, CampaignBlockRole.STANDARD),
            AvailabilityFixture(15, CampaignDifficultyTier.MEDIUM, CampaignBlockRole.SPIKE),
            AvailabilityFixture(20, CampaignDifficultyTier.MEDIUM, CampaignBlockRole.HARDCORE),
            AvailabilityFixture(81, CampaignDifficultyTier.HARD, CampaignBlockRole.STANDARD),
            AvailabilityFixture(85, CampaignDifficultyTier.HARD, CampaignBlockRole.SPIKE),
            AvailabilityFixture(90, CampaignDifficultyTier.HARD, CampaignBlockRole.HARDCORE),
            AvailabilityFixture(221, CampaignDifficultyTier.HARDCORE, CampaignBlockRole.STANDARD),
            AvailabilityFixture(225, CampaignDifficultyTier.HARDCORE, CampaignBlockRole.SPIKE),
            AvailabilityFixture(230, CampaignDifficultyTier.HARDCORE, CampaignBlockRole.HARDCORE),
        )

        fixtures.forEach { fixture ->
            val level = CampaignLevelGenerator.generate(fixture.levelNumber)
            assertEquals("level ${fixture.levelNumber} tier", fixture.tier, level.difficultyTier)
            assertEquals("level ${fixture.levelNumber} role", fixture.role, level.blockRole)
            assertTrue("level ${fixture.levelNumber} allows hints", level.hintsAllowed)
            assertTrue("level ${fixture.levelNumber} allows auto mode", level.autoModeAllowed)
        }
    }

    private data class BoostFixture(
        val levelNumber: Int,
        val tier: CampaignDifficultyTier,
        val role: CampaignBlockRole,
        val extraMoves: Int,
        val extraSeconds: Int,
    )

    private data class AvailabilityFixture(
        val levelNumber: Int,
        val tier: CampaignDifficultyTier,
        val role: CampaignBlockRole,
    )
}
