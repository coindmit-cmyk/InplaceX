package com.mirkori.inplacex.core.bot

import com.mirkori.inplacex.core.engine.SecretGenerator
import com.mirkori.inplacex.core.model.GameConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class BotSolverSimulationTest {

    @Test(timeout = 20_000)
    fun easyBotFitsLightweightModel() {
        val stats = runBatchSimulation(
            config = simulationConfig(),
            difficulty = BotDifficulty.EASY,
            samples = 10,
            seedBase = 100L,
            maxMovesFactor = 6.0,
        )

        assertTrue("easy win rate too low: ${stats.winRate}", stats.winRate >= 0.80)
        assertTrue("easy max moves too high: ${stats.maxMoves}", stats.maxMoves <= 24)
        assertTrue("easy average moves too high: ${stats.averageMoves}", stats.averageMoves <= 19.0)
    }

    @Test(timeout = 20_000)
    fun mediumBotFitsLightweightModel() {
        val stats = runBatchSimulation(
            config = simulationConfig(),
            difficulty = BotDifficulty.MEDIUM,
            samples = 10,
            seedBase = 200L,
            maxMovesFactor = 5.0,
        )

        assertTrue("medium win rate too low: ${stats.winRate}", stats.winRate >= 0.90)
        assertTrue("medium max moves too high: ${stats.maxMoves}", stats.maxMoves <= 20)
        assertTrue("medium average moves too high: ${stats.averageMoves}", stats.averageMoves <= 15.5)
    }

    @Test(timeout = 20_000)
    fun hardBotFitsLightweightModel() {
        val stats = runBatchSimulation(
            config = simulationConfig(),
            difficulty = BotDifficulty.HARD,
            samples = 10,
            seedBase = 300L,
            maxMovesFactor = 4.0,
        )

        assertTrue("hard win rate too low: ${stats.winRate}", stats.winRate >= 0.90)
        assertTrue("hard max moves too high: ${stats.maxMoves}", stats.maxMoves <= 16)
        assertTrue("hard average moves too high: ${stats.averageMoves}", stats.averageMoves <= 12.5)
    }

    @Test(timeout = 20_000)
    fun expertBotFitsLightweightModel() {
        val stats = runBatchSimulation(
            config = simulationConfig(),
            difficulty = BotDifficulty.EXPERT,
            samples = 10,
            seedBase = 400L,
            maxMovesFactor = 3.5,
        )

        assertTrue("expert win rate too low: ${stats.winRate}", stats.winRate >= 0.90)
        assertTrue("expert max moves too high: ${stats.maxMoves}", stats.maxMoves <= 14)
        assertTrue("expert average moves too high: ${stats.averageMoves}", stats.averageMoves <= 10.5)
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
            samples = 2,
            seedBase = 600L,
            maxMovesFactor = 6.0,
        )

        assertTrue("six-digit medium win rate too low: ${stats.winRate}", stats.winRate >= 0.60)
        assertTrue("six-digit medium max moves exploded: ${stats.maxMoves}", stats.maxMoves <= 36)
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
        maxMovesFactor: Double,
    ): BatchStats {
        val runs = (0 until samples).map { index ->
            val secret = SecretGenerator.generate(config.copy(seed = seedBase + index))
            BotSolver.solveSecret(
                secret = secret,
                config = config,
                difficulty = difficulty,
                seed = seedBase * 10 + index,
                maxMoves = (config.codeLength * maxMovesFactor).toInt(),
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
