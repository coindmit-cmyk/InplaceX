package com.mirkori.inplacex.core.engine

import com.mirkori.inplacex.core.match.MatchAttempt
import com.mirkori.inplacex.core.match.MatchEngine
import com.mirkori.inplacex.core.match.MatchHintOutcome
import com.mirkori.inplacex.core.match.MatchHintResult
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
        secret = secretOverride ?: SecretGenerator.generate(config)
        history.clear()
        phase = MatchPhase.ACTIVE
        extraAttemptBudget = 0
        return snapshot()
    }

    override fun submit(rawGuess: String): MatchSnapshot {
        if (phase != MatchPhase.ACTIVE) {
            return snapshot("Игра уже завершена")
        }

        val guess = rawGuess.trim()
        val error = GuessValidator.validateOrMessage(guess, config)
        if (error != null) {
            return snapshot(error)
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

        return snapshot(message)
    }

    override fun snapshot(message: String?): MatchSnapshot {
        return MatchSnapshot(
            phase = phase,
            attempts = history.toList(),
            attemptsLeft = (config.attemptLimit + extraAttemptBudget - history.size).coerceAtLeast(0),
            debugSecret = secret,
            message = message,
        )
    }

    override fun grantExtraMoves(amount: Int): MatchSnapshot {
        require(amount > 0) { "amount must be > 0" }
        if (phase != MatchPhase.ACTIVE) {
            return snapshot("Р‘СѓСЃС‚РµСЂ РЅРµРґРѕСЃС‚СѓРїРµРЅ РІРЅРµ Р°РєС‚РёРІРЅРѕР№ РёРіСЂС‹")
        }
        extraAttemptBudget += amount
        return snapshot("Р”РѕР±Р°РІР»РµРЅРѕ С…РѕРґРѕРІ: $amount")
    }

    override fun fail(message: String): MatchSnapshot {
        if (phase == MatchPhase.ACTIVE) {
            phase = MatchPhase.LOST
        }
        return snapshot(message)
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
}
