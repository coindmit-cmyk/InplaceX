package com.mirkori.inplacex.core.engine

import com.mirkori.inplacex.core.model.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuessValidatorTest {

    @Test
    fun validGuessHasNoTypedReason() {
        assertNull(GuessValidator.validateOrReason("123456", config()))
        assertTrue(GuessValidator.validate("123456", config()))
    }

    @Test
    fun lengthRuleReturnsTypedReason() {
        assertEquals(
            GuessValidationReason.INVALID_LENGTH,
            GuessValidator.validateOrReason("12345", config()),
        )
    }

    @Test
    fun digitRuleReturnsTypedReason() {
        assertEquals(
            GuessValidationReason.NON_DIGIT,
            GuessValidator.validateOrReason("1234a6", config()),
        )
    }

    @Test
    fun duplicateRuleReturnsTypedReason() {
        val config = config(allowDuplicates = false)

        assertEquals(
            GuessValidationReason.DUPLICATE_DIGITS,
            GuessValidator.validateOrReason("112345", config),
        )
    }

    @Test
    fun allSameRuleReturnsTypedReason() {
        assertEquals(
            GuessValidationReason.ALL_SAME_DIGITS,
            GuessValidator.validateOrReason("111111", config()),
        )
    }

    @Test
    fun adjacencyRuleReturnsTypedReason() {
        val config = config(forbidAdjacentDuplicates = true)

        assertEquals(
            GuessValidationReason.ADJACENT_DUPLICATES,
            GuessValidator.validateOrReason("112345", config),
        )
    }

    @Test
    fun tripleRuleReturnsTypedReason() {
        val config = config(forbidTripleDuplicates = true)

        assertEquals(
            GuessValidationReason.TRIPLE_DUPLICATES,
            GuessValidator.validateOrReason("111234", config),
        )
    }

    @Test
    fun messageBridgePreservesExistingMessagesWithoutDrivingValidation() {
        val config = config(forbidAdjacentDuplicates = true)

        assertEquals("Adjacent duplicates are forbidden", GuessValidator.validateOrMessage("112345", config))
        assertEquals(
            GuessValidationReason.ADJACENT_DUPLICATES,
            GuessValidator.validateOrReason("112345", config),
        )
    }

    @Test
    fun mutablePathMatchesStringRulesWithoutChangingTheInput() {
        val cases = listOf(
            "123456" to config(),
            "12345" to config(),
            "1234a6" to config(),
            "123\u066456" to config(),
            "112345" to config(allowDuplicates = false),
            "111111" to config(),
            "112345" to config(forbidAdjacentDuplicates = true),
            "111234" to config(forbidTripleDuplicates = true),
            "111234" to config(
                forbidAllSameDigitsGuess = false,
                forbidAdjacentDuplicates = false,
                forbidTripleDuplicates = false,
            ),
        )

        cases.forEach { (value, gameConfig) ->
            val mutableValue = value.toCharArray()
            val expectedInput = mutableValue.copyOf()

            assertEquals(
                "Typed reason differs for $value",
                GuessValidator.validateOrReason(value, gameConfig),
                GuessValidator.validateOrReason(mutableValue, gameConfig),
            )
            assertEquals(
                "Boolean result differs for $value",
                GuessValidator.validate(value, gameConfig),
                GuessValidator.validate(mutableValue, gameConfig),
            )
            assertTrue(mutableValue.contentEquals(expectedInput))
        }
    }

    @Test
    fun bothPathsRejectNonAsciiDigits() {
        val nonAsciiDigit = "123\u066456"

        assertEquals(
            GuessValidationReason.NON_DIGIT,
            GuessValidator.validateOrReason(nonAsciiDigit, config()),
        )
        assertEquals(
            GuessValidationReason.NON_DIGIT,
            GuessValidator.validateOrReason(nonAsciiDigit.toCharArray(), config()),
        )
    }

    private fun config(
        allowDuplicates: Boolean = true,
        forbidAllSameDigitsGuess: Boolean = true,
        forbidAdjacentDuplicates: Boolean = false,
        forbidTripleDuplicates: Boolean = false,
    ): GameConfig {
        return GameConfig(
            codeLength = 6,
            allowDuplicates = allowDuplicates,
            attemptLimit = 20,
            forbidAllSameDigitsGuess = forbidAllSameDigitsGuess,
            forbidAdjacentDuplicates = forbidAdjacentDuplicates,
            forbidTripleDuplicates = forbidTripleDuplicates,
        )
    }
}
