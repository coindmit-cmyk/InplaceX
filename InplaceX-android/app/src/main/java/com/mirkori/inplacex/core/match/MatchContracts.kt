package com.mirkori.inplacex.core.match

import com.mirkori.inplacex.core.engine.GuessValidationReason
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

enum class MatchActionRejection {
    MATCH_NOT_ACTIVE,
    INVALID_HINT_DIGIT,
    INVALID_HINT_POSITION,
}

sealed interface MatchFeedback {
    data class ValidationRejected(
        val reason: GuessValidationReason,
    ) : MatchFeedback

    data class MatchFinished(
        val phase: MatchPhase,
    ) : MatchFeedback

    data class ExtraMovesGranted(
        val amount: Int,
    ) : MatchFeedback

    data class ActionRejected(
        val reason: MatchActionRejection,
    ) : MatchFeedback
}

data class MatchSnapshot(
    val phase: MatchPhase,
    val attempts: List<MatchAttempt>,
    val attemptsLeft: Int,
    val debugSecret: String,
    val message: String? = null,
    val feedback: MatchFeedback? = null,
)

/**
 * Полный доменный снимок, необходимый для восстановления матча без генерации
 * нового секрета. Секрет хранится только как часть состояния, не логируется.
 */
data class MatchCheckpoint(
    val secret: String,
    val phase: MatchPhase,
    val attempts: List<MatchAttempt>,
    val extraAttemptBudget: Int,
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

    fun checkpoint(): MatchCheckpoint

    fun restoreCheckpoint(checkpoint: MatchCheckpoint): Boolean

    fun grantExtraMoves(amount: Int): MatchSnapshot

    fun fail(message: String): MatchSnapshot

    fun checkPosition(digit: Int, position: Int): MatchHintOutcome

    fun openPosition(position: Int): MatchHintOutcome

    fun checkDigitCount(digit: Int): MatchHintOutcome
}
