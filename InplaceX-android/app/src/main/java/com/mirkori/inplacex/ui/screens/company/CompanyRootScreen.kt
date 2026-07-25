package com.mirkori.inplacex.ui.screens.company
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.campaign.CampaignLevelDefinition
import com.mirkori.inplacex.core.campaign.CampaignLevelGenerator
import com.mirkori.inplacex.core.campaign.CampaignProgressionRules
import com.mirkori.inplacex.data.local.CampaignLevelProgress
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.logging.AppLog
import com.mirkori.inplacex.ui.screens.game.GameFieldParams
import com.mirkori.inplacex.ui.screens.game.GameFieldScreen
import com.mirkori.inplacex.ui.screens.game.MatchSessionSummary
import com.mirkori.inplacex.ui.screens.game.TypeGame
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import kotlin.math.ceil

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
            resultState == null
    ) {
        showExitDialog = true
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
    } else if (showHistory) {
        CampaignHistoryScreen(
            strings = strings,
            progress = campaignProgress.filter { it.bestBackendRating > 0 }.sortedByDescending { it.levelNumber },
            onSelectLevel = {
                onActiveLevelNumberChange(it)
                showHistory = false
            },
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

        CompanySceneScreen(
            strings = strings,
            progressState = progressState,
            levelItems = levelItems,
            focusLevel = focusLevel,
            accessibleMaxLevel = accessibleMaxLevel,
            totalStars = totalStars,
            requiredStarsForNextBlock = requiredStarsForNextBlock,
            nextBlockLocked = nextBlockLocked,
            listState = listState,
            onHistory = { showHistory = true },
            onBuyEnergy = onBuyEnergy,
            onPlay = {
                AppLog.info(
                    tag = logTag,
                    message = "campaign level selected",
                    attributes = mapOf("level" to it.toString()),
                )
                onActiveLevelNumberChange(it)
            }
        )
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
                    }
                ) {
                    Text(strings.text("company.dialog.exit_confirm"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text(strings.text("company.dialog.stay"))
                }
            }
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
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.text("company.dialog.level").replace("{value}", result.levelNumber.toString()))
                    Text(strings.text("company.dialog.rating").replace("{value}", result.backendRating.toString()))
                    Text(strings.text("company.dialog.stars").replace("{value}", starsLabel(result.stars)))
                }
            },
            confirmButton = {
                TextButton(onClick = { resultState = null }) {
                    Text(strings.text("company.action.close"))
                }
            }
        )
    }
}

