package com.mirkori.inplacex.backend.bot

import com.mirkori.inplacex.core.bot.BotBehaviorModel
import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.core.bot.BotProfiles
import com.mirkori.inplacex.core.bot.BotSolver
import com.mirkori.inplacex.core.engine.GuessValidator
import com.mirkori.inplacex.core.engine.ScoreCalculator
import com.mirkori.inplacex.core.model.GameConfig
import java.security.SecureRandom

class ServerBotPlayer internal constructor(
    val profile: ServerBotProfile,
    val config: GameConfig,
    private val hiddenSecret: String,
    private val solver: BotSolver,
) {
    init {
        require(GuessValidator.validate(hiddenSecret, config)) {
            "Server bot secret does not match active rules"
        }
    }

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
        ): ServerBotPlayer {
            return ServerBotPlayer(
                profile = ServerBotProfile(
                    botId = botId,
                    displayName = displayName,
                    difficulty = difficulty,
                    behavior = behavior,
                ),
                config = config,
                hiddenSecret = ProductionServerBotEntropy.generateSecret(config),
                solver = BotSolver(
                    config = config,
                    difficulty = difficulty,
                    behavior = behavior,
                    seed = ProductionServerBotEntropy.nextBrainSeed(),
                ),
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

internal object ProductionServerBotEntropy {
    private val secureRandom = SecureRandom()

    fun generateSecret(config: GameConfig): String {
        repeat(MAX_SECRET_GENERATION_ATTEMPTS) {
            val candidate = if (config.allowDuplicates) {
                buildString(config.codeLength) {
                    repeat(config.codeLength) {
                        append(secureRandom.nextInt(DIGIT_ALPHABET_SIZE))
                    }
                }
            } else {
                ('0'..'9').toMutableList().also { digits ->
                    for (index in digits.lastIndex downTo 1) {
                        val replacement = secureRandom.nextInt(index + 1)
                        val current = digits[index]
                        digits[index] = digits[replacement]
                        digits[replacement] = current
                    }
                }.take(config.codeLength).joinToString("")
            }

            if (GuessValidator.validate(candidate, config)) {
                return candidate
            }
        }

        error("Unable to generate a secure server bot secret that matches the active rules")
    }

    fun nextBrainSeed(): Long = secureRandom.nextLong()
}

private const val DIGIT_ALPHABET_SIZE = 10
private const val MAX_SECRET_GENERATION_ATTEMPTS = 20_000
