package com.mirkori.inplacex.core.model

import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.core.match.SecretSetupKind

enum class MatchModeFamily {
    RACE,
    DUEL,
}

data class BotModeSettings(
    val difficulty: BotDifficulty,
)

data class CampaignModeSettings(
    val enabled: Boolean = false,
    val maxScalingLevel: Int = 400,
    val levelsPerBlock: Int = 10,
)

data class GameModeMetadata(
    val family: MatchModeFamily = MatchModeFamily.RACE,
    val secretSetupKind: SecretSetupKind = SecretSetupKind.GENERATED,
    val preMatchRequired: Boolean = false,
    val botMode: BotModeSettings? = null,
    val campaignMode: CampaignModeSettings? = null,
)
