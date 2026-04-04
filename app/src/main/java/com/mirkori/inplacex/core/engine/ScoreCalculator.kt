package com.mirkori.inplacex.core.engine

object ScoreCalculator {

    fun countExactMatches(secret: String, guess: String): Int {
        require(secret.length == guess.length)

        return secret.indices.count { i ->
            secret[i] == guess[i]
        }
    }
}