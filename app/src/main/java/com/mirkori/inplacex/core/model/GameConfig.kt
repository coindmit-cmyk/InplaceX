package com.mirkori.inplacex.core.model

data class GameConfig(
    val codeLength: Int = 6,
    val allowDuplicates: Boolean = true,
    val attemptLimit: Int = 12,
    val forbidAllSameDigitsGuess: Boolean = true,
    val turnTimeLimitSeconds: Int? = null,
    val seed: Long? = null,
) {
    init {
        require(codeLength in 4..20) { "codeLength must be in 4..20" }
        require(attemptLimit > 0) { "attemptLimit must be > 0" }
    }
}
