package com.mirkori.inplacex.core.bot

import com.mirkori.inplacex.core.model.GameConfig

data class BotObservation(
    val guess: String,
    val score: Int,
)

data class BotTurnDecision(
    val guess: String,
    val candidatesLeft: Int,
)

data class BotSimulationRun(
    val secret: String,
    val difficulty: BotDifficulty,
    val behavior: BotBehaviorModel,
    val won: Boolean,
    val moves: Int,
    val targetMoves: Int,
)

data class BotSolverState(
    val config: GameConfig,
    val difficulty: BotDifficulty,
    val profile: BotDifficultyProfile,
    val behavior: BotBehaviorModel,
    val behaviorProfile: BotBehaviorProfile,
    val history: List<BotObservation>,
    val stage: BotSolveStage,
    val gridPlanId: String?,
    val candidates: List<Set<Char>>,
    val resolvedPositions: Map<Int, Char>,
)
