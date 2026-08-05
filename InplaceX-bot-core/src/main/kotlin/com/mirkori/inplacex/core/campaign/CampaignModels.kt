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
    val levelsPerChapter: Int = 10,
    val requiredStarsPerLevel: Double = 2.0,
) {
    init {
        require(levelsPerChapter > 0) { "levelsPerChapter must be > 0" }
        require(requiredStarsPerLevel > 0.0) { "requiredStarsPerLevel must be > 0" }
    }
}

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

    fun chapterForLevel(
        levelNumber: Int,
        config: CampaignUnlockConfig = unlockConfig,
    ): Int {
        require(levelNumber > 0) { "levelNumber must be > 0" }
        return ((levelNumber - 1) / config.levelsPerChapter) + 1
    }

    fun levelRange(
        chapterNumber: Int,
        config: CampaignUnlockConfig = unlockConfig,
    ): IntRange {
        require(chapterNumber > 0) { "chapterNumber must be > 0" }
        val firstLevel = (chapterNumber - 1) * config.levelsPerChapter + 1
        return firstLevel..(firstLevel + config.levelsPerChapter - 1)
    }

    fun requiredCompletedLevelsForNextBlock(
        nextBlockNumber: Int,
        config: CampaignUnlockConfig = unlockConfig,
    ): Int {
        if (nextBlockNumber <= 1) return 0
        return (nextBlockNumber - 1) * config.levelsPerChapter
    }

    fun requiredStarsForNextBlock(
        nextBlockNumber: Int,
        config: CampaignUnlockConfig = unlockConfig,
    ): Int {
        val requiredCompleted = requiredCompletedLevelsForNextBlock(nextBlockNumber, config)
        return kotlin.math.ceil(requiredCompleted * config.requiredStarsPerLevel).toInt()
    }

    fun completedLevelsForNextBlock(
        nextBlockNumber: Int,
        starsByLevel: Map<Int, Int>,
        config: CampaignUnlockConfig = unlockConfig,
    ): Int {
        val requiredCompleted = requiredCompletedLevelsForNextBlock(nextBlockNumber, config)
        return (1..requiredCompleted).count { levelNumber ->
            starsByLevel.getOrDefault(levelNumber, 0) > 0
        }
    }

    fun earnedStarsForNextBlock(
        nextBlockNumber: Int,
        starsByLevel: Map<Int, Int>,
        config: CampaignUnlockConfig = unlockConfig,
    ): Int {
        val requiredCompleted = requiredCompletedLevelsForNextBlock(nextBlockNumber, config)
        return (1..requiredCompleted).sumOf { levelNumber ->
            starsByLevel.getOrDefault(levelNumber, 0).coerceAtLeast(0)
        }
    }

    fun isBlockUnlocked(
        blockNumber: Int,
        starsByLevel: Map<Int, Int>,
        config: CampaignUnlockConfig = unlockConfig,
    ): Boolean {
        if (blockNumber <= 1) return true
        val requiredCompleted = requiredCompletedLevelsForNextBlock(blockNumber, config)
        val requiredStars = requiredStarsForNextBlock(blockNumber, config)
        return completedLevelsForNextBlock(blockNumber, starsByLevel, config) == requiredCompleted &&
            earnedStarsForNextBlock(blockNumber, starsByLevel, config) >= requiredStars
    }

    fun computeUnlockedBlock(
        starsByLevel: Map<Int, Int>,
        config: CampaignUnlockConfig = unlockConfig,
    ): Int {
        require(starsByLevel.keys.all { it > 0 }) { "level numbers must be > 0" }
        require(starsByLevel.values.all { it >= 0 }) { "stars must be >= 0" }

        var unlockedBlock = 1
        while (true) {
            val nextBlock = unlockedBlock + 1
            if (!isBlockUnlocked(nextBlock, starsByLevel, config)) break
            unlockedBlock = nextBlock
        }
        return unlockedBlock
    }
}
