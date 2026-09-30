package com.mirkori.inplacex.core.bot

import com.mirkori.inplacex.core.engine.SecretGenerator
import com.mirkori.inplacex.core.model.GameConfig

data class BotBenchmarkRequest(
    val config: GameConfig,
    val secret: String? = null,
    val difficulties: List<BotDifficulty> = BotDifficulty.entries,
    val behavior: BotBehaviorModel = BotBehaviorModel.BALANCED,
    val samplesPerDifficulty: Int = 1,
    val seedBase: Long = 1_000L,
)

data class BotBenchmarkEntry(
    val difficulty: BotDifficulty,
    val behavior: BotBehaviorModel,
    val wins: Int,
    val samples: Int,
    val averageMoves: Double,
    val bestMoves: Int,
    val worstMoves: Int,
    val targetMoves: Int,
    val secrets: List<String>,
) {
    val winRate: Double
        get() = wins.toDouble() / samples.toDouble()
}

data class BotBenchmarkReport(
    val config: GameConfig,
    val behavior: BotBehaviorModel,
    val entries: List<BotBenchmarkEntry>,
)

object BotBenchmarkRunner {
    fun run(request: BotBenchmarkRequest): BotBenchmarkReport {
        val config = request.config
        val sampleCount = request.samplesPerDifficulty.coerceAtLeast(1)

        val entries = request.difficulties.mapIndexed { difficultyIndex, difficulty ->
            val runs = (0 until sampleCount).map { sampleIndex ->
                val sampleSeed = request.seedBase + difficultyIndex * 1_000L + sampleIndex
                val secret = request.secret ?: SecretGenerator.generate(config.copy(seed = sampleSeed))
                BotSolver.solveSecret(
                    secret = secret,
                    config = config,
                    difficulty = difficulty,
                    behavior = request.behavior,
                    seed = sampleSeed * 13L,
                    maxMoves = BotProfiles.forDifficulty(difficulty).targetMovesForCodeLength(config.codeLength) + 8,
                )
            }

            BotBenchmarkEntry(
                difficulty = difficulty,
                behavior = request.behavior,
                wins = runs.count { it.won },
                samples = sampleCount,
                averageMoves = runs.map { it.moves }.average(),
                bestMoves = runs.minOf { it.moves },
                worstMoves = runs.maxOf { it.moves },
                targetMoves = BotProfiles.forDifficulty(difficulty).targetMovesForCodeLength(config.codeLength),
                secrets = runs.map { it.secret },
            )
        }

        return BotBenchmarkReport(
            config = config,
            behavior = request.behavior,
            entries = entries,
        )
    }
}
