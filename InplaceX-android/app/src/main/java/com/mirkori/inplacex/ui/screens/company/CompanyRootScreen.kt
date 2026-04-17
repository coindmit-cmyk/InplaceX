package com.mirkori.inplacex.ui.screens.company

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.campaign.CampaignLevelDefinition
import com.mirkori.inplacex.core.campaign.CampaignLevelGenerator
import com.mirkori.inplacex.core.campaign.CampaignProgressionRules
import com.mirkori.inplacex.data.local.CampaignLevelProgress
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.screens.game.GameFieldParams
import com.mirkori.inplacex.ui.screens.game.GameFieldScreen
import com.mirkori.inplacex.ui.screens.game.MatchSessionSummary
import com.mirkori.inplacex.ui.screens.game.TypeGame
import kotlin.math.ceil

@Composable
fun CompanyRootScreen(
    progressState: GameProgressState,
    campaignProgress: List<CampaignLevelProgress>,
    requestExitGame: Boolean = false,
    onExitGameConsumed: () -> Unit = {},
    onInGameChange: (Boolean) -> Unit = {},
    onDebugSecretChange: (String?) -> Unit = {},
    openPositionHints: Int = 0,
    checkDigitHints: Int = 0,
    checkPositionHints: Int = 0,
    autoModeAvailable: Boolean = true,
    infiniteHintsEnabled: Boolean = false,
    extraMovesBoosts: Int = 0,
    extraTimeBoosts: Int = 0,
    onConsumeOpenPositionHint: () -> Boolean = { false },
    onConsumeCheckDigitHint: () -> Boolean = { false },
    onConsumeCheckPositionHint: () -> Boolean = { false },
    onWatchRewardedHintAd: (com.mirkori.inplacex.data.local.HintStockType) -> Boolean = { false },
    onConsumeExtraMovesBoost: () -> Boolean = { false },
    onConsumeExtraTimeBoost: () -> Boolean = { false },
    onBuyEnergy: () -> Unit = {},
    onRecordCampaignCompletion: (Int, Int) -> Unit = { _, _ -> },
    onRecordCompanyLoss: () -> Unit = {},
    onMatchStarted: () -> Unit = {},
) {
    var activeLevelNumber by remember { mutableStateOf<Int?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }
    var resultState by remember { mutableStateOf<CompanyMatchResult?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    val activeLevel = activeLevelNumber?.let(CampaignLevelGenerator::generate)

    LaunchedEffect(activeLevelNumber) {
        onInGameChange(activeLevelNumber != null)
        if (activeLevelNumber == null) {
            onDebugSecretChange(null)
        }
    }

    LaunchedEffect(requestExitGame) {
        if (requestExitGame && activeLevelNumber != null) {
            activeLevelNumber = null
            showExitDialog = false
            onDebugSecretChange(null)
            onExitGameConsumed()
        }
    }

    BackHandler(enabled = showExitDialog) { showExitDialog = false }
    BackHandler(enabled = showHistory) { showHistory = false }

    if (activeLevel != null) {
        GameFieldScreen(
            title = "",
            params = activeLevel.toFieldParams(),
            onBack = { showExitDialog = true },
            onDebugSecretChange = onDebugSecretChange,
            openPositionHints = openPositionHints,
            checkDigitHints = checkDigitHints,
            checkPositionHints = checkPositionHints,
            autoModeAvailable = autoModeAvailable,
            infiniteHintsEnabled = infiniteHintsEnabled,
            extraMovesBoosts = extraMovesBoosts,
            extraTimeBoosts = extraTimeBoosts,
            onConsumeOpenPositionHint = onConsumeOpenPositionHint,
            onConsumeCheckDigitHint = onConsumeCheckDigitHint,
            onConsumeCheckPositionHint = onConsumeCheckPositionHint,
            onWatchRewardedHintAd = onWatchRewardedHintAd,
            onConsumeExtraMovesBoost = onConsumeExtraMovesBoost,
            onConsumeExtraTimeBoost = onConsumeExtraTimeBoost,
            onMatchStarted = onMatchStarted,
            onMatchFinished = { summary ->
                val rating = if (summary.won) rateCampaignMatch(activeLevel, summary) else 0
                if (summary.won) {
                    onRecordCampaignCompletion(activeLevel.levelNumber, rating)
                } else {
                    onRecordCompanyLoss()
                }
                resultState = CompanyMatchResult(
                    levelNumber = activeLevel.levelNumber,
                    won = summary.won,
                    backendRating = rating,
                    stars = starsForRating(rating),
                )
                activeLevelNumber = null
                onDebugSecretChange(null)
            },
            autoRestartOnWin = false,
            extraMovesPerBoost = activeLevel.boostPack.extraMoves,
            extraTimeSecondsPerBoost = activeLevel.boostPack.extraSeconds,
        )
    } else if (showHistory) {
        CampaignHistoryScreen(
            progress = campaignProgress.filter { it.bestBackendRating > 0 }.sortedByDescending { it.levelNumber },
            onClose = { showHistory = false }
        )
    } else {
        val completedProgress = remember(campaignProgress) {
            campaignProgress.filter { it.bestBackendRating > 0 }.sortedBy { it.levelNumber }
        }
        val completedLevelsCount = completedProgress.size
        val totalStars = completedProgress.sumOf { starsForRating(it.bestBackendRating) }
        val unlockedBlock = remember(completedLevelsCount, totalStars) {
            computeUnlockedBlock(completedLevelsCount, totalStars)
        }
        val accessibleMaxLevel = unlockedBlock * 10
        val nextBlockNumber = unlockedBlock + 1
        val requiredStarsForNextBlock = CampaignProgressionRules.requiredStarsForNextBlock(nextBlockNumber, completedLevelsCount)
        val nextBlockLocked = completedLevelsCount >= unlockedBlock * 10 && totalStars < requiredStarsForNextBlock
        val focusLevel = when {
            nextBlockLocked -> accessibleMaxLevel + 1
            else -> progressState.highestUnlockedCampaignLevel.coerceAtLeast(1)
        }
        val visibleTopLevel = maxOf(focusLevel + 9, accessibleMaxLevel + 10, 20)
        val levelItems = remember(campaignProgress, visibleTopLevel) {
            (1..visibleTopLevel).map { levelNumber ->
                val progress = campaignProgress.firstOrNull { it.levelNumber == levelNumber }
                    ?: CampaignLevelProgress(levelNumber = levelNumber, bestBackendRating = 0)
                CampaignLevelListItem(
                    definition = CampaignLevelGenerator.generate(levelNumber),
                    progress = progress,
                )
            }.sortedByDescending { it.definition.levelNumber }
        }
        val focusIndex = levelItems.indexOfFirst { it.definition.levelNumber == focusLevel }.coerceAtLeast(0)
        val listState = rememberLazyListState()

        LaunchedEffect(focusLevel, levelItems.size) {
            listState.scrollToItem((focusIndex - 2).coerceAtLeast(0))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Company",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Current level: $focusLevel",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Stars: $totalStars | Next block: $requiredStarsForNextBlock",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Energy: ${progressState.campaignEnergy}/${progressState.campaignEnergyMax} | refill ${progressState.campaignEnergyRefillMinutes} min",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (nextBlockLocked) {
                        Text(
                            text = "Need $requiredStarsForNextBlock stars to unlock levels ${accessibleMaxLevel + 1}-${accessibleMaxLevel + 10}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { showHistory = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("History")
                        }
                        FilledTonalButton(
                            onClick = onBuyEnergy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Buy energy")
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(levelItems) { _, item ->
                    val levelNumber = item.definition.levelNumber
                    val isCompleted = item.progress.bestBackendRating > 0
                    val isCurrentFocus = levelNumber == focusLevel
                    val isPlayable = levelNumber <= accessibleMaxLevel && levelNumber <= progressState.highestUnlockedCampaignLevel
                    val isLocked = !isPlayable && !isCompleted
                    val stars = starsForRating(item.progress.bestBackendRating)

                    CampaignLevelCard(
                        item = item,
                        stars = stars,
                        isCompleted = isCompleted,
                        isCurrentFocus = isCurrentFocus,
                        isPlayable = isPlayable,
                        isLocked = isLocked,
                        hasEnergy = progressState.campaignEnergy > 0,
                        onPlay = { activeLevelNumber = levelNumber }
                    )
                }
            }
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("Exit campaign run?") },
            text = { Text("Progress in the current attempt will be lost.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        activeLevelNumber = null
                        onDebugSecretChange(null)
                    }
                ) {
                    Text("Exit")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Stay")
                }
            }
        )
    }

    resultState?.let { result ->
        AlertDialog(
            onDismissRequest = { resultState = null },
            title = { Text(if (result.won) "Level complete" else "Level failed") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Level ${result.levelNumber}")
                    Text("Rating: ${result.backendRating}/10")
                    Text("Stars: ${starsLabel(result.stars)}")
                }
            },
            confirmButton = {
                TextButton(onClick = { resultState = null }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
private fun CampaignLevelCard(
    item: CampaignLevelListItem,
    stars: Int,
    isCompleted: Boolean,
    isCurrentFocus: Boolean,
    isPlayable: Boolean,
    isLocked: Boolean,
    hasEnergy: Boolean,
    onPlay: () -> Unit,
) {
    val borderColor = when {
        isCurrentFocus -> MaterialTheme.colorScheme.primary
        isLocked -> MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isCurrentFocus) 2.dp else 1.dp,
            color = borderColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Level ${item.definition.levelNumber}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${item.definition.difficultyTier} | cn ${item.definition.config.codeLength} | ${item.definition.config.attemptLimit} moves",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Best: ${item.progress.bestBackendRating}/10 | ${starsLabel(stars)}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (isCurrentFocus) {
                    Text(
                        text = if (isLocked) "Locked target level" else "Current target level",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            when {
                isCompleted -> {
                    FilledTonalButton(onClick = onPlay, enabled = hasEnergy) {
                        Text(if (hasEnergy) "Replay" else "No energy")
                    }
                }
                isPlayable && hasEnergy -> {
                    Button(onClick = onPlay) {
                        Text("Play")
                    }
                }
                isPlayable && !hasEnergy -> {
                    FilledTonalButton(onClick = {}, enabled = false) {
                        Text("No energy")
                    }
                }
                else -> {
                    FilledTonalButton(onClick = {}, enabled = false) {
                        Text("Locked")
                    }
                }
            }
        }
    }
}

@Composable
private fun CampaignHistoryScreen(
    progress: List<CampaignLevelProgress>,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "History",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(onClick = onClose) {
                Text("Close")
            }
        }

        if (progress.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Completed levels will appear here.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(progress) { _, item ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Level ${item.levelNumber}", fontWeight = FontWeight.SemiBold)
                                Text("Rating: ${item.bestBackendRating}/10", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(starsLabel(starsForRating(item.bestBackendRating)))
                        }
                    }
                }
            }
        }
    }
}

