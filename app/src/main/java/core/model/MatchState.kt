package com.mirkori.inplacex.core.model

enum class GameStatus {
    IN_PROGRESS,
    WON,
    LOST
}

data class MatchState(
    val config: GameConfig,
    val secret: String,
    val attempts: List<GuessResult> = emptyList(),
    val status: GameStatus = GameStatus.IN_PROGRESS
)