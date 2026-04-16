package com.mirkori.inplacex.ui.screens.company

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.campaign.CampaignLevelDefinition
import com.mirkori.inplacex.core.campaign.CampaignLevelGenerator
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
    extraMovesBoosts: Int = 0,
    extraTimeBoosts: Int = 0,
    onConsumeOpenPositionHint: () -> Boolean = { false },
    onConsumeCheckDigitHint: () -> Boolean = { false },
    onConsumeCheckPositionHint: () -> Boolean = { false },
    onConsumeExtraMovesBoost: () -> Boolean = { false },
    onConsumeExtraTimeBoost: () -> Boolean = { false },
    onBuyEnergy: () -> Unit = {},
    onRecordCampaignCompletion: (Int, Int) -> Unit = { _, _ -> },
    onRecordCompanyLoss: () -> Unit = {},
    onMatchStarted: () -> Unit = {},
) {
    val strings = LocalAppStrings.current
    var activeLevelNumber by remember { mutableStateOf<Int?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }
    var resultState by remember { mutableStateOf<CompanyMatchResult?>(null) }
    val activeLevel = activeLevelNumber?.let(CampaignLevelGenerator::generate)

    androidx.compose.runtime.LaunchedEffect(activeLevelNumber) {
        onInGameChange(activeLevelNumber != null)
        if (activeLevelNumber == null) {
            onDebugSecretChange(null)
        }
    }

    androidx.compose.runtime.LaunchedEffect(requestExitGame) {
        if (requestExitGame && activeLevelNumber != null) {
            activeLevelNumber = null
            showExitDialog = false
            onDebugSecretChange(null)
            onExitGameConsumed()
        }
    }

    BackHandler(enabled = showExitDialog) {
        showExitDialog = false
    }

    if (activeLevel != null) {
        GameFieldScreen(
            title = "",
            params = activeLevel.toFieldParams(),
            onBack = { showExitDialog = true },
            onDebugSecretChange = onDebugSecretChange,
            openPositionHints = openPositionHints,
            checkDigitHints = checkDigitHints,
            checkPositionHints = checkPositionHints,
            extraMovesBoosts = extraMovesBoosts,
            extraTimeBoosts = extraTimeBoosts,
            onConsumeOpenPositionHint = onConsumeOpenPositionHint,
            onConsumeCheckDigitHint = onConsumeCheckDigitHint,
            onConsumeCheckPositionHint = onConsumeCheckPositionHint,
            onConsumeExtraMovesBoost = onConsumeExtraMovesBoost,
            onConsumeExtraTimeBoost = onConsumeExtraTimeBoost,
            onMatchStarted = onMatchStarted,
            onMatchFinished = { summary ->
                val rating = if (summary.won) {
                    rateCampaignMatch(activeLevel, summary)
                } else {
                    0
                }
                if (summary.won) {
                    onRecordCampaignCompletion(activeLevel.levelNumber, rating)
                } else {
                    onRecordCompanyLoss()
                }
                resultState = CompanyMatchResult(
                    levelNumber = activeLevel.levelNumber,
                    won = summary.won,
                    backendRating = rating,
                    stars = starsForRating(rating)
                )
                activeLevelNumber = null
                onDebugSecretChange(null)
            },
            autoRestartOnWin = false,
            extraMovesPerBoost = activeLevel.boostPack.extraMoves,
            extraTimeSecondsPerBoost = activeLevel.boostPack.extraSeconds,
        )
    } else {
        val blockStart = ((progressState.highestUnlockedCampaignLevel - 1) / 10) * 10 + 1
        val blockEnd = blockStart + 9
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = strings.text("company.title"),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Unlocked level: ${progressState.highestUnlockedCampaignLevel}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Energy: ${progressState.campaignEnergy}/${progressState.campaignEnergyMax} | refill ${progressState.campaignEnergyRefillMinutes} min",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Total rating: ${progressState.totalCampaignRating}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (progressState.campaignEnergy <= 0) {
                    FilledTonalButton(onClick = onBuyEnergy) {
                        Text("Buy 1 energy")
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(campaignProgress.filter { it.levelNumber in blockStart..blockEnd }) { levelProgress ->
                        val levelDefinition = CampaignLevelGenerator.generate(levelProgress.levelNumber)
                        val isUnlocked = levelProgress.levelNumber <= progressState.highestUnlockedCampaignLevel
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Level ${levelDefinition.levelNumber}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${levelDefinition.difficultyTier} / cn ${levelDefinition.config.codeLength} / ${levelDefinition.config.attemptLimit} moves",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "Best: ${levelProgress.bestBackendRating}/10 ${starsLabel(starsForRating(levelProgress.bestBackendRating))}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (isUnlocked && progressState.campaignEnergy > 0) {
                                    Button(onClick = { activeLevelNumber = levelDefinition.levelNumber }) {
                                        Text("Play")
                                    }
                                } else {
                                    FilledTonalButton(onClick = {}, enabled = false) {
                                        Text(if (isUnlocked) "No energy" else "Locked")
                                    }
                                }
                            }
                        }
                    }
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
            title = {
                Text(if (result.won) "Level complete" else "Level failed")
            },
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

private data class CompanyMatchResult(
    val levelNumber: Int,
    val won: Boolean,
    val backendRating: Int,
    val stars: Int,
)

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
