package com.mirkori.inplacex.core.bot

import com.mirkori.inplacex.core.engine.SecretGenerator
import com.mirkori.inplacex.core.model.GameConfig
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.measureNanoTime

private const val RUN_TIMEOUT_MINUTES = 1L

private data class BenchmarkRow(
    val difficulty: BotDifficulty,
    val codeLength: Int,
    val secret: String,
    val attemptsLabel: String,
    val attemptsCount: Int?,
    val elapsedMillis: Long,
)

fun main() {
    val difficulties = listOf(
        BotDifficulty.EASY,
        BotDifficulty.MEDIUM,
        BotDifficulty.HARD,
    )
    val codeLengths = listOf(4, 6, 8, 10)
    val samplesPerLength = 3

    val rows = mutableListOf<BenchmarkRow>()
    var seedCursor = System.currentTimeMillis()

    difficulties.forEach { difficulty ->
        codeLengths.forEach { codeLength ->
            repeat(samplesPerLength) {
                val config = GameConfig(
                    codeLength = codeLength,
                    allowDuplicates = true,
                    attemptLimit = 120,
                    forbidAllSameDigitsGuess = true,
                )
                val secret = SecretGenerator.generate(config.copy(seed = seedCursor++))
                rows += runTimedBenchmark(
                    difficulty = difficulty,
                    config = config,
                    secret = secret,
                    runSeed = seedCursor++,
                )
            }
        }
    }

    val lines = rows.mapIndexed { index, row ->
        val nLabel = row.attemptsCount?.let { attempts ->
            "%.2f".format(attempts.toDouble() / row.codeLength.toDouble())
        } ?: "n/a"
        "${index + 1}. ${row.difficulty.name} | len=${row.codeLength} | secret=${row.secret} | attempts=${row.attemptsLabel} | n=${nLabel} | timeMs=${row.elapsedMillis}"
    }

    val reportDir = File("build/reports")
    reportDir.mkdirs()
    val reportFile = File(reportDir, "bot-benchmark-matrix.txt")
    reportFile.writeText(lines.joinToString(System.lineSeparator()))

    lines.forEach(::println)
    println("Report written to: ${reportFile.absolutePath}")
}

private fun runTimedBenchmark(
    difficulty: BotDifficulty,
    config: GameConfig,
    secret: String,
    runSeed: Long,
): BenchmarkRow {
    val executor = Executors.newSingleThreadExecutor()
    return try {
        val future = executor.submit(Callable {
            var result: BotSimulationRun? = null
            val elapsedNanos = measureNanoTime {
                result = BotSolver.solveSecret(
                    secret = secret,
                    config = config,
                    difficulty = difficulty,
                    seed = runSeed,
                    maxMoves = BotProfiles.forDifficulty(difficulty).targetMovesForCodeLength(config.codeLength) + 12,
                )
            }
            val run = checkNotNull(result)
            BenchmarkRow(
                difficulty = difficulty,
                codeLength = config.codeLength,
                secret = secret,
                attemptsLabel = if (run.won) run.moves.toString() else "failed:${run.moves}",
                attemptsCount = run.moves,
                elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
            )
        })

        future.get(RUN_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    } catch (_: TimeoutException) {
        BenchmarkRow(
            difficulty = difficulty,
            codeLength = config.codeLength,
            secret = secret,
            attemptsLabel = "timeout",
            attemptsCount = null,
            elapsedMillis = TimeUnit.MINUTES.toMillis(RUN_TIMEOUT_MINUTES),
        )
    } finally {
        executor.shutdownNow()
    }
}
