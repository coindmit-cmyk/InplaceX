package com.mirkori.inplacex.core.campaign

import com.mirkori.inplacex.core.model.GameConfig
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object CampaignLevelGenerator {

    fun generate(levelNumber: Int): CampaignLevelDefinition {
        require(levelNumber > 0) { "levelNumber must be > 0" }

        val blockNumber = ((levelNumber - 1) / 10) + 1
        val positionInBlock = ((levelNumber - 1) % 10) + 1
        val difficultyTier = tierForLevel(levelNumber)
        val blockRole = roleForPosition(positionInBlock)
        val codeLength = codeLengthForLevel(levelNumber)
        val generatedMultiplier = multiplierFor(difficultyTier, blockRole, levelNumber)
        val onboardingAttemptLimit = onboardingAttemptLimit(levelNumber)
        val attemptLimit = onboardingAttemptLimit
            ?: ceil(codeLength * generatedMultiplier).toInt()
        val moveMultiplier = onboardingAttemptLimit
            ?.let { it.toDouble() / codeLength.toDouble() }
            ?: generatedMultiplier
        val raceTimeLimitSeconds = timeLimitFor(codeLength, difficultyTier, blockRole, levelNumber)
        val boostPack = boostPackFor(difficultyTier)
        val targetAttempts = max(1, attemptLimit - attemptSlackFor(difficultyTier, blockRole))
        val targetTime = max(30, raceTimeLimitSeconds - timeSlackFor(difficultyTier, blockRole))

        return CampaignLevelDefinition(
            levelNumber = levelNumber,
            blockNumber = blockNumber,
            blockRole = blockRole,
            difficultyTier = difficultyTier,
            config = GameConfig(
                codeLength = codeLength,
                allowDuplicates = true,
                attemptLimit = attemptLimit,
                turnTimeLimitSeconds = null,
            ),
            moveBudgetMultiplier = moveMultiplier,
            raceTimeLimitSeconds = raceTimeLimitSeconds,
            hintsAllowed = true,
            autoModeAllowed = true,
            boostPack = boostPack,
            ratingPolicy = CampaignRatingPolicy(
                targetAttemptsForPerfect = targetAttempts,
                targetTimeSecondsForPerfect = targetTime,
                assistsBudget = assistBudgetFor(difficultyTier),
            ),
        )
    }

    private fun tierForLevel(levelNumber: Int): CampaignDifficultyTier {
        return when {
            levelNumber <= 10 -> CampaignDifficultyTier.EASY
            levelNumber <= 80 -> CampaignDifficultyTier.MEDIUM
            levelNumber <= 220 -> CampaignDifficultyTier.HARD
            else -> CampaignDifficultyTier.HARDCORE
        }
    }

    private fun roleForPosition(positionInBlock: Int): CampaignBlockRole {
        return when (positionInBlock) {
            5 -> CampaignBlockRole.SPIKE
            10 -> CampaignBlockRole.HARDCORE
            else -> CampaignBlockRole.STANDARD
        }
    }

    private fun codeLengthForLevel(levelNumber: Int): Int {
        return when {
            levelNumber <= 10 -> 4
            levelNumber <= 40 -> 5
            levelNumber <= 90 -> 6
            levelNumber <= 150 -> 7
            levelNumber <= 220 -> 8
            levelNumber <= 300 -> 9
            else -> 10
        }
    }

    private fun multiplierFor(
        tier: CampaignDifficultyTier,
        role: CampaignBlockRole,
        levelNumber: Int,
    ): Double {
        val roleDelta = when (role) {
            CampaignBlockRole.STANDARD -> 0.0
            CampaignBlockRole.SPIKE -> -0.35
            CampaignBlockRole.HARDCORE -> -0.55
        }
        val tierBase = when (tier) {
            CampaignDifficultyTier.EASY -> 3.4
            CampaignDifficultyTier.MEDIUM -> 2.8
            CampaignDifficultyTier.HARD -> 2.5
            CampaignDifficultyTier.HARDCORE -> 2.2
        }
        val longRunDelta = min(0.9, ((levelNumber - 1) / 120.0) * 0.2)
        return max(1.8, tierBase + roleDelta - longRunDelta)
    }

    private fun timeLimitFor(
        codeLength: Int,
        tier: CampaignDifficultyTier,
        role: CampaignBlockRole,
        levelNumber: Int,
    ): Int {
        val rolePenalty = when (role) {
            CampaignBlockRole.STANDARD -> 0
            CampaignBlockRole.SPIKE -> 15
            CampaignBlockRole.HARDCORE -> 30
        }
        onboardingTimeLimitSeconds(levelNumber)?.let { return it }

        val tierBase = when (tier) {
            CampaignDifficultyTier.EASY -> 270
            CampaignDifficultyTier.MEDIUM -> 150
            CampaignDifficultyTier.HARD -> 130
            CampaignDifficultyTier.HARDCORE -> 110
        }
        val onboardingBonus = when {
            levelNumber <= 25 -> 30
            levelNumber <= 50 -> 15
            else -> 0
        }
        return max(45, tierBase + codeLength * 18 - rolePenalty + onboardingBonus)
    }

    private fun onboardingAttemptLimit(levelNumber: Int): Int? =
        OnboardingAttemptLimits.getOrNull(levelNumber - 1)

    private fun onboardingTimeLimitSeconds(levelNumber: Int): Int? =
        OnboardingTimeLimitsSeconds.getOrNull(levelNumber - 1)

    private fun boostPackFor(tier: CampaignDifficultyTier): CampaignBoostPack {
        return when (tier) {
            CampaignDifficultyTier.EASY -> CampaignBoostPack(extraMoves = 3, extraSeconds = 120)
            CampaignDifficultyTier.MEDIUM -> CampaignBoostPack(extraMoves = 2, extraSeconds = 60)
            CampaignDifficultyTier.HARD -> CampaignBoostPack(extraMoves = 2, extraSeconds = 60)
            CampaignDifficultyTier.HARDCORE -> CampaignBoostPack(extraMoves = 1, extraSeconds = 30)
        }
    }

    private fun assistBudgetFor(tier: CampaignDifficultyTier): CampaignAssistBudget {
        return when (tier) {
            CampaignDifficultyTier.EASY -> CampaignAssistBudget(perfectHintsBudget = null, perfectBoostsBudget = null)
            CampaignDifficultyTier.MEDIUM -> CampaignAssistBudget(perfectHintsBudget = 8, perfectBoostsBudget = 2)
            CampaignDifficultyTier.HARD -> CampaignAssistBudget(perfectHintsBudget = 6, perfectBoostsBudget = 2)
            CampaignDifficultyTier.HARDCORE -> CampaignAssistBudget(perfectHintsBudget = 3, perfectBoostsBudget = 1)
        }
    }

    private fun attemptSlackFor(
        tier: CampaignDifficultyTier,
        role: CampaignBlockRole,
    ): Int {
        val tierSlack = when (tier) {
            CampaignDifficultyTier.EASY -> 1
            CampaignDifficultyTier.MEDIUM -> 1
            CampaignDifficultyTier.HARD -> 2
            CampaignDifficultyTier.HARDCORE -> 2
        }
        val roleSlack = when (role) {
            CampaignBlockRole.STANDARD -> 0
            CampaignBlockRole.SPIKE -> 1
            CampaignBlockRole.HARDCORE -> 1
        }
        return tierSlack + roleSlack
    }

    private fun timeSlackFor(
        tier: CampaignDifficultyTier,
        role: CampaignBlockRole,
    ): Int {
        val tierSlack = when (tier) {
            CampaignDifficultyTier.EASY -> 20
            CampaignDifficultyTier.MEDIUM -> 15
            CampaignDifficultyTier.HARD -> 12
            CampaignDifficultyTier.HARDCORE -> 10
        }
        val roleSlack = when (role) {
            CampaignBlockRole.STANDARD -> 0
            CampaignBlockRole.SPIKE -> 6
            CampaignBlockRole.HARDCORE -> 10
        }
        return tierSlack + roleSlack
    }

    private val OnboardingAttemptLimits = listOf(15, 15, 15, 15, 14, 14, 14, 14, 13, 12)
    private val OnboardingTimeLimitsSeconds = listOf(360, 345, 330, 330, 315, 315, 300, 300, 285, 270)
}
