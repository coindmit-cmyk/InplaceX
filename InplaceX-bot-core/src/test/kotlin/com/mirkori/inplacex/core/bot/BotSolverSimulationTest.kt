package com.mirkori.inplacex.core.bot

import com.mirkori.inplacex.core.engine.SecretGenerator
import com.mirkori.inplacex.core.model.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotSolverSimulationTest {

    @Test(timeout = 30_000)
    fun difficultyCurveRemainsOrderedOnFourDigitSecrets() {
        val config = simulationConfig()

        val easy = runBatchSimulation(
            config = config,
            difficulty = BotDifficulty.EASY,
            samples = 12,
            seedBase = 100L,
            maxMoves = 24,
        )
        val medium = runBatchSimulation(
            config = config,
            difficulty = BotDifficulty.MEDIUM,
            samples = 12,
            seedBase = 200L,
            maxMoves = 20,
        )
        val hard = runBatchSimulation(
            config = config,
            difficulty = BotDifficulty.HARD,
            samples = 12,
            seedBase = 300L,
            maxMoves = 18,
        )
        val expert = runBatchSimulation(
            config = config,
            difficulty = BotDifficulty.EXPERT,
            samples = 12,
            seedBase = 400L,
            maxMoves = 18,
        )

        assertTrue("easy win rate too low: ${easy.winRate}", easy.winRate >= 0.40)
        assertTrue("medium win rate too low: ${medium.winRate}", medium.winRate >= 0.75)
        assertTrue("hard win rate too low: ${hard.winRate}", hard.winRate >= 0.75)
        assertTrue("expert win rate too low: ${expert.winRate}", expert.winRate >= 0.70)

        assertTrue("medium should beat easy on average: easy=${easy.averageMoves}, medium=${medium.averageMoves}",
            medium.averageMoves <= easy.averageMoves)
        assertTrue("hard should not be slower than medium: hard=${hard.averageMoves}, medium=${medium.averageMoves}",
            hard.averageMoves <= medium.averageMoves + 0.5)
        assertTrue("expert should stay close to hard: expert=${expert.averageMoves}, hard=${hard.averageMoves}",
            expert.averageMoves <= hard.averageMoves + 1.0)
    }

    @Test(timeout = 60_000)
    fun mediumBotDoesNotStallOnSixDigitSecrets() {
        val config = GameConfig(
            codeLength = 6,
            allowDuplicates = true,
            attemptLimit = 40,
            forbidAllSameDigitsGuess = true,
        )

        val stats = runBatchSimulation(
            config = config,
            difficulty = BotDifficulty.MEDIUM,
            samples = 4,
            seedBase = 600L,
            maxMoves = 36,
        )

        assertTrue("six-digit medium win rate too low: ${stats.winRate}", stats.winRate >= 0.50)
        assertTrue("six-digit medium max moves exploded: ${stats.maxMoves}", stats.maxMoves <= 36)
    }

    @Test(timeout = 30_000)
    fun hardBotStaysWithinReasonableMoveBudget() {
        val stats = runBatchSimulation(
            config = simulationConfig(),
            difficulty = BotDifficulty.HARD,
            samples = 12,
            seedBase = 800L,
            maxMoves = 18,
        )

        assertTrue("hard average moves too high: ${stats.averageMoves}", stats.averageMoves <= 14.5)
        assertTrue("hard max moves too high: ${stats.maxMoves}", stats.maxMoves <= 18)
    }

    @Test(timeout = 60_000)
    fun mediumMaintainsVisibleGapFromEasyOnTenDigitSecrets() {
        val config = GameConfig(
            codeLength = 10,
            allowDuplicates = true,
            attemptLimit = 100,
            forbidAllSameDigitsGuess = true,
        )

        val easy = runBatchSimulation(
            config = config,
            difficulty = BotDifficulty.EASY,
            samples = 8,
            seedBase = 1_200L,
            maxMoves = 100,
        )
        val medium = runBatchSimulation(
            config = config,
            difficulty = BotDifficulty.MEDIUM,
            samples = 8,
            seedBase = 1_200L,
            maxMoves = 100,
        )

        assertTrue("easy should solve the batch: ${easy.winRate}", easy.winRate >= 1.0)
        assertTrue("medium should solve the batch: ${medium.winRate}", medium.winRate >= 1.0)
        assertTrue(
            "medium should keep a visible move gap on len=10: easy=${easy.averageMoves}, medium=${medium.averageMoves}",
            medium.averageMoves <= easy.averageMoves - 5.0,
        )
    }

    @Test(timeout = 60_000)
    fun expertMaintainsVisibleGapFromHardOnTenDigitSecrets() {
        val config = GameConfig(
            codeLength = 10,
            allowDuplicates = true,
            attemptLimit = 100,
            forbidAllSameDigitsGuess = true,
        )

        val hard = runBatchSimulation(
            config = config,
            difficulty = BotDifficulty.HARD,
            samples = 8,
            seedBase = 1_600L,
            maxMoves = 100,
        )
        val expert = runBatchSimulation(
            config = config,
            difficulty = BotDifficulty.EXPERT,
            samples = 8,
            seedBase = 1_600L,
            maxMoves = 100,
        )

        assertTrue("hard should solve the batch: ${hard.winRate}", hard.winRate >= 1.0)
        assertTrue("expert should solve the batch: ${expert.winRate}", expert.winRate >= 1.0)
        assertTrue(
            "expert should keep a visible move gap on len=10: hard=${hard.averageMoves}, expert=${expert.averageMoves}",
            expert.averageMoves <= hard.averageMoves - 1.0,
        )
    }

    @Test(timeout = 30_000)
    fun easyBotDoesNotStallOnZeroPrefixedSecretDuringSafeBaseSearch() {
        val config = GameConfig(
            codeLength = 4,
            allowDuplicates = true,
            attemptLimit = 40,
            forbidAllSameDigitsGuess = true,
        )

        val result = BotSolver.solveSecret(
            secret = "0536",
            config = config,
            difficulty = BotDifficulty.EASY,
            seed = 4L,
            maxMoves = 40,
        )

        assertEquals("0536", result.secret)
        assertTrue("easy bot still stalled on 0536 with seed=4", result.won)
        assertTrue("easy bot used too many moves: ${result.moves}", result.moves < 40)
    }

    private fun simulationConfig(): GameConfig {
        return GameConfig(
            codeLength = 4,
            allowDuplicates = true,
            attemptLimit = 30,
            forbidAllSameDigitsGuess = true,
        )
    }

    private fun runBatchSimulation(
        config: GameConfig,
        difficulty: BotDifficulty,
        samples: Int,
        seedBase: Long,
        maxMoves: Int,
    ): BatchStats {
        val runs = (0 until samples).map { index ->
            val secret = SecretGenerator.generate(config.copy(seed = seedBase + index))
            BotSolver.solveSecret(
                secret = secret,
                config = config,
                difficulty = difficulty,
                seed = seedBase * 10 + index,
                maxMoves = maxMoves,
            )
        }

        val wins = runs.count { it.won }
        return BatchStats(
            winRate = wins.toDouble() / samples,
            averageMoves = runs.map { it.moves }.average(),
            maxMoves = runs.maxOf { it.moves },
        )
    }

    private data class BatchStats(
        val winRate: Double,
        val averageMoves: Double,
        val maxMoves: Int,
    )
}
