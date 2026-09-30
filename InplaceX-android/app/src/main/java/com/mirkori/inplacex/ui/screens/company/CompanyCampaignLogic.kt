package com.mirkori.inplacex.ui.screens.company

import com.mirkori.inplacex.core.campaign.CampaignLevelDefinition
import com.mirkori.inplacex.core.campaign.CampaignLevelGenerator
import com.mirkori.inplacex.core.campaign.CampaignPerformance
import com.mirkori.inplacex.core.campaign.CampaignProgressionRules
import com.mirkori.inplacex.core.campaign.CampaignRatingCalculator
import com.mirkori.inplacex.data.local.CampaignLevelProgress
import com.mirkori.inplacex.ui.screens.game.GameFieldParams
import com.mirkori.inplacex.ui.screens.game.MatchSessionSummary
import com.mirkori.inplacex.ui.screens.game.TypeGame

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
    return CampaignProgressionRules.computeUnlockedBlock(completedLevelsCount, totalStars)
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
        allowDuplicates = config.allowDuplicates,
        forbidAllSameDigitsGuess = config.forbidAllSameDigitsGuess,
        forbidAdjacentDuplicates = config.forbidAdjacentDuplicates,
        forbidTripleDuplicates = config.forbidTripleDuplicates,
        maxConsecutiveDuplicateDigits = config.maxConsecutiveDuplicateDigits,
    )
}

internal fun rateCampaignMatch(
    level: CampaignLevelDefinition,
    summary: MatchSessionSummary,
): Int {
    return CampaignRatingCalculator.rate(
        level = level,
        performance = CampaignPerformance(
            won = summary.won,
            attemptsUsed = summary.attemptsUsed,
            elapsedSeconds = summary.elapsedSeconds,
            hintUses = summary.hintUses,
            boostUses = summary.boostUses,
        ),
    )
}

internal fun starsForRating(rating: Int): Int {
    return CampaignRatingCalculator.starsForRating(rating)
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
