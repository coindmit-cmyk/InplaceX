package com.mirkori.inplacex.ui.screens.game

data class MatchSessionSummary(
    val won: Boolean,
    val attemptsUsed: Int,
    val elapsedSeconds: Int,
    val hintUses: Int,
    val boostUses: Int,
    val openPositionHintUses: Int,
    val checkDigitHintUses: Int,
    val checkPositionHintUses: Int,
    val extraMovesBoostUses: Int,
    val extraTimeBoostUses: Int,
)
