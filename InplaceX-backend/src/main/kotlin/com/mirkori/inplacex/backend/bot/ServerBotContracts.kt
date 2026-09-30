package com.mirkori.inplacex.backend.bot

import com.mirkori.inplacex.core.bot.BotBehaviorModel
import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.core.bot.BotObservation
import com.mirkori.inplacex.core.bot.BotSolveStage
import com.mirkori.inplacex.core.model.GameConfig

data class ServerBotProfile(
    val botId: String,
    val displayName: String,
    val difficulty: BotDifficulty,
    val behavior: BotBehaviorModel = BotBehaviorModel.BALANCED,
)

data class ServerBotTurn(
    val guess: String,
    val moveNumber: Int,
    val reactionDelayMillis: Long,
)

data class ServerBotTurnFeedback(
    val guess: String,
    val exactMatches: Int,
    val solvedOpponentSecret: Boolean,
)

data class ServerBotDefenseResult(
    val guess: String,
    val exactMatches: Int,
    val solvedSecret: Boolean,
)

data class ServerBotSnapshot(
    val profile: ServerBotProfile,
    val config: GameConfig,
    val stage: BotSolveStage,
    val gridPlanId: String?,
    val offensiveHistory: List<BotObservation>,
    val defensiveHistory: List<ServerBotDefenseResult>,
    val pendingTurn: ServerBotTurn?,
    val solvedOpponentSecret: Boolean,
    val secretSolvedByOpponent: Boolean,
    val confirmedPositions: Int,
)
