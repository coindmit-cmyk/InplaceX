package com.mirkori.inplacex.ui.screens.company

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mirkori.inplacex.core.campaign.CampaignChapterRewardPolicy
import com.mirkori.inplacex.core.campaign.CampaignProgressionRules
import com.mirkori.inplacex.core.retention.RetentionRewardType
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.data.local.RetentionRewardStatus
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import com.mirkori.inplacex.ui.theme.PageColors

@Composable
internal fun CompanySceneScreen(
    strings: LocalizationProvider,
    progressState: GameProgressState,
    levelItems: List<CampaignLevelListItem>,
    claimedChapterNumbers: Set<Int>,
    focusLevel: Int,
    accessibleMaxLevel: Int,
    starsByLevel: Map<Int, Int>,
    unlockedBlock: Int,
    onHistory: () -> Unit,
    onClaimChapterReward: (Int) -> Boolean,
    retentionRewardStatus: RetentionRewardStatus,
    onRefreshRetentionRewards: () -> Unit,
    onClaimRetentionReward: (RetentionRewardType) -> Boolean,
    onBuyEnergy: () -> Unit,
    onPlay: (Int) -> Unit,
) {
    val focusChapter = CampaignProgressionRules.chapterForLevel(focusLevel)
    var selectedChapterState by rememberSaveable { mutableIntStateOf(focusChapter) }
    var selectedLevel by rememberSaveable { mutableIntStateOf(focusLevel) }
    var showRules by rememberSaveable { mutableStateOf(false) }
    var showRetentionRewards by rememberSaveable { mutableStateOf(false) }
    var rewardDialogState by rememberSaveable {
        mutableStateOf<ChapterRewardDialogState?>(null)
    }
    val maxVisibleChapter = unlockedBlock + 1
    val selectedChapter = selectedChapterState.coerceIn(1, maxVisibleChapter)
    val chapterItems = campaignLevelItemsForChapter(levelItems, selectedChapter)
    val displayItems = chapterItems
    val selectedItem = chapterItems.firstOrNull { it.definition.levelNumber == selectedLevel }
        ?: chapterItems.first()
    val selectedCompleted = selectedItem.progress.bestBackendRating > 0
    val selectedChapterLevels = CampaignChapterRewardPolicy.levelRange(selectedChapter)
    val selectedChapterCompleted = selectedChapterLevels.all { levelNumber ->
        levelItems.firstOrNull { it.definition.levelNumber == levelNumber }
            ?.progress
            ?.bestBackendRating
            ?.let { it > 0 } == true
    }
    val selectedChapterRewardClaimed = selectedChapter in claimedChapterNumbers
    val progressTargetBlock = if (selectedChapter <= unlockedBlock) {
        selectedChapter + 1
    } else {
        selectedChapter
    }
    val requiredStars = CampaignProgressionRules.requiredStarsForNextBlock(progressTargetBlock)
    val totalStars = CampaignProgressionRules.earnedStarsForNextBlock(
        progressTargetBlock,
        starsByLevel,
    )
    val nextBlockLocked = !CampaignProgressionRules.isBlockUnlocked(
        progressTargetBlock,
        starsByLevel,
    )
    val openChapterReward = {
        rewardDialogState = when {
            selectedChapterRewardClaimed -> ChapterRewardDialogState.CLAIMED
            !selectedChapterCompleted -> ChapterRewardDialogState.LOCKED
            onClaimChapterReward(selectedChapter) -> ChapterRewardDialogState.COLLECTED
            else -> ChapterRewardDialogState.CLAIMED
        }
    }
    val selectedPlayable =
        selectedCompleted ||
            (
                selectedLevel <= accessibleMaxLevel &&
                    selectedLevel <= progressState.highestUnlockedCampaignLevel
                )
    val hasEnergy = progressState.campaignEnergy > 0
    val selectChapter: (Int) -> Unit = { chapterNumber ->
        val items = campaignLevelItemsForChapter(levelItems, chapterNumber)
        val preferredItem = items.lastOrNull { item ->
            item.progress.bestBackendRating > 0 ||
                item.definition.levelNumber <= progressState.highestUnlockedCampaignLevel
        } ?: items.first()
        selectedChapterState = chapterNumber
        selectedLevel = preferredItem.definition.levelNumber
    }

    LaunchedEffect(focusLevel, levelItems.size) {
        selectedChapterState = focusChapter
        selectedLevel = focusLevel
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxHeight < 540.dp || maxWidth < 340.dp
        val heroHeight = if (compact) 72.dp else 75.dp
        val chapterHeight = if (compact) 72.dp else 82.dp
        val actionHeight = if (compact) 124.dp else 152.dp
        val heroChapterGap = if (compact) 4.dp else 3.dp
        val chapterMapGap = if (compact) 3.dp else 1.dp
        val mapActionGap = 4.dp
        val mapActionOverlap = if (compact) 4.dp else 7.dp
        val availableMapHeight = maxHeight -
            heroHeight - heroChapterGap - chapterHeight - chapterMapGap -
            actionHeight - mapActionGap + mapActionOverlap
        val mapHeight = availableMapHeight.coerceIn(
            minimumValue = if (compact) 100.dp else 220.dp,
            maximumValue = if (compact) 260.dp else 362.dp,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("company-reference-screen")
                .testTag("company-mission-list")
                .padding(horizontal = 12.dp),
        ) {
            CompanyScreenHeader(
                strings = strings,
                compact = compact,
                retentionRewardAvailable = retentionRewardStatus.anyAvailable,
                onRetentionRewards = {
                    onRefreshRetentionRewards()
                    showRetentionRewards = true
                },
                onHistory = onHistory,
                modifier = Modifier
                    .height(heroHeight)
                    .padding(horizontal = if (compact) 0.dp else 4.dp),
            )
            Spacer(modifier = Modifier.height(heroChapterGap))
            CompanyChapterCard(
                strings = strings,
                chapter = selectedChapter,
                totalStars = totalStars,
                requiredStars = requiredStars,
                nextBlockLocked = nextBlockLocked,
                rewardAvailable = selectedChapterCompleted,
                rewardClaimed = selectedChapterRewardClaimed,
                canGoPrevious = selectedChapter > 1,
                canGoNext = selectedChapter < maxVisibleChapter,
                onPrevious = { selectChapter(selectedChapter - 1) },
                onNext = { selectChapter(selectedChapter + 1) },
                onRewardClick = openChapterReward,
                compact = compact,
                modifier = Modifier
                    .height(chapterHeight)
                    .padding(horizontal = if (compact) 0.dp else 4.dp)
                    .zIndex(1f),
            )
            Spacer(modifier = Modifier.height(chapterMapGap))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(mapHeight + mapActionGap + actionHeight - mapActionOverlap),
            ) {
                CompanyMapRoute(
                    strings = strings,
                    items = displayItems,
                    selectedLevel = selectedLevel,
                    accessibleMaxLevel = accessibleMaxLevel,
                    highestUnlockedLevel = progressState.highestUnlockedCampaignLevel,
                    viewportHeight = mapHeight,
                    compact = compact,
                    onSelect = { selectedLevel = it },
                )
                CompanyActionBar(
                    strings = strings, levelNumber = selectedLevel,
                    playable = selectedPlayable, hasEnergy = hasEnergy,
                    requiredStars = requiredStars,
                    lockRequiresStars = selectedLevel > accessibleMaxLevel,
                    onBuyEnergy = onBuyEnergy, onPlay = { onPlay(selectedLevel) },
                    onRules = { showRules = true }, compact = compact,
                    definition = selectedItem.definition,
                    bestRating = selectedItem.progress.bestBackendRating,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .height(actionHeight),
                )
            }
        }
    }

    if (showRules) {
        CompanyRulesDialog(
            strings = strings,
            level = selectedItem.definition,
            onDismiss = { showRules = false },
        )
    }

    if (showRetentionRewards) {
        AlertDialog(
            shape = RoundedCornerShape(24.dp),
            containerColor = PageColors.Cream,
            titleContentColor = PageColors.Text,
            textContentColor = PageColors.Text,
            onDismissRequest = { showRetentionRewards = false },
            modifier = Modifier.testTag("company-retention-rewards-dialog"),
            title = { Text(strings.text("company.retention.title")) },
            text = {
                Column {
                    Text(strings.text("company.retention.daily.reward"))
                    TextButton(
                        modifier = Modifier.testTag("company-retention-daily-claim"),
                        enabled = retentionRewardStatus.dailyAvailable,
                        onClick = { onClaimRetentionReward(RetentionRewardType.DAILY) },
                    ) {
                        Text(
                            strings.text(
                                if (retentionRewardStatus.dailyAvailable) {
                                    "company.retention.claim"
                                } else {
                                    "company.retention.claimed"
                                },
                            ),
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(strings.text("company.retention.weekly.reward"))
                    TextButton(
                        modifier = Modifier.testTag("company-retention-weekly-claim"),
                        enabled = retentionRewardStatus.weeklyAvailable,
                        onClick = { onClaimRetentionReward(RetentionRewardType.WEEKLY) },
                    ) {
                        Text(
                            strings.text(
                                if (retentionRewardStatus.weeklyAvailable) {
                                    "company.retention.claim"
                                } else {
                                    "company.retention.claimed"
                                },
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRetentionRewards = false }) {
                    Text(strings.text("company.action.close"))
                }
            },
        )
    }

    rewardDialogState?.let { dialogState ->
        val reward = CampaignChapterRewardPolicy.rewardFor(selectedChapter)
        AlertDialog(
            shape = RoundedCornerShape(24.dp),
            containerColor = PageColors.Cream,
            titleContentColor = PageColors.Text,
            textContentColor = PageColors.Text,
            onDismissRequest = { rewardDialogState = null },
            title = { Text(strings.text("company.reward.dialog.title")) },
            text = {
                Text(
                    when (dialogState) {
                        ChapterRewardDialogState.LOCKED ->
                            strings.text("company.reward.dialog.locked")
                        ChapterRewardDialogState.CLAIMED ->
                            strings.text("company.reward.dialog.claimed")
                        ChapterRewardDialogState.COLLECTED ->
                            strings.text("company.reward.dialog.collected")
                                .replace("{coins}", reward.coins.toString())
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { rewardDialogState = null }) {
                    Text(strings.text("company.action.close"))
                }
            },
        )
    }
}

private enum class ChapterRewardDialogState {
    LOCKED,
    CLAIMED,
    COLLECTED,
}

@Composable
private fun CompanyMissionCard(
    strings: LocalizationProvider,
    item: CampaignLevelListItem,
    selectedLevel: Int,
    accessibleMaxLevel: Int,
    highestUnlockedLevel: Int,
    requiredStars: Int,
    first: Boolean,
    last: Boolean,
    compact: Boolean,
    onSelect: (Int) -> Unit,
) {
    val level = item.definition.levelNumber
    val isCompleted = item.progress.bestBackendRating > 0
    val isPlayable =
        isCompleted ||
            (
                level <= accessibleMaxLevel &&
                    level <= highestUnlockedLevel
                )

    CompanyMissionTimelineItem(
        strings = strings,
        item = item,
        selected = level == selectedLevel,
        completed = isCompleted,
        locked = !isPlayable,
        requiredStars = requiredStars,
        lockRequiresStars = level > accessibleMaxLevel,
        first = first,
        last = last,
        compact = compact,
        onSelect = { onSelect(level) },
    )
}
