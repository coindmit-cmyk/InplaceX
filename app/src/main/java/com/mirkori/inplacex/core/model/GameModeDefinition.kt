package com.mirkori.inplacex.core.model

import com.mirkori.inplacex.core.match.OpponentKind

data class GameModeDefinition(
    val id: String,
    val titleKey: String,
    val subtitleKey: String,
    val config: GameConfig,
    val opponentKind: OpponentKind,
    val hintsEnabled: Boolean,
    val totalTimeLimitSeconds: Int? = null,
    val turnTimeLimitSeconds: Int? = null,
)
