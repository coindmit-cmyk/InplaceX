package com.mirkori.inplacex.core.engine

import com.mirkori.inplacex.core.model.GameConfig

object GuessValidator {

    fun validate(guess: String, config: GameConfig): Boolean {
        return validateOrMessage(guess, config) == null
    }

    fun validateOrMessage(guess: String, config: GameConfig): String? {
        if (guess.length != config.codeLength) {
            return "Нужно ввести ${config.codeLength} цифр"
        }

        if (!guess.all { it.isDigit() }) {
            return "Можно вводить только цифры"
        }

        if (!config.allowDuplicates && guess.toSet().size != guess.length) {
            return "Повторы запрещены"
        }

        if (config.forbidAllSameDigitsGuess && guess.toSet().size == 1) {
            return "Одинаковые цифры запрещены"
        }

        return null
    }
}
