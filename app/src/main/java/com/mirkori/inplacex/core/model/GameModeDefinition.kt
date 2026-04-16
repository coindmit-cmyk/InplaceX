package com.mirkori.inplacex.core.model

import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.core.match.OpponentKind
import com.mirkori.inplacex.core.match.PreMatchConfig

enum class GameModeFamily {
    RACE,
    DUEL,
    CAMPAIGN_RACE,
}

data class GameModeDefinition(
    val id: String,
    val titleKey: String,
    val subtitleKey: String,
    val family: GameModeFamily = GameModeFamily.RACE,
    val config: GameConfig,
    val opponentKind: OpponentKind,
    val hintsEnabled: Boolean,
    val totalTimeLimitSeconds: Int? = null,
    val turnTimeLimitSeconds: Int? = null,
    val preMatchConfig: PreMatchConfig? = null,
    val botDifficulty: BotDifficulty? = null,
    val campaignLevelNumber: Int? = null,
)
