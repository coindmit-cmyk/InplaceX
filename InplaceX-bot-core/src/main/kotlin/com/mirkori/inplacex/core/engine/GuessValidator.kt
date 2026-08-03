package com.mirkori.inplacex.core.engine

import com.mirkori.inplacex.core.model.GameConfig

enum class GuessValidationReason {
    INVALID_LENGTH,
    NON_DIGIT,
    DUPLICATE_DIGITS,
    ALL_SAME_DIGITS,
    ADJACENT_DUPLICATES,
    TRIPLE_DUPLICATES,
    TOO_MANY_CONSECUTIVE_DUPLICATES,
}

object GuessValidator {

    fun validate(guess: String, config: GameConfig): Boolean {
        return validateOrReason(guess, config) == null
    }

    fun validate(guess: CharArray, config: GameConfig): Boolean {
        return validateOrReason(guess, config) == null
    }

    fun validateOrReason(guess: String, config: GameConfig): GuessValidationReason? {
        return validateOrReason(guess.length, config) { index -> guess[index] }
    }

    fun validateOrReason(guess: CharArray, config: GameConfig): GuessValidationReason? {
        return validateOrReason(guess.size, config) { index -> guess[index] }
    }

    private inline fun validateOrReason(
        length: Int,
        config: GameConfig,
        digitAt: (Int) -> Char,
    ): GuessValidationReason? {
        if (length != config.codeLength) {
            return GuessValidationReason.INVALID_LENGTH
        }

        val seenDigits = BooleanArray(10)
        var distinctDigits = 0
        var hasAdjacentDuplicates = false
        var hasTripleDuplicates = false
        var consecutiveDuplicateCount = 1
        var maximumConsecutiveDuplicateCount = 1

        for (index in 0 until length) {
            val digit = digitAt(index)
            if (digit !in '0'..'9') {
                return GuessValidationReason.NON_DIGIT
            }

            val digitIndex = digit - '0'
            if (!seenDigits[digitIndex]) {
                seenDigits[digitIndex] = true
                distinctDigits += 1
            }

            if (index > 0 && digitAt(index - 1) == digit) {
                hasAdjacentDuplicates = true
                consecutiveDuplicateCount += 1
                maximumConsecutiveDuplicateCount = maxOf(
                    maximumConsecutiveDuplicateCount,
                    consecutiveDuplicateCount,
                )
            } else {
                consecutiveDuplicateCount = 1
            }
            if (
                index > 1 &&
                digitAt(index - 1) == digit &&
                digitAt(index - 2) == digit
            ) {
                hasTripleDuplicates = true
            }
        }

        if (!config.allowDuplicates && distinctDigits != length) {
            return GuessValidationReason.DUPLICATE_DIGITS
        }

        if (config.forbidAllSameDigitsGuess && distinctDigits == 1) {
            return GuessValidationReason.ALL_SAME_DIGITS
        }

        if (config.forbidAdjacentDuplicates && hasAdjacentDuplicates) {
            return GuessValidationReason.ADJACENT_DUPLICATES
        }

        if (config.forbidTripleDuplicates && hasTripleDuplicates) {
            return GuessValidationReason.TRIPLE_DUPLICATES
        }

        if (
            config.maxConsecutiveDuplicateDigits?.let {
                maximumConsecutiveDuplicateCount > it
            } == true
        ) {
            return GuessValidationReason.TOO_MANY_CONSECUTIVE_DUPLICATES
        }

        return null
    }

    /**
     * Временный мост совместимости для существующих вызывающих компонентов.
     * Новые компоненты должны использовать [validateOrReason] и локализовать причину.
     */
    fun validateOrMessage(guess: String, config: GameConfig): String? {
        return validateOrReason(guess, config)?.toCompatibilityMessage(config)
    }

    private fun GuessValidationReason.toCompatibilityMessage(config: GameConfig): String {
        return when (this) {
            GuessValidationReason.INVALID_LENGTH -> "Need ${config.codeLength} digits"
            GuessValidationReason.NON_DIGIT -> "Only digits are allowed"
            GuessValidationReason.DUPLICATE_DIGITS -> "Duplicate digits are forbidden"
            GuessValidationReason.ALL_SAME_DIGITS -> "All digits cannot be the same"
            GuessValidationReason.ADJACENT_DUPLICATES -> "Adjacent duplicates are forbidden"
            GuessValidationReason.TRIPLE_DUPLICATES -> "Triple duplicates are forbidden"
            GuessValidationReason.TOO_MANY_CONSECUTIVE_DUPLICATES ->
                "Too many identical digits in a row"
        }
    }
}
