package com.mirkori.inplacex.core.campaign

import com.mirkori.inplacex.core.model.GameConfig

enum class CampaignBlockRole {
    STANDARD,
    SPIKE,
    HARDCORE,
}

enum class CampaignDifficultyTier {
    EASY,
    MEDIUM,
    HARD,
    HARDCORE,
}

data class CampaignAssistBudget(
    val perfectHintsBudget: Int?,
    val perfectBoostsBudget: Int?,
)

data class CampaignBoostPack(
    val extraMoves: Int,
    val extraSeconds: Int,
)

data class CampaignUnlockConfig(
    val earlyBlockRequiredAverageStars: Double = 1.5,
    val lateBlockRequiredAverageStars: Double = 1.75,
    val lateBlockStartsFrom: Int = 6,
)

data class CampaignRatingPolicy(
    val maxBackendPoints: Int = 10,
    val starsMax: Int = 3,
    val targetAttemptsForPerfect: Int,
    val targetTimeSecondsForPerfect: Int,
    val assistsBudget: CampaignAssistBudget,
)

data class CampaignLevelDefinition(
    val levelNumber: Int,
    val blockNumber: Int,
    val blockRole: CampaignBlockRole,
    val difficultyTier: CampaignDifficultyTier,
    val config: GameConfig,
    val moveBudgetMultiplier: Double,
    val raceTimeLimitSeconds: Int,
    val hintsAllowed: Boolean,
    val autoModeAllowed: Boolean,
    val boostPack: CampaignBoostPack,
    val ratingPolicy: CampaignRatingPolicy,
)

object CampaignProgressionRules {
    val unlockConfig = CampaignUnlockConfig()

    fun requiredAverageStarsForBlock(blockNumber: Int): Double {
        return when {
            blockNumber <= 1 -> 0.0
            blockNumber < unlockConfig.lateBlockStartsFrom -> unlockConfig.earlyBlockRequiredAverageStars
            else -> unlockConfig.lateBlockRequiredAverageStars
        }
    }

    fun requiredStarsForNextBlock(nextBlockNumber: Int, completedLevelsCount: Int): Int {
        if (nextBlockNumber <= 1) return 0
        return kotlin.math.ceil(completedLevelsCount * requiredAverageStarsForBlock(nextBlockNumber)).toInt()
    }
}
