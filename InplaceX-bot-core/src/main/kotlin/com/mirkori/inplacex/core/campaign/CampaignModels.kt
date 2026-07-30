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

data class CampaignPerformance(
    val won: Boolean,
    val attemptsUsed: Int,
    val elapsedSeconds: Int,
    val hintUses: Int,
    val boostUses: Int,
)

/**
 * Воспроизводимый эталон эффективного решателя для длин, используемых кампанией.
 *
 * Значения округлены вверх по детерминированной benchmark-матрице и отделены
 * от профиля противника: баланс кампании не должен меняться из-за анимации или
 * характера бота.
 */
object CampaignSolverBudget {
    private val expertReferenceAttempts = mapOf(
        4 to 13,
        5 to 14,
        6 to 15,
        7 to 17,
        8 to 19,
        9 to 21,
        10 to 24,
    )

    fun expertReferenceAttempts(codeLength: Int): Int =
        requireNotNull(expertReferenceAttempts[codeLength]) {
            "Campaign codeLength must be in 4..10: $codeLength"
        }
}

object CampaignRatingCalculator {
    fun rate(level: CampaignLevelDefinition, performance: CampaignPerformance): Int {
        if (!performance.won) return 0

        var score = level.ratingPolicy.maxBackendPoints.toDouble()
        val targetAttempts = level.ratingPolicy.targetAttemptsForPerfect
        val attemptReserve = (level.config.attemptLimit - targetAttempts).coerceAtLeast(1)
        val reserveSpent = (performance.attemptsUsed - targetAttempts).coerceAtLeast(0)
        val maximumAttemptPenalty = level.ratingPolicy.maxBackendPoints - 1
        score -= kotlin.math.ceil(
            reserveSpent.toDouble() * maximumAttemptPenalty.toDouble() / attemptReserve.toDouble(),
        )
        score -= kotlin.math.ceil(
            ((performance.elapsedSeconds - level.ratingPolicy.targetTimeSecondsForPerfect)
                .coerceAtLeast(0)) / 30.0,
        )

        level.ratingPolicy.assistsBudget.perfectHintsBudget?.let { hintsBudget ->
            score -= (performance.hintUses - hintsBudget).coerceAtLeast(0)
        }
        level.ratingPolicy.assistsBudget.perfectBoostsBudget?.let { boostsBudget ->
            score -= (performance.boostUses - boostsBudget).coerceAtLeast(0)
        }

        return score.toInt().coerceIn(1, level.ratingPolicy.maxBackendPoints)
    }

    fun starsForRating(rating: Int): Int = when {
        rating >= 8 -> 3
        rating >= 4 -> 2
        rating >= 1 -> 1
        else -> 0
    }
}

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

    fun computeUnlockedBlock(completedLevelsCount: Int, totalStars: Int): Int {
        require(completedLevelsCount >= 0)
        require(totalStars >= 0)

        var unlockedBlock = 1
        while (true) {
            val nextBlock = unlockedBlock + 1
            val requiredCompleted = unlockedBlock * 10
            val requiredStars = requiredStarsForNextBlock(nextBlock, requiredCompleted)
            if (completedLevelsCount < requiredCompleted || totalStars < requiredStars) break
            unlockedBlock = nextBlock
        }
        return unlockedBlock
    }
}
