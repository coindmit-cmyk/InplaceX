package com.mirkori.inplacex.ui.screens.company

import com.mirkori.inplacex.core.campaign.CampaignLevelDefinition
import com.mirkori.inplacex.core.campaign.CampaignLevelGenerator
import com.mirkori.inplacex.core.campaign.CampaignProgressionRules
import com.mirkori.inplacex.data.local.CampaignLevelProgress
import com.mirkori.inplacex.ui.screens.game.GameFieldParams
import com.mirkori.inplacex.ui.screens.game.MatchSessionSummary
import com.mirkori.inplacex.ui.screens.game.TypeGame
import kotlin.math.ceil

internal data class CompanyMatchResult(
    val levelNumber: Int,
    val won: Boolean,
    val backendRating: Int,
    val stars: Int,
)

internal data class CampaignLevelListItem(
    val definition: CampaignLevelDefinition,
    val progress: CampaignLevelProgress,
)

internal fun buildCampaignLevelItems(
    campaignProgress: List<CampaignLevelProgress>,
    visibleTopLevel: Int,
): List<CampaignLevelListItem> {
    val progressByLevel = campaignProgress.associateBy(CampaignLevelProgress::levelNumber)
    return (1..visibleTopLevel).map { levelNumber ->
        CampaignLevelListItem(
            definition = CampaignLevelGenerator.generate(levelNumber),
            progress = progressByLevel[levelNumber]
                ?: CampaignLevelProgress(levelNumber = levelNumber, bestBackendRating = 0),
        )
    }
}

internal fun computeUnlockedBlock(completedLevelsCount: Int, totalStars: Int): Int {
    var unlockedBlock = 1
    while (true) {
        val nextBlock = unlockedBlock + 1
        val requiredCompleted = unlockedBlock * 10
        val requiredStars = CampaignProgressionRules.requiredStarsForNextBlock(
            nextBlock,
            requiredCompleted,
        )
        if (completedLevelsCount < requiredCompleted || totalStars < requiredStars) break
        unlockedBlock = nextBlock
    }
    return unlockedBlock
}

internal fun CampaignLevelDefinition.toFieldParams(): GameFieldParams {
    return GameFieldParams(
        typeGame = TypeGame.RaceMatch,
        useHints = hintsAllowed,
        useBoosts = true,
        timeAll = raceTimeLimitSeconds,
        timeMove = 0,
        limitMoves = config.attemptLimit,
        lenSecret = config.codeLength,
    )
}

internal fun rateCampaignMatch(
    level: CampaignLevelDefinition,
    summary: MatchSessionSummary,
): Int {
    var score = level.ratingPolicy.maxBackendPoints.toDouble()
    val targetAttempts = level.ratingPolicy.targetAttemptsForPerfect
    val attemptReserve = (level.config.attemptLimit - targetAttempts).coerceAtLeast(1)
    val reserveSpent = (summary.attemptsUsed - targetAttempts).coerceAtLeast(0)
    val maximumAttemptPenalty = level.ratingPolicy.maxBackendPoints - 1
    score -= ceil(
        reserveSpent.toDouble() * maximumAttemptPenalty.toDouble() / attemptReserve.toDouble(),
    )
    score -= ceil(
        ((summary.elapsedSeconds - level.ratingPolicy.targetTimeSecondsForPerfect)
            .coerceAtLeast(0)) / 30.0,
    )

    level.ratingPolicy.assistsBudget.perfectHintsBudget?.let { hintsBudget ->
        score -= (summary.hintUses - hintsBudget).coerceAtLeast(0)
    }
    level.ratingPolicy.assistsBudget.perfectBoostsBudget?.let { boostsBudget ->
        score -= (summary.boostUses - boostsBudget).coerceAtLeast(0)
    }

    return score.toInt().coerceIn(1, level.ratingPolicy.maxBackendPoints)
}

internal fun starsForRating(rating: Int): Int {
    return when {
        rating >= 8 -> 3
        rating >= 4 -> 2
        rating >= 1 -> 1
        else -> 0
    }
}

internal fun starsLabel(stars: Int): String {
    return if (stars <= 0) "—" else buildString {
        repeat(stars) { append('★') }
    }
}

internal fun formatCampaignTime(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
