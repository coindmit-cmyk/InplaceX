package com.mirkori.inplacex.core.bot

import com.mirkori.inplacex.core.engine.ScoreCalculator
import com.mirkori.inplacex.core.engine.SecretGenerator
import com.mirkori.inplacex.core.model.GameConfig
import java.io.File
import kotlin.system.measureNanoTime

private const val EASY_TRACE_CODE_LENGTH = 4
private const val EASY_TRACE_MAX_MOVES = EASY_TRACE_CODE_LENGTH * 10
private const val EASY_TRACE_SEARCH_LIMIT = 5_000

private data class EasyTraceStep(
    val move: Int,
    val stageBefore: BotSolveStage,
    val guess: String,
    val score: Int,
    val stageAfter: BotSolveStage,
    val resolvedAfter: String,
)

private data class EasyFailureTrace(
    val secret: String,
    val secretSeed: Long,
    val runSeed: Long,
    val won: Boolean,
    val attempts: Int,
    val elapsedMillis: Long,
    val steps: List<EasyTraceStep>,
)

fun main() {
    val config = GameConfig(
        codeLength = EASY_TRACE_CODE_LENGTH,
        allowDuplicates = true,
        attemptLimit = EASY_TRACE_MAX_MOVES,
        forbidAllSameDigitsGuess = true,
    )

    val failureTrace = findFirstFailure(config)
    val trace = failureTrace ?: runTrace(
        secret = "0536",
        config = config,
        secretSeed = 3L,
        runSeed = 4L,
    )

    val lines = buildList {
        if (failureTrace == null) {
            add("No EASY len=4 failure found in $EASY_TRACE_SEARCH_LIMIT sampled runs.")
            add("Regression trace for the previous bad case follows below.")
            add("")
        } else {
            add("EASY failure trace | len=${config.codeLength} | maxMoves=$EASY_TRACE_MAX_MOVES")
        }
        add("secret=${trace.secret} | secretSeed=${trace.secretSeed} | runSeed=${trace.runSeed} | won=${trace.won} | attempts=${trace.attempts} | timeMs=${trace.elapsedMillis}")
        add("")
        trace.steps.forEach { step ->
            add(
                "${step.move}. ${step.stageBefore.name} | guess=${step.guess} | score=${step.score} | resolved=${step.resolvedAfter} | next=${step.stageAfter.name}",
            )
        }
    }

    val reportDir = File("build/reports")
    reportDir.mkdirs()
    val reportFile = File(reportDir, "bot-easy-failure-trace.txt")
    reportFile.writeText(lines.joinToString(System.lineSeparator()))

    lines.forEach(::println)
    println("Report written to: ${reportFile.absolutePath}")
}

private fun findFirstFailure(config: GameConfig): EasyFailureTrace? {
    var seedCursor = 1L

    repeat(EASY_TRACE_SEARCH_LIMIT) {
        val secretSeed = seedCursor++
        val runSeed = seedCursor++
        val secret = SecretGenerator.generate(config.copy(seed = secretSeed))
        val trace = runTrace(
            secret = secret,
            config = config,
            secretSeed = secretSeed,
            runSeed = runSeed,
        )
        if (!trace.won) {
            return trace
        }
    }

    return null
}

private fun runTrace(
    secret: String,
    config: GameConfig,
    secretSeed: Long,
    runSeed: Long,
): EasyFailureTrace {
    val solver = BotSolver(
        config = config,
        difficulty = BotDifficulty.EASY,
        seed = runSeed,
    )
    val steps = mutableListOf<EasyTraceStep>()
    var won = false

    val elapsedNanos = measureNanoTime {
        var moves = 0
        while (moves < EASY_TRACE_MAX_MOVES && !won) {
            val before = solver.snapshot()
            val guess = solver.nextTurn().guess
            val score = ScoreCalculator.countExactMatches(secret, guess)
            solver.registerFeedback(guess, score)
            val after = solver.snapshot()

            steps += EasyTraceStep(
                move = moves + 1,
                stageBefore = before.stage,
                guess = guess,
                score = score,
                stageAfter = after.stage,
                resolvedAfter = renderResolved(after, config.codeLength),
            )

            moves += 1
            won = score == config.codeLength
        }
    }

    return EasyFailureTrace(
        secret = secret,
        secretSeed = secretSeed,
        runSeed = runSeed,
        won = won,
        attempts = steps.size,
        elapsedMillis = elapsedNanos / 1_000_000,
        steps = steps,
    )
}

private fun renderResolved(
    state: BotSolverState,
    codeLength: Int,
): String {
    return buildString {
        repeat(codeLength) { index ->
            append(state.resolvedPositions[index] ?: '-')
        }
    }
}
