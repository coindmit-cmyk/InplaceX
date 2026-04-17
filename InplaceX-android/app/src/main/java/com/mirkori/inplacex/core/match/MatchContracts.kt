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

sealed interface MatchHintResult {
    data class PositionChecked(
        val digit: Int,
        val position: Int,
        val isMatch: Boolean,
    ) : MatchHintResult

    data class PositionOpened(
        val position: Int,
        val digit: Int,
    ) : MatchHintResult

    data class DigitCountChecked(
        val digit: Int,
        val count: Int,
    ) : MatchHintResult
}

data class MatchHintOutcome(
    val snapshot: MatchSnapshot,
    val result: MatchHintResult? = null,
    val message: String? = null,
)

interface MatchEngine {
    val config: GameConfig

    fun start(secretOverride: String? = null): MatchSnapshot

    fun submit(rawGuess: String): MatchSnapshot

    fun snapshot(message: String? = null): MatchSnapshot

    fun grantExtraMoves(amount: Int): MatchSnapshot

    fun fail(message: String): MatchSnapshot

    fun checkPosition(digit: Int, position: Int): MatchHintOutcome

    fun openPosition(position: Int): MatchHintOutcome

    fun checkDigitCount(digit: Int): MatchHintOutcome
}
