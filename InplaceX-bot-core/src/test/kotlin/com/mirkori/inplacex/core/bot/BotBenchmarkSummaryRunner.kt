package com.mirkori.inplacex.core.bot

import com.mirkori.inplacex.core.engine.SecretGenerator
import com.mirkori.inplacex.core.model.GameConfig
import com.mirkori.inplacex.logging.InplaceXLogger
import com.mirkori.inplacex.logging.LogLevel
import com.mirkori.inplacex.testsupport.ConsoleLogSink
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.measureNanoTime

private const val SUMMARY_RUN_TIMEOUT_MINUTES = 1L
private const val DEFAULT_SUMMARY_SAMPLES_PER_BUCKET = 10
private const val DEFAULT_SUMMARY_SEED = 17_000L
private const val SUMMARY_LOG_TAG = "BotBenchmarkSummaryRunner"

private data class SummaryRun(
    val difficulty: BotDifficulty,
    val codeLength: Int,
    val secretSeed: Long,
    val runSeed: Long,
    val won: Boolean,
    val attempts: Int,
    val elapsedMillis: Long,
    val timedOut: Boolean,
)

private data class SummaryBucket(
    val difficulty: BotDifficulty,
    val codeLength: Int,
    val runs: List<SummaryRun>,
)

fun main() {
    val logger = InplaceXLogger(
        sink = ConsoleLogSink(),
        minLevel = LogLevel.INFO,
    )
    val samplesPerBucket = (
        System.getProperty("bot.summary.samples")
            ?: System.getenv("BOT_SUMMARY_SAMPLES")
        )
        ?.toIntOrNull()
        ?.coerceAtLeast(1)
        ?: DEFAULT_SUMMARY_SAMPLES_PER_BUCKET
    val difficulties = listOf(
        BotDifficulty.EASY,
        BotDifficulty.MEDIUM,
        BotDifficulty.HARD,
        BotDifficulty.EXPERT,
    )
    val codeLengths = (4..10).toList()

    var seedCursor = (
        System.getProperty("bot.summary.seed")
            ?: System.getenv("BOT_SUMMARY_SEED")
        )
        ?.toLongOrNull()
        ?: DEFAULT_SUMMARY_SEED
    val buckets = mutableListOf<SummaryBucket>()

    difficulties.forEach { difficulty ->
        codeLengths.forEach { codeLength ->
            val config = GameConfig(
                codeLength = codeLength,
                allowDuplicates = true,
                attemptLimit = codeLength * 10,
                forbidAllSameDigitsGuess = true,
            )
            val runs = (0 until samplesPerBucket).map {
                val secretSeed = seedCursor++
                val runSeed = seedCursor++
                val secret = SecretGenerator.generate(config.copy(seed = secretSeed))
                runSummaryBenchmark(
                    difficulty = difficulty,
                    config = config,
                    secret = secret,
                    secretSeed = secretSeed,
                    runSeed = runSeed,
                )
            }
            buckets += SummaryBucket(
                difficulty = difficulty,
                codeLength = codeLength,
                runs = runs,
            )
        }
    }

    val lines = buckets.mapIndexed { index, bucket ->
        val averageAttempts = bucket.runs.map { it.attempts }.average()
        val averageN = bucket.runs.map { it.attempts.toDouble() / bucket.codeLength.toDouble() }.average()
        val averageTimeMs = bucket.runs.map { it.elapsedMillis }.average()
        val wins = bucket.runs.count { it.won }
        val timeouts = bucket.runs.count { it.timedOut }
        val sortedAttempts = bucket.runs.map { it.attempts }.sorted()
        val p95Attempts = sortedAttempts[((sortedAttempts.size - 1) * 0.95).toInt()]
        val maximumAttempts = sortedAttempts.last()

        val failures = bucket.runs
            .filterNot(SummaryRun::won)
            .joinToString(separator = ",") { run -> "${run.secretSeed}:${run.runSeed}" }
            .ifEmpty { "none" }

        "${index + 1}. ${bucket.difficulty.name} | len=${bucket.codeLength} | wins=${wins}/${bucket.runs.size} | avgAttempts=${"%.2f".format(averageAttempts)} | p95Attempts=$p95Attempts | maxAttempts=$maximumAttempts | avgN=${"%.2f".format(averageN)} | avgTimeMs=${"%.2f".format(averageTimeMs)} | timeouts=${timeouts} | failureSeeds=$failures"
    }

    val reportDir = File("build/reports")
    reportDir.mkdirs()
    val reportFile = File(reportDir, "bot-benchmark-summary.txt")
    reportFile.writeText(lines.joinToString(System.lineSeparator()))

    lines.forEach { line ->
        logger.info(tag = SUMMARY_LOG_TAG, message = line)
    }
    logger.info(
        tag = SUMMARY_LOG_TAG,
        message = "report written",
        attributes = mapOf("path" to reportFile.absolutePath),
    )
}

private fun runSummaryBenchmark(
    difficulty: BotDifficulty,
    config: GameConfig,
    secret: String,
    secretSeed: Long,
    runSeed: Long,
): SummaryRun {
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
                    maxMoves = config.codeLength * 10,
                )
            }
            val run = checkNotNull(result)
            SummaryRun(
                difficulty = difficulty,
                codeLength = config.codeLength,
                secretSeed = secretSeed,
                runSeed = runSeed,
                won = run.won,
                attempts = run.moves,
                elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
                timedOut = false,
            )
        })

        future.get(SUMMARY_RUN_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    } catch (_: TimeoutException) {
        SummaryRun(
            difficulty = difficulty,
            codeLength = config.codeLength,
            secretSeed = secretSeed,
            runSeed = runSeed,
            won = false,
            attempts = config.codeLength * 10,
            elapsedMillis = TimeUnit.MINUTES.toMillis(SUMMARY_RUN_TIMEOUT_MINUTES),
            timedOut = true,
        )
    } finally {
        executor.shutdownNow()
    }
}
