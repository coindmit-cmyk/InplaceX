package com.mirkori.inplacex.core.engine

import com.mirkori.inplacex.core.match.MatchAttempt
import com.mirkori.inplacex.core.match.MatchActionRejection
import com.mirkori.inplacex.core.match.MatchCheckpoint
import com.mirkori.inplacex.core.match.MatchEngine
import com.mirkori.inplacex.core.match.MatchHintOutcome
import com.mirkori.inplacex.core.match.MatchHintResult
import com.mirkori.inplacex.core.match.MatchFeedback
import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.core.match.MatchSnapshot
import com.mirkori.inplacex.core.model.GameConfig

class GameEngine(
    override val config: GameConfig
) : MatchEngine {

    private var secret = ""
    private val history = mutableListOf<MatchAttempt>()
    private var phase = MatchPhase.NOT_STARTED
    private var extraAttemptBudget = 0

    override fun start(secretOverride: String?): MatchSnapshot {
        secret = secretOverride
            ?.takeIf { GuessValidator.validate(it, config) }
            ?: SecretGenerator.generate(config)
        history.clear()
        phase = MatchPhase.ACTIVE
        extraAttemptBudget = 0
        return snapshot()
    }

    override fun submit(rawGuess: String): MatchSnapshot {
        if (phase != MatchPhase.ACTIVE) {
            return snapshot(
                message = "Игра уже завершена",
                feedback = MatchFeedback.MatchFinished(phase),
            )
        }

        val guess = rawGuess.trim()
        val validationReason = GuessValidator.validateOrReason(guess, config)
        if (validationReason != null) {
            return snapshot(
                message = GuessValidator.validateOrMessage(guess, config),
                feedback = MatchFeedback.ValidationRejected(validationReason),
            )
        }

        val score = ScoreCalculator.countExactMatches(secret, guess)
        val isWin = score == config.codeLength

        history += MatchAttempt(
            guess = guess,
            score = score,
            number = history.size + 1,
            isWin = isWin,
        )

        phase = when {
            isWin -> MatchPhase.WON
            history.size >= config.attemptLimit + extraAttemptBudget -> MatchPhase.LOST
            else -> MatchPhase.ACTIVE
        }

        val message = when (phase) {
            MatchPhase.WON -> "Победа"
            MatchPhase.LOST -> "Попытки закончились"
            else -> null
        }

        return snapshot(
            message = message,
            feedback = if (phase == MatchPhase.ACTIVE) {
                null
            } else {
                MatchFeedback.MatchFinished(phase)
            },
        )
    }

    override fun snapshot(message: String?): MatchSnapshot {
        return snapshot(message = message, feedback = null)
    }

    private fun snapshot(message: String?, feedback: MatchFeedback?): MatchSnapshot {
        return MatchSnapshot(
            phase = phase,
            attempts = history.toList(),
            attemptsLeft = (config.attemptLimit + extraAttemptBudget - history.size).coerceAtLeast(0),
            debugSecret = secret,
            message = message,
            feedback = feedback,
        )
    }

    override fun checkpoint(): MatchCheckpoint {
        return MatchCheckpoint(
            secret = secret,
            phase = phase,
            attempts = history.toList(),
            extraAttemptBudget = extraAttemptBudget,
        )
    }

    override fun restoreCheckpoint(checkpoint: MatchCheckpoint): Boolean {
        if (!isValidCheckpoint(checkpoint)) {
            return false
        }

        secret = checkpoint.secret
        history.clear()
        history += checkpoint.attempts
        phase = checkpoint.phase
        extraAttemptBudget = checkpoint.extraAttemptBudget
        return true
    }

    override fun grantExtraMoves(amount: Int): MatchSnapshot {
        require(amount > 0) { "amount must be > 0" }
        if (phase != MatchPhase.ACTIVE) {
            return snapshot(
                message = "Бустер недоступен вне активной игры",
                feedback = MatchFeedback.ActionRejected(MatchActionRejection.MATCH_NOT_ACTIVE),
            )
        }
        extraAttemptBudget += amount
        return snapshot(
            message = "Добавлено ходов: $amount",
            feedback = MatchFeedback.ExtraMovesGranted(amount),
        )
    }

    override fun fail(message: String): MatchSnapshot {
        if (phase == MatchPhase.ACTIVE) {
            phase = MatchPhase.LOST
        }
        return snapshot(
            message = message,
            feedback = MatchFeedback.MatchFinished(phase),
        )
    }

    override fun checkPosition(digit: Int, position: Int): MatchHintOutcome {
        if (phase != MatchPhase.ACTIVE) {
            return MatchHintOutcome(
                snapshot = snapshot(),
                message = "Подсказка недоступна вне активной игры",
            )
        }

        if (digit !in 0..9 || position !in 0 until config.codeLength) {
            return MatchHintOutcome(
                snapshot = snapshot(),
                message = "Некорректная позиция подсказки",
            )
        }

        return MatchHintOutcome(
            snapshot = snapshot(),
            result = MatchHintResult.PositionChecked(
                digit = digit,
                position = position,
                isMatch = secret[position].digitToInt() == digit,
            ),
        )
    }

    override fun openPosition(position: Int): MatchHintOutcome {
        if (phase != MatchPhase.ACTIVE) {
            return MatchHintOutcome(
                snapshot = snapshot(),
                message = "Подсказка недоступна вне активной игры",
            )
        }

        if (position !in 0 until config.codeLength) {
            return MatchHintOutcome(
                snapshot = snapshot(),
                message = "Некорректная позиция подсказки",
            )
        }

        return MatchHintOutcome(
            snapshot = snapshot(),
            result = MatchHintResult.PositionOpened(
                position = position,
                digit = secret[position].digitToInt(),
            ),
        )
    }

    override fun checkDigitCount(digit: Int): MatchHintOutcome {
        if (phase != MatchPhase.ACTIVE) {
            return MatchHintOutcome(
                snapshot = snapshot(),
                message = "Подсказка недоступна вне активной игры",
            )
        }

        if (digit !in 0..9) {
            return MatchHintOutcome(
                snapshot = snapshot(),
                message = "Некорректная цифра подсказки",
            )
        }

        return MatchHintOutcome(
            snapshot = snapshot(),
            result = MatchHintResult.DigitCountChecked(
                digit = digit,
                count = secret.count { it.digitToInt() == digit },
            ),
        )
    }

    private fun isValidCheckpoint(checkpoint: MatchCheckpoint): Boolean {
        if (checkpoint.extraAttemptBudget < 0) {
            return false
        }

        if (checkpoint.phase == MatchPhase.NOT_STARTED) {
            return checkpoint.secret.isEmpty() &&
                checkpoint.attempts.isEmpty() &&
                checkpoint.extraAttemptBudget == 0
        }

        if (!GuessValidator.validate(checkpoint.secret, config)) {
            return false
        }

        val maximumAttempts = config.attemptLimit + checkpoint.extraAttemptBudget
        if (checkpoint.attempts.size > maximumAttempts) {
            return false
        }

        checkpoint.attempts.forEachIndexed { index, attempt ->
            if (attempt.number != index + 1 ||
                GuessValidator.validateOrReason(attempt.guess, config) != null ||
                attempt.score !in 0..config.codeLength ||
                attempt.score != ScoreCalculator.countExactMatches(checkpoint.secret, attempt.guess) ||
                attempt.isWin != (attempt.score == config.codeLength)
            ) {
                return false
            }
        }

        val winningAttemptIndexes = checkpoint.attempts
            .mapIndexedNotNull { index, attempt -> index.takeIf { attempt.isWin } }
        return when (checkpoint.phase) {
            MatchPhase.ACTIVE -> checkpoint.attempts.size < maximumAttempts &&
                winningAttemptIndexes.isEmpty()
            MatchPhase.WON -> winningAttemptIndexes == listOf(checkpoint.attempts.lastIndex)
            MatchPhase.LOST -> winningAttemptIndexes.isEmpty()
            MatchPhase.NOT_STARTED -> false
        }
    }
}
