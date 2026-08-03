package com.mirkori.inplacex.ui.screens.company

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.mirkori.inplacex.core.campaign.CampaignChapterRewardPolicy
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.platform.localization.LocalizationProvider

@Composable
internal fun CompanySceneScreen(
    strings: LocalizationProvider,
    progressState: GameProgressState,
    levelItems: List<CampaignLevelListItem>,
    claimedChapterNumbers: Set<Int>,
    focusLevel: Int,
    accessibleMaxLevel: Int,
    totalStars: Int,
    requiredStarsForNextBlock: Int,
    nextBlockLocked: Boolean,
    onHistory: () -> Unit,
    onClaimChapterReward: (Int) -> Boolean,
    onBuyEnergy: () -> Unit,
    onPlay: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val displayItems = levelItems.asReversed()
    var selectedLevel by rememberSaveable { mutableIntStateOf(focusLevel) }
    var showRules by rememberSaveable { mutableStateOf(false) }
    var rewardDialogState by rememberSaveable {
        mutableStateOf<ChapterRewardDialogState?>(null)
    }
    val selectedItem =
        levelItems.firstOrNull { it.definition.levelNumber == selectedLevel }
            ?: levelItems.first { it.definition.levelNumber == focusLevel }
    val selectedCompleted = selectedItem.progress.bestBackendRating > 0
    val selectedChapter = selectedItem.definition.blockNumber
    val selectedChapterLevels = CampaignChapterRewardPolicy.levelRange(selectedChapter)
    val selectedChapterCompleted = selectedChapterLevels.all { levelNumber ->
        levelItems.firstOrNull { it.definition.levelNumber == levelNumber }
            ?.progress
            ?.bestBackendRating
            ?.let { it > 0 } == true
    }
    val selectedChapterRewardClaimed = selectedChapter in claimedChapterNumbers
    val chapterRewardLabel = strings.text(
        when {
            selectedChapterRewardClaimed -> "company.chapter.reward.claimed"
            selectedChapterCompleted -> "company.chapter.reward.available"
            else -> "company.chapter.reward"
        },
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
    val focusIndex = displayItems.indexOfFirst { it.definition.levelNumber == focusLevel }
        .coerceAtLeast(0)

    LaunchedEffect(focusLevel, levelItems.size) {
        selectedLevel = focusLevel
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compact = maxHeight < 650.dp || maxWidth < 360.dp
            val landscape = maxWidth > maxHeight
            val horizontalPadding = if (compact) 7.dp else 11.dp
            val landscapeCardWidth = (maxWidth - 22.dp) / 2f
            val selectedIndex = displayItems
                .indexOfFirst { it.definition.levelNumber == selectedLevel }
                .coerceAtLeast(focusIndex)

            LaunchedEffect(selectedLevel, displayItems.size, landscape) {
                val targetIndex = if (landscape) {
                    (selectedIndex - 1).coerceAtLeast(0)
                } else {
                    (selectedIndex - 1).coerceAtLeast(0)
                }
                listState.animateScrollToItem(targetIndex)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = 4.dp),
            ) {
                CompanyScreenHeader(
                    strings = strings,
                    energy = progressState.campaignEnergy,
                    energyMax = progressState.campaignEnergyMax,
                    compact = compact,
                    chapterRewardLabel = chapterRewardLabel.takeIf { landscape },
                    onChapterReward = openChapterReward.takeIf { landscape },
                    onHistory = onHistory,
                    onBuyEnergy = onBuyEnergy,
                )
                Spacer(modifier = Modifier.height(if (compact) 4.dp else 7.dp))

                if (!landscape) {
                    CompanyChapterHero(
                        strings = strings,
                        chapter = selectedItem.definition.blockNumber,
                        totalStars = totalStars,
                        requiredStars = requiredStarsForNextBlock,
                        nextBlockLocked = nextBlockLocked,
                        rewardAvailable = selectedChapterCompleted,
                        rewardClaimed = selectedChapterRewardClaimed,
                        onRewardClick = openChapterReward,
                        compact = compact,
                    )
                    Spacer(modifier = Modifier.height(if (compact) 5.dp else 8.dp))
                }

                if (landscape) {
                    LazyRow(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("company-mission-list"),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            top = 5.dp,
                            bottom = 3.dp,
                        ),
                    ) {
                        itemsIndexed(
                            items = displayItems,
                            key = { _, item -> item.definition.levelNumber },
                        ) { index, item ->
                            Box(modifier = Modifier.width(landscapeCardWidth)) {
                                CompanyMissionCard(
                                    strings = strings,
                                    item = item,
                                    selectedLevel = selectedLevel,
                                    accessibleMaxLevel = accessibleMaxLevel,
                                    highestUnlockedLevel = progressState.highestUnlockedCampaignLevel,
                                    requiredStars = requiredStarsForNextBlock,
                                    first = index == 0,
                                    last = index == displayItems.lastIndex,
                                    compact = true,
                                    onSelect = { selectedLevel = it },
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("company-mission-list"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            top = 7.dp,
                            bottom = 7.dp,
                        ),
                    ) {
                        itemsIndexed(
                            items = displayItems,
                            key = { _, item -> item.definition.levelNumber },
                        ) { index, item ->
                            CompanyMissionCard(
                                strings = strings,
                                item = item,
                                selectedLevel = selectedLevel,
                                accessibleMaxLevel = accessibleMaxLevel,
                                highestUnlockedLevel = progressState.highestUnlockedCampaignLevel,
                                requiredStars = requiredStarsForNextBlock,
                                first = index == 0,
                                last = index == displayItems.lastIndex,
                                compact = compact,
                                onSelect = { selectedLevel = it },
                            )
                        }
                    }
                }

                CompanyActionBar(
                    strings = strings,
                    levelNumber = selectedLevel,
                    playable = selectedPlayable,
                    hasEnergy = hasEnergy,
                    requiredStars = requiredStarsForNextBlock,
                    lockRequiresStars = selectedLevel > accessibleMaxLevel,
                    onBuyEnergy = onBuyEnergy,
                    onPlay = { onPlay(selectedLevel) },
                    onRules = { showRules = true },
                    compact = compact,
                )
            }
    }

    if (showRules) {
        CompanyRulesDialog(
            strings = strings,
            level = selectedItem.definition,
            onDismiss = { showRules = false },
        )
    }

    rewardDialogState?.let { dialogState ->
        val reward = CampaignChapterRewardPolicy.rewardFor(selectedChapter)
        AlertDialog(
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
