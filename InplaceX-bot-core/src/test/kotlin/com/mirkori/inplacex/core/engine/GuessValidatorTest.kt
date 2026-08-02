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
    fun maximumConsecutiveRuleAllowsThreeButRejectsFourEqualDigits() {
        val config = config(maxConsecutiveDuplicateDigits = 3)

        assertNull(GuessValidator.validateOrReason("111234", config))
        assertNull(GuessValidator.validateOrReason("112111", config))
        assertEquals(
            GuessValidationReason.TOO_MANY_CONSECUTIVE_DUPLICATES,
            GuessValidator.validateOrReason("111123", config),
        )
    }

    @Test
    fun messageBridgeReturnsForAllTypedRejections() {
        val cases = listOf(
            Triple("12345", config(), GuessValidationReason.INVALID_LENGTH),
            Triple("1234a6", config(), GuessValidationReason.NON_DIGIT),
            Triple("112345", config(allowDuplicates = false), GuessValidationReason.DUPLICATE_DIGITS),
            Triple("111111", config(), GuessValidationReason.ALL_SAME_DIGITS),
            Triple("112345", config(forbidAdjacentDuplicates = true), GuessValidationReason.ADJACENT_DUPLICATES),
            Triple("111234", config(forbidTripleDuplicates = true), GuessValidationReason.TRIPLE_DUPLICATES),
            Triple("111123", config(maxConsecutiveDuplicateDigits = 3), GuessValidationReason.TOO_MANY_CONSECUTIVE_DUPLICATES),
        )
        val compatibilityMessages = mapOf(
            GuessValidationReason.INVALID_LENGTH to "Need 6 digits",
            GuessValidationReason.NON_DIGIT to "Only digits are allowed",
            GuessValidationReason.DUPLICATE_DIGITS to "Duplicate digits are forbidden",
            GuessValidationReason.ALL_SAME_DIGITS to "All digits cannot be the same",
            GuessValidationReason.ADJACENT_DUPLICATES to "Adjacent duplicates are forbidden",
            GuessValidationReason.TRIPLE_DUPLICATES to "Triple duplicates are forbidden",
            GuessValidationReason.TOO_MANY_CONSECUTIVE_DUPLICATES to "Too many identical digits in a row",
        )

        cases.forEach { (guess, gameConfig, reason) ->
            val message = GuessValidator.validateOrMessage(guess, gameConfig)

            assertEquals(
                "Unexpected typed reason for $guess",
                reason,
                GuessValidator.validateOrReason(guess, gameConfig),
            )
            assertEquals(
                "Unexpected compatibility message for $reason",
                compatibilityMessages.getValue(reason),
                message,
            )
        }
    }

    @Test
    fun messageBridgeReturnsNullForValidGuess() {
        val valid = "123456"

        assertEquals(null, GuessValidator.validateOrMessage(valid, config()))
        assertEquals(null, GuessValidator.validateOrReason(valid, config()))
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
        maxConsecutiveDuplicateDigits: Int? = null,
    ): GameConfig {
        return GameConfig(
            codeLength = 6,
            allowDuplicates = allowDuplicates,
            attemptLimit = 20,
            forbidAllSameDigitsGuess = forbidAllSameDigitsGuess,
            forbidAdjacentDuplicates = forbidAdjacentDuplicates,
            forbidTripleDuplicates = forbidTripleDuplicates,
            maxConsecutiveDuplicateDigits = maxConsecutiveDuplicateDigits,
        )
    }
}
