package com.mirkori.inplacex.core.bot

import com.mirkori.inplacex.core.engine.ScoreCalculator
import com.mirkori.inplacex.core.model.GameConfig

class BotSolver(
    private val config: GameConfig,
    difficulty: BotDifficulty,
    private val behavior: BotBehaviorModel = BotBehaviorModel.BALANCED,
    seed: Long = 0L,
) {
    private val profile = BotProfiles.forDifficulty(difficulty)
    private val behaviorProfile = BotBehaviorProfiles.forModel(behavior)
    private val agent = BotAgent(
        rules = config.toBotMatchRules(),
        difficulty = difficulty,
        behavior = behavior,
        seed = seed,
    )

    fun snapshot(): BotSolverState {
        val state = agent.snapshot()
        return BotSolverState(
            config = config,
            difficulty = difficulty(),
            profile = profile,
            behavior = behavior,
            behaviorProfile = behaviorProfile,
            history = state.history,
            stage = state.stage,
            gridPlanId = state.gridPlanId,
            candidates = state.candidates,
            resolvedPositions = state.resolvedPositions,
        )
    }

    fun nextTurn(): BotTurnDecision {
        val guess = agent.nextGuess()
        val state = agent.snapshot()
        return BotTurnDecision(
            guess = guess,
            candidatesLeft = state.candidates.sumOf { it.size },
        )
    }

    fun registerFeedback(guess: String, score: Int) {
        agent.registerFeedback(guess, score)
    }

    fun confirmedPositionsCount(): Int {
        return agent.confirmedPositionsCount()
    }

    private fun difficulty(): BotDifficulty = profile.difficulty

    companion object {
        fun solveSecret(
            secret: String,
            config: GameConfig,
            difficulty: BotDifficulty,
            behavior: BotBehaviorModel = BotBehaviorModel.BALANCED,
            seed: Long = 0L,
            maxMoves: Int = BotProfiles.forDifficulty(difficulty).targetMovesForCodeLength(config.codeLength) + 6,
        ): BotSimulationRun {
            val solver = BotSolver(
                config = config,
                difficulty = difficulty,
                behavior = behavior,
                seed = seed,
            )
            var moves = 0

            while (moves < maxMoves) {
                val turn = solver.nextTurn()
                val score = ScoreCalculator.countExactMatches(secret, turn.guess)
                moves += 1
                solver.registerFeedback(turn.guess, score)
                if (score == config.codeLength) {
                    return BotSimulationRun(
                        secret = secret,
                        difficulty = difficulty,
                        behavior = behavior,
                        won = true,
                        moves = moves,
                        targetMoves = BotProfiles.forDifficulty(difficulty).targetMovesForCodeLength(config.codeLength),
                    )
                }
            }

            return BotSimulationRun(
                secret = secret,
                difficulty = difficulty,
                behavior = behavior,
                won = false,
                moves = moves,
                targetMoves = BotProfiles.forDifficulty(difficulty).targetMovesForCodeLength(config.codeLength),
            )
        }
    }
}
