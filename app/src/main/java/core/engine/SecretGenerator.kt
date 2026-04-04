package com.mirkori.inplacex.core.engine

import com.mirkori.inplacex.core.model.GameConfig
import kotlin.random.Random

object SecretGenerator {

    fun generate(config: GameConfig): String {
        val digits = ('0'..'9').toList()

        return if (config.allowDuplicates) {
            (1..config.codeLength)
                .map { digits.random() }
                .joinToString("")
        } else {
            digits.shuffled()
                .take(config.codeLength)
                .joinToString("")
        }
    }
}