@Composable
private fun CompanySceneScreen(
    strings: com.mirkori.inplacex.platform.localization.LocalizationProvider,
    progressState: GameProgressState,
    levelItems: List<CampaignLevelListItem>,
    focusLevel: Int,
    accessibleMaxLevel: Int,
    totalStars: Int,
    requiredStarsForNextBlock: Int,
    nextBlockLocked: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onHistory: () -> Unit,
    onBuyEnergy: () -> Unit,
    onPlay: (Int) -> Unit,
) {
    val focusItem = levelItems.first { it.definition.levelNumber == focusLevel }
    val focusPlayable = focusLevel <= accessibleMaxLevel && focusLevel <= progressState.highestUnlockedCampaignLevel
    val hasEnergy = progressState.campaignEnergy > 0

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(28.dp))
                .border(1.dp, Color.White.copy(alpha = 0.72f), RoundedCornerShape(28.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.74f),
                            Color(0xFFEFF5FF).copy(alpha = 0.58f)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SceneCard(accentColor = Color.White.copy(alpha = 0.80f)) {
                    Text(
                        text = strings.text("company.title"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = strings.text("company.scene.stars")
                            .replace("{current}", totalStars.toString())
                            .replace("{required}", requiredStarsForNextBlock.toString()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (nextBlockLocked) {
                            strings.text("company.scene.locked")
                        } else {
                            strings.text("company.scene.unlocked")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp, top = 30.dp, bottom = 90.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SideSceneAction(
                    title = strings.text("company.scene.history"),
                    subtitle = strings.text("company.scene.history.subtitle"),
                    onClick = onHistory
                )
                SideSceneAction(
                    title = strings.text("company.scene.energy"),
                    subtitle = "${progressState.campaignEnergy}/${progressState.campaignEnergyMax}",
                    onClick = onBuyEnergy
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp, top = 30.dp, bottom = 90.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SideInfoBadge(
                    title = strings.text("company.scene.current"),
                    value = focusLevel.toString()
                )
                SideInfoBadge(
                    title = strings.text("company.scene.best"),
                    value = "${focusItem.progress.bestBackendRating}/10"
                )
            }

            LazyColumn(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight(0.64f)
                    .fillMaxWidth(0.42f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                itemsIndexed(levelItems) { _, item ->
                    val levelNumber = item.definition.levelNumber
                    val isCompleted = item.progress.bestBackendRating > 0
                    val isCurrentFocus = levelNumber == focusLevel
                    val isPlayable = levelNumber <= accessibleMaxLevel && levelNumber <= progressState.highestUnlockedCampaignLevel
                    val isLocked = !isPlayable && !isCompleted
                    LevelStone(
                        levelNumber = levelNumber,
                        stars = starsForRating(item.progress.bestBackendRating),
                        isCurrentFocus = isCurrentFocus,
                        isLocked = isLocked,
                        onClick = {
                            if (isPlayable || isCompleted) {
                                onPlay(levelNumber)
                            }
                        }
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp)
                    .heightIn(min = 60.dp),
                shape = RoundedCornerShape(28.dp),
                color = if (focusPlayable && hasEnergy) Color(0xFF9BE54D) else Color(0xFFE0E3D8),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp
            ) {
                Button(
                    onClick = { onPlay(focusLevel) },
                    enabled = focusPlayable && hasEnergy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text(
                        text = when {
                            !hasEnergy -> strings.text("company.scene.no_energy")
                            !focusPlayable -> strings.text("company.scene.locked_level")
                            else -> strings.text("company.scene.level").replace("{value}", focusLevel.toString())
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelStone(
    levelNumber: Int,
    stars: Int,
    isCurrentFocus: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit,
) {
    val size = if (isCurrentFocus) 82.dp else 64.dp
    val bg = when {
        isLocked -> Color(0xFFC8C6BE)
        isCurrentFocus -> Color(0xFFF0D4A3)
        else -> Color(0xFFE6C898)
    }
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = bg,
        tonalElevation = if (isCurrentFocus) 8.dp else 3.dp,
        shadowElevation = if (isCurrentFocus) 10.dp else 4.dp,
        onClick = onClick,
        enabled = true
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = if (isCurrentFocus) 3.dp else 1.dp,
                    color = if (isCurrentFocus) Color(0xFF9F6A1F) else Color(0xFFB98F56),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = levelNumber.toString(),
                    style = if (isCurrentFocus) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6C5129)
                )
                if (stars > 0) {
                    Text(
                        text = starsLabel(stars),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF8A641C)
                    )
                }
            }
        }
    }
}

@Composable
private fun SideSceneAction(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SideInfoBadge(
    title: String,
    value: String,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.86f),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, style = MaterialTheme.typography.labelSmall)
            Text(value, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CampaignHistoryScreen(
    strings: com.mirkori.inplacex.platform.localization.LocalizationProvider,
    progress: List<CampaignLevelProgress>,
    onSelectLevel: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SceneCard(accentColor = Color.White.copy(alpha = 0.76f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = strings.text("company.history.title"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onClose) {
                    Text(strings.text("company.action.close"))
                }
            }
        }

        if (progress.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = strings.text("company.history.empty"),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 58.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(progress) { item ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clickable { onSelectLevel(item.levelNumber) },
                        shape = RoundedCornerShape(18.dp),
                        color = Color.White.copy(alpha = 0.88f),
                        tonalElevation = 2.dp,
                        shadowElevation = 4.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(6.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = item.levelNumber.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = starsLabel(starsForRating(item.bestBackendRating)),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8A641C)
                            )
                            Text(
                                text = "${item.bestBackendRating}/10",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
