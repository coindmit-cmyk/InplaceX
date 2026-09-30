package com.mirkori.inplacex.core.bot

import com.mirkori.inplacex.core.engine.GuessValidator
import com.mirkori.inplacex.core.model.GameConfig

data class BotMatchRules(
    val codeLength: Int,
    val alphabet: List<Char>,
    val allowDuplicates: Boolean,
    val forbidAllSameDigitsGuess: Boolean,
    val forbidAdjacentDuplicates: Boolean,
    val forbidTripleDuplicates: Boolean,
    val maxConsecutiveDuplicateDigits: Int? = null,
) {
    fun toGameConfig(seed: Long? = null): GameConfig {
        return GameConfig(
            codeLength = codeLength,
            allowDuplicates = allowDuplicates,
            attemptLimit = 999,
            forbidAllSameDigitsGuess = forbidAllSameDigitsGuess,
            forbidAdjacentDuplicates = forbidAdjacentDuplicates,
            forbidTripleDuplicates = forbidTripleDuplicates,
            maxConsecutiveDuplicateDigits = maxConsecutiveDuplicateDigits,
            seed = seed,
        )
    }

    fun isValidCode(value: String): Boolean {
        return GuessValidator.validate(value, toGameConfig())
    }

    companion object {
        val digits: List<Char> = ('0'..'9').toList()
    }
}

fun GameConfig.toBotMatchRules(): BotMatchRules {
    return BotMatchRules(
        codeLength = codeLength,
        alphabet = BotMatchRules.digits,
        allowDuplicates = allowDuplicates,
        forbidAllSameDigitsGuess = forbidAllSameDigitsGuess,
        forbidAdjacentDuplicates = forbidAdjacentDuplicates,
        forbidTripleDuplicates = forbidTripleDuplicates,
        maxConsecutiveDuplicateDigits = maxConsecutiveDuplicateDigits,
    )
}
