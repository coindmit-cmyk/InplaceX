package com.mirkori.inplacex.core.engine

import com.mirkori.inplacex.core.model.GameConfig

enum class GuessValidationReason {
    INVALID_LENGTH,
    NON_DIGIT,
    DUPLICATE_DIGITS,
    ALL_SAME_DIGITS,
    ADJACENT_DUPLICATES,
    TRIPLE_DUPLICATES,
}

object GuessValidator {

    fun validate(guess: String, config: GameConfig): Boolean {
        return validateOrReason(guess, config) == null
    }

    fun validateOrReason(guess: String, config: GameConfig): GuessValidationReason? {
        if (guess.length != config.codeLength) {
            return GuessValidationReason.INVALID_LENGTH
        }

        if (!guess.all { it.isDigit() }) {
            return GuessValidationReason.NON_DIGIT
        }

        if (!config.allowDuplicates && guess.toSet().size != guess.length) {
            return GuessValidationReason.DUPLICATE_DIGITS
        }

        if (config.forbidAllSameDigitsGuess && guess.toSet().size == 1) {
            return GuessValidationReason.ALL_SAME_DIGITS
        }

        if (config.forbidAdjacentDuplicates && hasAdjacentDuplicates(guess)) {
            return GuessValidationReason.ADJACENT_DUPLICATES
        }

        if (config.forbidTripleDuplicates && hasTripleDuplicates(guess)) {
            return GuessValidationReason.TRIPLE_DUPLICATES
        }

        return null
    }

    /**
     * Временный мост совместимости для существующих вызывающих компонентов.
     * Новые компоненты должны использовать [validateOrReason] и локализовать причину.
     */
    fun validateOrMessage(guess: String, config: GameConfig): String? {
        return when (val reason = validateOrReason(guess, config)) {
            null -> null
            GuessValidationReason.INVALID_LENGTH -> "Need ${config.codeLength} digits"
            GuessValidationReason.NON_DIGIT -> "Only digits are allowed"
            GuessValidationReason.DUPLICATE_DIGITS -> "Duplicate digits are forbidden"
            GuessValidationReason.ALL_SAME_DIGITS -> "All digits cannot be the same"
            GuessValidationReason.ADJACENT_DUPLICATES -> "Adjacent duplicates are forbidden"
            GuessValidationReason.TRIPLE_DUPLICATES -> "Triple duplicates are forbidden"
        }
    }

    private fun hasAdjacentDuplicates(value: String): Boolean {
        return value.zipWithNext().any { (left, right) -> left == right }
    }

    private fun hasTripleDuplicates(value: String): Boolean {
        return value.windowed(size = 3, step = 1, partialWindows = false)
            .any { window -> window.toSet().size == 1 }
    }
}
