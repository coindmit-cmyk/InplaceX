package com.mirkori.inplacex.ui.screens.company

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.platform.localization.LocalizationProvider

@Composable
internal fun CompanySceneScreen(
    strings: LocalizationProvider,
    progressState: GameProgressState,
    levelItems: List<CampaignLevelListItem>,
    focusLevel: Int,
    accessibleMaxLevel: Int,
    totalStars: Int,
    requiredStarsForNextBlock: Int,
    nextBlockLocked: Boolean,
    onHistory: () -> Unit,
    onBuyEnergy: () -> Unit,
    onPlay: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    var selectedLevel by rememberSaveable { mutableIntStateOf(focusLevel) }
    var showRules by rememberSaveable { mutableStateOf(false) }
    val selectedItem =
        levelItems.firstOrNull { it.definition.levelNumber == selectedLevel }
            ?: levelItems.first { it.definition.levelNumber == focusLevel }
    val selectedCompleted = selectedItem.progress.bestBackendRating > 0
    val selectedPlayable =
        selectedCompleted ||
            (
                selectedLevel <= accessibleMaxLevel &&
                    selectedLevel <= progressState.highestUnlockedCampaignLevel
                )
    val hasEnergy = progressState.campaignEnergy > 0
    val focusIndex = levelItems.indexOfFirst { it.definition.levelNumber == focusLevel }
        .coerceAtLeast(0)

    LaunchedEffect(focusLevel, levelItems.size) {
        selectedLevel = focusLevel
    }

    LaunchedEffect(selectedLevel, levelItems.size) {
        val selectedIndex = levelItems
            .indexOfFirst { it.definition.levelNumber == selectedLevel }
            .coerceAtLeast(focusIndex)
        // The chapter hero is item 0; mission N starts at lazy-list index N.
        listState.animateScrollToItem((selectedIndex + 1).coerceAtMost(levelItems.size))
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxHeight < 650.dp || maxWidth < 360.dp
        val horizontalPadding = if (compact) 10.dp else 16.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding, vertical = 6.dp),
        ) {
            CompanyScreenHeader(
                strings = strings,
                energy = progressState.campaignEnergy,
                energyMax = progressState.campaignEnergyMax,
                compact = compact,
                onHistory = onHistory,
                onBuyEnergy = onBuyEnergy,
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("company-mission-list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 8.dp,
                    bottom = 12.dp,
                ),
            ) {
                item(key = "chapter-hero") {
                    CompanyChapterHero(
                        strings = strings,
                        chapter = selectedItem.definition.blockNumber,
                        totalStars = totalStars,
                        requiredStars = requiredStarsForNextBlock,
                        nextBlockLocked = nextBlockLocked,
                        compact = compact,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                itemsIndexed(
                    items = levelItems,
                    key = { _, item -> item.definition.levelNumber },
                ) { index, item ->
                    val level = item.definition.levelNumber
                    val isCompleted = item.progress.bestBackendRating > 0
                    val isPlayable =
                        isCompleted ||
                            (
                                level <= accessibleMaxLevel &&
                                    level <= progressState.highestUnlockedCampaignLevel
                                )

                    CompanyMissionTimelineItem(
                        strings = strings,
                        item = item,
                        selected = level == selectedLevel,
                        completed = isCompleted,
                        locked = !isPlayable,
                        requiredStars = requiredStarsForNextBlock,
                        lockRequiresStars = level > accessibleMaxLevel,
                        first = index == 0,
                        last = index == levelItems.lastIndex,
                        compact = compact,
                        onSelect = { selectedLevel = level },
                    )
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
}
