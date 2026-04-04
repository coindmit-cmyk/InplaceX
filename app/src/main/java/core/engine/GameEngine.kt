package com.mirkori.inplacex.core.engine

import com.mirkori.inplacex.core.model.*

object GameEngine {

    fun createNewGame(config: GameConfig): MatchState {
        val secret = SecretGenerator.generate(config)

        return MatchState(
            config = config,
            secret = secret
        )
    }

    fun makeGuess(state: MatchState, guess: String): MatchState {

        if (state.status != GameStatus.IN_PROGRESS) return state

        if (!GuessValidator.validate(guess, state.config)) return state

        val matches = ScoreCalculator.countExactMatches(state.secret, guess)

        val result = GuessResult(
            guess = guess,
            exactMatches = matches
        )

        val newAttempts = state.attempts + result

        val newStatus = when {
            matches == state.config.codeLength -> GameStatus.WON
            newAttempts.size >= state.config.attemptLimit -> GameStatus.LOST
            else -> GameStatus.IN_PROGRESS
        }

        return state.copy(
            attempts = newAttempts,
            status = newStatus
        )
    }
}