package com.mirkori.inplacex.core.engine

object ScoreCalculator {

    fun countExactMatches(secret: String, guess: String): Int {
        require(secret.length == guess.length)

        return countExactMatches(secret.length) { index -> secret[index] == guess[index] }
    }

    fun countExactMatches(secret: CharArray, guess: CharArray): Int {
        require(secret.size == guess.size)

        return countExactMatches(secret.size) { index -> secret[index] == guess[index] }
    }

    private inline fun countExactMatches(
        length: Int,
        matchesAt: (Int) -> Boolean,
    ): Int {
        var exactMatches = 0
        for (index in 0 until length) {
            if (matchesAt(index)) {
                exactMatches += 1
            }
        }
        return exactMatches
    }
}
