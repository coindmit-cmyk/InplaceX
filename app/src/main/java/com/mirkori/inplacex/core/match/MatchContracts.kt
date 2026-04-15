package com.mirkori.inplacex.core.match

import com.mirkori.inplacex.core.model.GameConfig

enum class MatchPhase {
    NOT_STARTED,
    ACTIVE,
    WON,
    LOST,
}

data class MatchAttempt(
    val guess: String,
    val score: Int,
    val number: Int,
    val isWin: Boolean,
)

data class MatchSnapshot(
    val phase: MatchPhase,
    val attempts: List<MatchAttempt>,
    val attemptsLeft: Int,
    val debugSecret: String,
    val message: String? = null,
)

interface MatchEngine {
    val config: GameConfig

    fun start(): MatchSnapshot

    fun submit(rawGuess: String): MatchSnapshot

    fun snapshot(message: String? = null): MatchSnapshot
}
