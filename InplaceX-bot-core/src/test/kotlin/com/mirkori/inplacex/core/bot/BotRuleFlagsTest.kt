package com.mirkori.inplacex.core.bot

import com.mirkori.inplacex.core.engine.GuessValidator
import com.mirkori.inplacex.core.engine.SecretGenerator
import com.mirkori.inplacex.core.engine.ScoreCalculator
import com.mirkori.inplacex.core.model.GameConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotRuleFlagsTest {

    @Test
    fun adjacentDuplicateFlagRejectsDoubleRun() {
        val config = GameConfig(
            codeLength = 6,
            allowDuplicates = true,
            attemptLimit = 20,
            forbidAdjacentDuplicates = true,
        )

        assertFalse(GuessValidator.validate("112345", config))
        assertTrue(GuessValidator.validate("121345", config))
    }

    @Test
    fun tripleDuplicateFlagRejectsTripleRunButAllowsSeparatedRepeats() {
        val config = GameConfig(
            codeLength = 6,
            allowDuplicates = true,
            attemptLimit = 20,
            forbidTripleDuplicates = true,
        )

        assertFalse(GuessValidator.validate("111234", config))
        assertTrue(GuessValidator.validate("121212", config))
    }

    @Test
    fun secretGeneratorRespectsActiveDuplicateFlags() {
        val config = GameConfig(
            codeLength = 6,
            allowDuplicates = true,
            attemptLimit = 20,
            forbidAdjacentDuplicates = true,
            seed = 123L,
        )

        val secret = SecretGenerator.generate(config)
        assertTrue(GuessValidator.validate(secret, config))
    }

    @Test
    fun botPreservesTheMaximumConsecutiveDuplicateRule() {
        val config = GameConfig(
            codeLength = 6,
            allowDuplicates = true,
            attemptLimit = 40,
            maxConsecutiveDuplicateDigits = 3,
        )
        val solver = BotSolver(
            config = config,
            difficulty = BotDifficulty.MEDIUM,
            seed = 91L,
        )
        val secret = "111234"

        repeat(40) {
            val turn = solver.nextTurn()
            assertTrue(
                "bot emitted a rule-invalid guess: ${turn.guess}",
                GuessValidator.validate(turn.guess, config),
            )
            val score = ScoreCalculator.countExactMatches(secret, turn.guess)
            solver.registerFeedback(turn.guess, score)
            if (score == config.codeLength) return
        }
        throw AssertionError("bot did not finish the maximum-run regression")
    }
}
