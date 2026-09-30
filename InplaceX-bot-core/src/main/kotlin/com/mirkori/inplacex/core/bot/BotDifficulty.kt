package com.mirkori.inplacex.core.bot

import kotlin.math.ceil

enum class BotDifficulty {
    EASY,
    MEDIUM,
    HARD,
    EXPERT,
}

data class BotDifficultyProfile(
    val difficulty: BotDifficulty,
    val targetMovesMultiplier: Double,
    val minimumMoves: Int,
    val reactionDelayMillis: Long,
    val randomGuessShare: Double,
    val usesCandidatePruning: Boolean,
    val usesBestSplitSelection: Boolean,
    val expertBonusMoves: Int = 0,
) {
    fun targetMovesForCodeLength(codeLength: Int): Int {
        val baseline = ceil(codeLength * targetMovesMultiplier).toInt()
        return maxOf(minimumMoves, baseline + expertBonusMoves)
    }
}

object BotProfiles {
    val easy = BotDifficultyProfile(
        difficulty = BotDifficulty.EASY,
        targetMovesMultiplier = 4.5,
        minimumMoves = 8,
        reactionDelayMillis = 1600,
        randomGuessShare = 0.75,
        usesCandidatePruning = false,
        usesBestSplitSelection = false,
    )

    val medium = BotDifficultyProfile(
        difficulty = BotDifficulty.MEDIUM,
        targetMovesMultiplier = 3.5,
        minimumMoves = 7,
        reactionDelayMillis = 1200,
        randomGuessShare = 0.35,
        usesCandidatePruning = true,
        usesBestSplitSelection = false,
    )

    val hard = BotDifficultyProfile(
        difficulty = BotDifficulty.HARD,
        targetMovesMultiplier = 2.5,
        minimumMoves = 6,
        reactionDelayMillis = 900,
        randomGuessShare = 0.1,
        usesCandidatePruning = true,
        usesBestSplitSelection = true,
    )

    val expert = BotDifficultyProfile(
        difficulty = BotDifficulty.EXPERT,
        targetMovesMultiplier = 2.0,
        minimumMoves = 6,
        reactionDelayMillis = 700,
        randomGuessShare = 0.0,
        usesCandidatePruning = true,
        usesBestSplitSelection = true,
        expertBonusMoves = 1,
    )

    fun forDifficulty(difficulty: BotDifficulty): BotDifficultyProfile {
        return when (difficulty) {
            BotDifficulty.EASY -> easy
            BotDifficulty.MEDIUM -> medium
            BotDifficulty.HARD -> hard
            BotDifficulty.EXPERT -> expert
        }
    }
}
