package com.mirkori.inplacex.core.model

data class GameConfig(
    val codeLength: Int = 6,
    val allowDuplicates: Boolean = true,
    val attemptLimit: Int = 12,
    val forbidAllSameDigitsGuess: Boolean = true,
    val forbidAdjacentDuplicates: Boolean = false,
    val forbidTripleDuplicates: Boolean = false,
    val maxConsecutiveDuplicateDigits: Int? = null,
    val turnTimeLimitSeconds: Int? = null,
    val seed: Long? = null,
) {
    init {
        require(codeLength in 4..20) { "codeLength must be in 4..20" }
        require(allowDuplicates || codeLength <= DIGIT_ALPHABET_SIZE) {
            "codeLength must not exceed $DIGIT_ALPHABET_SIZE when duplicate digits are disabled"
        }
        require(attemptLimit > 0) { "attemptLimit must be > 0" }
        require(maxConsecutiveDuplicateDigits == null || maxConsecutiveDuplicateDigits in 1..codeLength) {
            "maxConsecutiveDuplicateDigits must be null or in 1..codeLength"
        }
        require(turnTimeLimitSeconds == null || turnTimeLimitSeconds > 0) {
            "turnTimeLimitSeconds must be null or > 0"
        }
    }
}

private const val DIGIT_ALPHABET_SIZE = 10
