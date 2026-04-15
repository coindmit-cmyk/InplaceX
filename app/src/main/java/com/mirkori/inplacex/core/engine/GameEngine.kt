package com.mirkori.inplacex.core.engine

import com.mirkori.inplacex.core.match.MatchAttempt
import com.mirkori.inplacex.core.match.MatchEngine
import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.core.match.MatchSnapshot
import com.mirkori.inplacex.core.model.GameConfig

class GameEngine(
    override val config: GameConfig
) : MatchEngine {

    private var secret = ""
    private val history = mutableListOf<MatchAttempt>()
    private var phase = MatchPhase.NOT_STARTED

    override fun start(): MatchSnapshot {
        secret = SecretGenerator.generate(config)
        history.clear()
        phase = MatchPhase.ACTIVE
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
            history.size >= config.attemptLimit -> MatchPhase.LOST
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
            attemptsLeft = (config.attemptLimit - history.size).coerceAtLeast(0),
            debugSecret = secret,
            message = message,
        )
    }
}
