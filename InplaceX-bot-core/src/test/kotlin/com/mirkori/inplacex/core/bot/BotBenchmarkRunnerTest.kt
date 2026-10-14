package com.mirkori.inplacex.core.bot

import com.mirkori.inplacex.core.model.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotBenchmarkRunnerTest {

    @Test
    fun benchmarkReturnsAllRequestedDifficulties() {
        val report = BotBenchmarkRunner.run(
            BotBenchmarkRequest(
                config = GameConfig(
                    codeLength = 4,
                    allowDuplicates = true,
                    attemptLimit = 30,
                    forbidAllSameDigitsGuess = true,
                ),
                secret = "1234",
                behavior = BotBehaviorModel.BALANCED,
                samplesPerDifficulty = 1,
            ),
        )

        assertEquals(BotDifficulty.entries.size, report.entries.size)
        assertTrue(report.entries.all { it.samples == 1 })
        assertTrue(report.entries.all { it.secrets == listOf("1234") })
    }

    @Test
    fun analystBehaviorCanFinishReferenceSecret() {
        val run = BotSolver.solveSecret(
            secret = "1234",
            config = GameConfig(
                codeLength = 4,
                allowDuplicates = true,
                attemptLimit = 30,
                forbidAllSameDigitsGuess = true,
            ),
            difficulty = BotDifficulty.HARD,
            behavior = BotBehaviorModel.ANALYST,
            seed = 77L,
            maxMoves = 18,
        )

        assertTrue("analyst bot should finish reference secret", run.won)
        assertTrue("analyst bot took too many moves: ${run.moves}", run.moves <= 18)
    }

    @Test
    fun targetMovesRoundFractionalBudgetsUpward() {
        val profile = BotProfiles.hard.copy(
            targetMovesMultiplier = 1.25,
            minimumMoves = 1,
            expertBonusMoves = 0,
        )

        assertEquals(5, profile.targetMovesForCodeLength(4))
        assertEquals(7, profile.targetMovesForCodeLength(5))
        assertEquals(25, profile.targetMovesForCodeLength(20))
    }

    @Test
    fun minimumMoveFloorDoesNotCapLargerBudgets() {
        val profile = BotProfiles.hard.copy(
            targetMovesMultiplier = 1.25,
            minimumMoves = 9,
            expertBonusMoves = 0,
        )

        assertEquals(9, profile.targetMovesForCodeLength(4))
        assertEquals(9, profile.targetMovesForCodeLength(5))
        assertEquals(25, profile.targetMovesForCodeLength(20))
    }
}
