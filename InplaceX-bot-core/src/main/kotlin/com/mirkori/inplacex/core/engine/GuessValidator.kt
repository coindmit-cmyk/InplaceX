package com.mirkori.inplacex.core.engine

import com.mirkori.inplacex.core.model.GameConfig

object GuessValidator {

    fun validate(guess: String, config: GameConfig): Boolean {
        return validateOrMessage(guess, config) == null
    }

    fun validateOrMessage(guess: String, config: GameConfig): String? {
        if (guess.length != config.codeLength) {
            return "Need ${config.codeLength} digits"
        }

        if (!guess.all { it.isDigit() }) {
            return "Only digits are allowed"
        }

        if (!config.allowDuplicates && guess.toSet().size != guess.length) {
            return "Duplicate digits are forbidden"
        }

        if (config.forbidAllSameDigitsGuess && guess.toSet().size == 1) {
            return "All digits cannot be the same"
        }

        if (config.forbidAdjacentDuplicates && hasAdjacentDuplicates(guess)) {
            return "Adjacent duplicates are forbidden"
        }

        if (config.forbidTripleDuplicates && hasTripleDuplicates(guess)) {
            return "Triple duplicates are forbidden"
        }

        return null
    }

    private fun hasAdjacentDuplicates(value: String): Boolean {
        return value.zipWithNext().any { (left, right) -> left == right }
    }

    private fun hasTripleDuplicates(value: String): Boolean {
        return value.windowed(size = 3, step = 1, partialWindows = false)
            .any { window -> window.toSet().size == 1 }
    }
}
