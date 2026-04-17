package com.mirkori.inplacex.backend.bot

import com.mirkori.inplacex.core.bot.BotBehaviorModel
import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.core.bot.BotProfiles
import com.mirkori.inplacex.core.bot.BotSolver
import com.mirkori.inplacex.core.engine.GuessValidator
import com.mirkori.inplacex.core.engine.ScoreCalculator
import com.mirkori.inplacex.core.engine.SecretGenerator
import com.mirkori.inplacex.core.model.GameConfig

class ServerBotPlayer private constructor(
    val profile: ServerBotProfile,
    val config: GameConfig,
    private val hiddenSecret: String,
    brainSeed: Long,
) {
    private val solver = BotSolver(
        config = config,
        difficulty = profile.difficulty,
        behavior = profile.behavior,
        seed = brainSeed,
    )
    private val defenseHistory = mutableListOf<ServerBotDefenseResult>()
    private var pendingTurn: ServerBotTurn? = null
    private var solvedOpponentSecret = false
    private var secretSolvedByOpponent = false

    fun nextTurnOrNull(): ServerBotTurn? {
        if (solvedOpponentSecret || secretSolvedByOpponent) return null
        pendingTurn?.let { return it }

        val moveNumber = solver.snapshot().history.size + 1
        val guess = solver.nextTurn().guess
        val turn = ServerBotTurn(
            guess = guess,
            moveNumber = moveNumber,
            reactionDelayMillis = BotProfiles.forDifficulty(profile.difficulty).reactionDelayMillis,
        )
        pendingTurn = turn
        return turn
    }

    fun nextTurn(): ServerBotTurn {
        return nextTurnOrNull() ?: error("Server bot cannot make a new turn because the match side is already finished")
    }

    fun registerTurnFeedback(
        guess: String,
        exactMatches: Int,
    ): ServerBotTurnFeedback {
        require(exactMatches in 0..config.codeLength) { "exactMatches must be in 0..${config.codeLength}" }
        val pending = pendingTurn ?: error("Server bot has no pending offensive turn to score")
        require(pending.guess == guess) { "Unexpected bot guess feedback: $guess" }

        solver.registerFeedback(guess, exactMatches)
        pendingTurn = null
        solvedOpponentSecret = exactMatches == config.codeLength

        return ServerBotTurnFeedback(
            guess = guess,
            exactMatches = exactMatches,
            solvedOpponentSecret = solvedOpponentSecret,
        )
    }

    fun scoreIncomingGuess(guess: String): ServerBotDefenseResult {
        require(GuessValidator.validate(guess, config)) { "Incoming guess does not match active rules: $guess" }
        val exactMatches = ScoreCalculator.countExactMatches(hiddenSecret, guess)
        val result = ServerBotDefenseResult(
            guess = guess,
            exactMatches = exactMatches,
            solvedSecret = exactMatches == config.codeLength,
        )
        defenseHistory += result
        if (result.solvedSecret) {
            secretSolvedByOpponent = true
        }
        return result
    }

    fun revealSecret(): String = hiddenSecret

    fun snapshot(): ServerBotSnapshot {
        val state = solver.snapshot()
        return ServerBotSnapshot(
            profile = profile,
            config = config,
            stage = state.stage,
            gridPlanId = state.gridPlanId,
            offensiveHistory = state.history,
            defensiveHistory = defenseHistory.toList(),
            pendingTurn = pendingTurn,
            solvedOpponentSecret = solvedOpponentSecret,
            secretSolvedByOpponent = secretSolvedByOpponent,
            confirmedPositions = solver.confirmedPositionsCount(),
        )
    }

    companion object {
        fun create(
            config: GameConfig,
            difficulty: BotDifficulty,
            behavior: BotBehaviorModel = BotBehaviorModel.BALANCED,
            botId: String = defaultBotId(difficulty),
            displayName: String = defaultDisplayName(difficulty),
            secret: String? = null,
            secretSeed: Long = 0L,
            brainSeed: Long = 0L,
        ): ServerBotPlayer {
            val resolvedSecret = secret ?: SecretGenerator.generate(config.copy(seed = secretSeed))
            require(GuessValidator.validate(resolvedSecret, config)) {
                "Server bot secret does not match active rules: $resolvedSecret"
            }

            return ServerBotPlayer(
                profile = ServerBotProfile(
                    botId = botId,
                    displayName = displayName,
                    difficulty = difficulty,
                    behavior = behavior,
                ),
                config = config,
                hiddenSecret = resolvedSecret,
                brainSeed = brainSeed,
            )
        }

        private fun defaultBotId(difficulty: BotDifficulty): String {
            return "server-bot-${difficulty.name.lowercase()}"
        }

        private fun defaultDisplayName(difficulty: BotDifficulty): String {
            return "${difficulty.name.lowercase().replaceFirstChar(Char::titlecase)} Bot"
        }
    }
}
