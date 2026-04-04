package com.mirkori.inplacex.core.engine

import com.mirkori.inplacex.core.model.GameConfig

object GuessValidator {

    fun validate(guess: String, config: GameConfig): Boolean {

        // длина
        if (guess.length != config.codeLength) return false

        // только цифры
        if (!guess.all { it.isDigit() }) return false

        // запрет "111111"
        if (guess.toSet().size == 1) return false

        // если повторы запрещены
        if (!config.allowDuplicates) {
            if (guess.toSet().size != guess.length) return false
        }

        return true
    }
}