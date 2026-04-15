package com.mirkori.inplacex.core.engine

import com.mirkori.inplacex.core.model.GameConfig
import kotlin.random.Random

object SecretGenerator {

    fun generate(config: GameConfig): String {
        val random = config.seed?.let(::Random) ?: Random.Default

        return if (config.allowDuplicates) {
            buildString {
                repeat(config.codeLength) {
                    append(random.nextInt(0, 10))
                }
            }
        } else {
            ('0'..'9').shuffled(random)
                .take(config.codeLength)
                .joinToString("")
        }
    }
}
