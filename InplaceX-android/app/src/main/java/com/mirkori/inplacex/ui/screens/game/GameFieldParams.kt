package com.mirkori.inplacex.ui.screens.game

enum class TypeGame {
    RaceMatch,
    DuelMatch
}

data class GameFieldParams(
    val typeGame: TypeGame,
    val useHints: Boolean = true,
    val useBoosts: Boolean = false,
    val timeAll: Int = 0,
    val timeMove: Int = 0,
    val limitMoves: Int = 0,
    val lenSecret: Int = 6,
    val allowDuplicates: Boolean = true,
    val forbidAllSameDigitsGuess: Boolean = true,
    val forbidAdjacentDuplicates: Boolean = false,
    val forbidTripleDuplicates: Boolean = false,
    val maxConsecutiveDuplicateDigits: Int? = null,
)