private data class CompanyMatchResult(
    val levelNumber: Int,
    val won: Boolean,
    val backendRating: Int,
    val stars: Int,
)

private data class CampaignLevelListItem(
    val definition: CampaignLevelDefinition,
    val progress: CampaignLevelProgress,
)

private fun computeUnlockedBlock(completedLevelsCount: Int, totalStars: Int): Int {
    var unlockedBlock = 1
    while (true) {
        val nextBlock = unlockedBlock + 1
        val requiredCompleted = unlockedBlock * 10
        val requiredStars = CampaignProgressionRules.requiredStarsForNextBlock(nextBlock, requiredCompleted)
        if (completedLevelsCount < requiredCompleted) break
        if (totalStars < requiredStars) break
        unlockedBlock = nextBlock
    }
    return unlockedBlock
}

private fun CampaignLevelDefinition.toFieldParams(): GameFieldParams {
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

private fun rateCampaignMatch(
    level: CampaignLevelDefinition,
    summary: MatchSessionSummary,
): Int {
    var score = level.ratingPolicy.maxBackendPoints.toDouble()
    score -= (summary.attemptsUsed - level.ratingPolicy.targetAttemptsForPerfect).coerceAtLeast(0)
    score -= ceil(((summary.elapsedSeconds - level.ratingPolicy.targetTimeSecondsForPerfect).coerceAtLeast(0)) / 30.0)

    val hintsBudget = level.ratingPolicy.assistsBudget.perfectHintsBudget
    if (hintsBudget != null) {
        score -= (summary.hintUses - hintsBudget).coerceAtLeast(0)
    }

    val boostsBudget = level.ratingPolicy.assistsBudget.perfectBoostsBudget
    if (boostsBudget != null) {
        score -= (summary.boostUses - boostsBudget).coerceAtLeast(0)
    }

    return score.toInt().coerceIn(1, level.ratingPolicy.maxBackendPoints)
}

private fun starsForRating(rating: Int): Int {
    return when {
        rating >= 8 -> 3
        rating >= 4 -> 2
        rating >= 1 -> 1
        else -> 0
    }
}

private fun starsLabel(stars: Int): String {
    return if (stars <= 0) "-" else buildString {
        repeat(stars) { append('★') }
    }
}
