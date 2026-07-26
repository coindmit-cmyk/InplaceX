package com.mirkori.inplacex.ui.screens.company

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.campaign.CampaignLevelGenerator
import com.mirkori.inplacex.core.campaign.CampaignProgressionRules
import com.mirkori.inplacex.data.local.CampaignLevelProgress
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.logging.AppLog
import com.mirkori.inplacex.ui.screens.game.GameFieldScreen

@Composable
fun CompanyRootScreen(
    progressState: GameProgressState,
    campaignProgress: List<CampaignLevelProgress>,
    activeLevelNumber: Int?,
    onActiveLevelNumberChange: (Int?) -> Unit,
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
    val logTag = "CompanyRootScreen"
    val strings = LocalAppStrings.current
    var showExitDialog by rememberSaveable { mutableStateOf(false) }
    var resultState by remember { mutableStateOf<CompanyMatchResult?>(null) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val activeLevel = activeLevelNumber?.let(CampaignLevelGenerator::generate)

    LaunchedEffect(activeLevelNumber) {
        AppLog.debug(
            tag = logTag,
            message = "campaign active level changed",
            attributes = mapOf("level" to (activeLevelNumber?.toString() ?: "none")),
        )
        onInGameChange(activeLevelNumber != null)
        if (activeLevelNumber == null) {
            onDebugSecretChange(null)
        }
    }

    LaunchedEffect(requestExitGame, activeLevelNumber) {
        if (!requestExitGame) return@LaunchedEffect

        AppLog.debug(
            tag = logTag,
            message = "campaign exit request consumed",
            attributes = mapOf("activeLevel" to (activeLevelNumber?.toString() ?: "none")),
        )
        if (activeLevelNumber != null) {
            showExitDialog = true
        }
        onExitGameConsumed()
    }

    BackHandler(enabled = showExitDialog) { showExitDialog = false }
    BackHandler(enabled = showHistory) { showHistory = false }
    BackHandler(
        enabled = activeLevelNumber != null &&
            !showExitDialog &&
            resultState == null,
    ) {
        showExitDialog = true
    }

    when {
        activeLevel != null -> GameFieldScreen(
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
                AppLog.info(
                    tag = logTag,
                    message = "campaign match finished",
                    attributes = mapOf(
                        "level" to activeLevel.levelNumber.toString(),
                        "won" to summary.won.toString(),
                        "attempts" to summary.attemptsUsed.toString(),
                        "elapsedSeconds" to summary.elapsedSeconds.toString(),
                    ),
                )
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
                onActiveLevelNumberChange(null)
                onDebugSecretChange(null)
            },
            autoRestartOnWin = false,
            extraMovesPerBoost = activeLevel.boostPack.extraMoves,
            extraTimeSecondsPerBoost = activeLevel.boostPack.extraSeconds,
        )

        showHistory -> CampaignHistoryScreen(
            strings = strings,
            progress = campaignProgress
                .filter { it.bestBackendRating > 0 }
                .sortedByDescending { it.levelNumber },
            onSelectLevel = {
                onActiveLevelNumberChange(it)
                showHistory = false
            },
            onClose = { showHistory = false },
        )

        else -> {
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
            val requiredStarsForNextBlock = CampaignProgressionRules.requiredStarsForNextBlock(
                nextBlockNumber,
                completedLevelsCount,
            )
            val nextBlockLocked =
                completedLevelsCount >= unlockedBlock * 10 &&
                    totalStars < requiredStarsForNextBlock
            val focusLevel = if (nextBlockLocked) {
                accessibleMaxLevel + 1
            } else {
                progressState.highestUnlockedCampaignLevel.coerceAtLeast(1)
            }
            val visibleTopLevel = maxOf(focusLevel + 9, accessibleMaxLevel + 10, 20)
            val levelItems = remember(campaignProgress, visibleTopLevel) {
                buildCampaignLevelItems(campaignProgress, visibleTopLevel)
            }

            CompanySceneScreen(
                strings = strings,
                progressState = progressState,
                levelItems = levelItems,
                focusLevel = focusLevel,
                accessibleMaxLevel = accessibleMaxLevel,
                totalStars = totalStars,
                requiredStarsForNextBlock = requiredStarsForNextBlock,
                nextBlockLocked = nextBlockLocked,
                onHistory = { showHistory = true },
                onBuyEnergy = onBuyEnergy,
                onPlay = { levelNumber ->
                    AppLog.info(
                        tag = logTag,
                        message = "campaign level selected",
                        attributes = mapOf("level" to levelNumber.toString()),
                    )
                    onActiveLevelNumberChange(levelNumber)
                },
            )
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(strings.text("company.dialog.exit_title")) },
            text = { Text(strings.text("company.dialog.exit_text")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        onActiveLevelNumberChange(null)
                        onDebugSecretChange(null)
                    },
                ) {
                    Text(strings.text("company.dialog.exit_confirm"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text(strings.text("company.dialog.stay"))
                }
            },
        )
    }

    resultState?.let { result ->
        AlertDialog(
            onDismissRequest = { resultState = null },
            title = {
                Text(
                    if (result.won) {
                        strings.text("company.dialog.complete_title")
                    } else {
                        strings.text("company.dialog.failed_title")
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        strings.text("company.dialog.level")
                            .replace("{value}", result.levelNumber.toString()),
                    )
                    Text(
                        strings.text("company.dialog.rating")
                            .replace("{value}", result.backendRating.toString()),
                    )
                    Text(
                        strings.text("company.dialog.stars")
                            .replace("{value}", starsLabel(result.stars)),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { resultState = null }) {
                    Text(strings.text("company.action.close"))
                }
            },
        )
    }
}
