package com.mirkori.inplacex.core.bot

import com.mirkori.inplacex.core.engine.GuessValidator
import com.mirkori.inplacex.core.engine.SecretGenerator
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
}
