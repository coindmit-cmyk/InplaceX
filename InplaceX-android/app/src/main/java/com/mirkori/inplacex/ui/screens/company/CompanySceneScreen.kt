package com.mirkori.inplacex.ui.screens.company

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.campaign.CampaignBlockRole
import com.mirkori.inplacex.core.campaign.CampaignDifficultyTier
import com.mirkori.inplacex.core.campaign.CampaignLevelDefinition
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import com.mirkori.inplacex.ui.theme.InplaceXColors

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
                    val isLocked = !isPlayable

                    CompanyMissionTimelineItem(
                        strings = strings,
                        item = item,
                        selected = level == selectedLevel,
                        completed = isCompleted,
                        locked = isLocked,
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

@Composable
private fun CompanyScreenHeader(
    strings: LocalizationProvider,
    energy: Int,
    energyMax: Int,
    compact: Boolean,
    onHistory: () -> Unit,
    onBuyEnergy: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = strings.text("company.title"),
                style = if (compact) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                color = InplaceXColors.White,
                fontWeight = FontWeight.Bold,
            )
            if (!compact) {
                Text(
                    text = strings.text("company.scene.subtitle"),
                    style = MaterialTheme.typography.bodySmall,
                    color = InplaceXColors.SurfaceMuted,
                )
            }
        }

        Surface(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(onClick = onHistory)
                .testTag("company-history"),
            shape = RoundedCornerShape(16.dp),
            color = InplaceXColors.NavySurface.copy(alpha = 0.92f),
            border = BorderStroke(1.dp, InplaceXColors.Cobalt.copy(alpha = 0.32f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = strings.text("company.scene.history"),
                    tint = InplaceXColors.Cyan,
                    modifier = Modifier.size(20.dp),
                )
                if (!compact) {
                    Text(
                        text = strings.text("company.scene.history"),
                        color = InplaceXColors.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Surface(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(onClick = onBuyEnergy)
                .testTag("company-energy"),
            shape = RoundedCornerShape(16.dp),
            color = InplaceXColors.Cobalt.copy(alpha = 0.18f),
            border = BorderStroke(1.dp, InplaceXColors.Cyan.copy(alpha = 0.42f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Bolt,
                    contentDescription = strings.text("company.scene.energy"),
                    tint = InplaceXColors.Cyan,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = "$energy/$energyMax",
                    color = InplaceXColors.White,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun CompanyChapterHero(
    strings: LocalizationProvider,
    chapter: Int,
    totalStars: Int,
    requiredStars: Int,
    nextBlockLocked: Boolean,
    compact: Boolean,
) {
    val target = requiredStars.coerceAtLeast(1)
    val progress = (totalStars.toFloat() / target.toFloat()).coerceIn(0f, 1f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 20.dp else 24.dp),
        color = InplaceXColors.MidnightElevated.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, InplaceXColors.Cobalt.copy(alpha = 0.44f)),
    ) {
        Row(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            InplaceXColors.Cobalt.copy(alpha = 0.16f),
                            Color.Transparent,
                            InplaceXColors.Amber.copy(alpha = 0.08f),
                        ),
                    ),
                )
                .padding(if (compact) 14.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.text("company.chapter.number")
                        .replace("{value}", chapter.toString()),
                    style = MaterialTheme.typography.labelLarge,
                    color = InplaceXColors.Cyan,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = strings.text("company.chapter.title"),
                    style = if (compact) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                    color = InplaceXColors.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = null,
                        tint = InplaceXColors.Amber,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = strings.text("company.chapter.progress")
                            .replace("{current}", totalStars.toString())
                            .replace("{required}", requiredStars.toString()),
                        color = InplaceXColors.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(InplaceXColors.NavySurface),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(InplaceXColors.Amber, InplaceXColors.Cyan),
                                ),
                            ),
                    )
                }
                Text(
                    text = if (nextBlockLocked) {
                        strings.text("company.scene.locked")
                    } else {
                        strings.text("company.scene.unlocked")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = InplaceXColors.SurfaceMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    modifier = Modifier.size(if (compact) 52.dp else 64.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = InplaceXColors.Amber.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, InplaceXColors.Amber.copy(alpha = 0.5f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.EmojiEvents,
                            contentDescription = null,
                            tint = InplaceXColors.Amber,
                            modifier = Modifier.size(if (compact) 30.dp else 36.dp),
                        )
                    }
                }
                if (!compact) {
                    Text(
                        text = strings.text("company.chapter.reward"),
                        style = MaterialTheme.typography.labelSmall,
                        color = InplaceXColors.SurfaceMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompanyMissionTimelineItem(
    strings: LocalizationProvider,
    item: CampaignLevelListItem,
    selected: Boolean,
    completed: Boolean,
    locked: Boolean,
    requiredStars: Int,
    lockRequiresStars: Boolean,
    first: Boolean,
    last: Boolean,
    compact: Boolean,
    onSelect: () -> Unit,
) {
    val definition = item.definition
    val stateColor = when {
        locked -> InplaceXColors.SurfaceMuted
        completed -> InplaceXColors.Mint
        else -> InplaceXColors.Cobalt
    }
    val borderColor = if (selected) InplaceXColors.Cyan else stateColor.copy(alpha = 0.42f)
    val missionSurface = if (selected) {
        InplaceXColors.Cobalt.copy(alpha = 0.18f)
    } else {
        InplaceXColors.MidnightElevated.copy(alpha = 0.88f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .testTag("company-level-${definition.levelNumber}"),
    ) {
        Column(
            modifier = Modifier
                .width(if (compact) 52.dp else 62.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(10.dp)
                    .background(if (first) Color.Transparent else stateColor.copy(alpha = 0.5f)),
            )
            Surface(
                modifier = Modifier.size(if (selected) 52.dp else 46.dp),
                shape = RoundedCornerShape(if (selected) 17.dp else 15.dp),
                color = if (locked) {
                    InplaceXColors.NavySurface
                } else {
                    stateColor.copy(alpha = 0.18f)
                },
                border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (locked) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = strings.text("company.state.locked"),
                            tint = stateColor,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        Text(
                            text = definition.levelNumber.toString(),
                            color = InplaceXColors.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(if (last) Color.Transparent else stateColor.copy(alpha = 0.5f)),
            )
        }

        Surface(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 10.dp)
                .clickable(onClick = onSelect),
            shape = RoundedCornerShape(if (selected) 20.dp else 16.dp),
            color = missionSurface,
            border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        ) {
            Column(
                modifier = Modifier.padding(if (compact) 12.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.text(missionTitleKey(definition)),
                            color = if (locked) {
                                InplaceXColors.SurfaceMuted
                            } else {
                                InplaceXColors.White
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = strings.text(difficultyKey(definition.difficultyTier)),
                            color = difficultyColor(definition.difficultyTier),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    when {
                        completed -> Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = strings.text("company.state.completed"),
                            tint = InplaceXColors.Mint,
                        )

                        locked -> Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = strings.text("company.state.locked"),
                            tint = InplaceXColors.SurfaceMuted,
                        )
                    }
                }

                if (completed) {
                    MissionStars(stars = starsForRating(item.progress.bestBackendRating))
                }

                if (locked) {
                    Text(
                        text = if (lockRequiresStars) {
                            strings.text("company.lock.stars")
                                .replace("{value}", requiredStars.toString())
                        } else {
                            strings.text("company.lock.previous")
                        },
                        color = InplaceXColors.SurfaceMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (selected) {
                    MissionDetails(
                        strings = strings,
                        definition = definition,
                        bestRating = item.progress.bestBackendRating,
                    )
                }
            }
        }
    }
}

@Composable
private fun MissionStars(stars: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { index ->
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription = null,
                tint = if (index < stars) InplaceXColors.Amber else InplaceXColors.Outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun MissionDetails(
    strings: LocalizationProvider,
    definition: CampaignLevelDefinition,
    bestRating: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(InplaceXColors.Cobalt.copy(alpha = 0.32f)),
        )
        Text(
            text = strings.text("company.mission.objective")
                .replace("{digits}", definition.config.codeLength.toString())
                .replace("{moves}", definition.config.attemptLimit.toString()),
            color = InplaceXColors.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MissionFactChip(
                modifier = Modifier.weight(1f),
                text = strings.text("company.mission.time")
                    .replace("{value}", formatTime(definition.raceTimeLimitSeconds)),
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Timer,
                        contentDescription = null,
                        tint = InplaceXColors.Cyan,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            MissionFactChip(
                modifier = Modifier.weight(1f),
                text = strings.text(
                    if (definition.config.allowDuplicates) {
                        "company.mission.repeats.allowed"
                    } else {
                        "company.mission.repeats.forbidden"
                    },
                ),
            )
        }
        Text(
            text = if (bestRating > 0) {
                strings.text("company.mission.best")
                    .replace("{value}", bestRating.toString())
            } else {
                strings.text("company.mission.best.empty")
            },
            color = if (bestRating > 0) InplaceXColors.Cyan else InplaceXColors.SurfaceMuted,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun MissionFactChip(
    modifier: Modifier = Modifier,
    text: String,
    icon: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier.heightIn(min = 40.dp),
        shape = RoundedCornerShape(12.dp),
        color = InplaceXColors.NavySurface.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, InplaceXColors.Outline.copy(alpha = 0.34f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            icon?.invoke()
            Text(
                text = text,
                color = InplaceXColors.SurfaceMuted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun CompanyActionBar(
    strings: LocalizationProvider,
    levelNumber: Int,
    playable: Boolean,
    hasEnergy: Boolean,
    requiredStars: Int,
    lockRequiresStars: Boolean,
    onBuyEnergy: () -> Unit,
    onPlay: () -> Unit,
    onRules: () -> Unit,
    compact: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = InplaceXColors.Midnight.copy(alpha = 0.96f),
    ) {
        if (compact) {
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompanyPrimaryAction(
                    modifier = Modifier.weight(1f),
                    strings = strings,
                    levelNumber = levelNumber,
                    playable = playable,
                    hasEnergy = hasEnergy,
                    requiredStars = requiredStars,
                    lockRequiresStars = lockRequiresStars,
                    onBuyEnergy = onBuyEnergy,
                    onPlay = onPlay,
                )
                TextButton(
                    onClick = onRules,
                    modifier = Modifier.heightIn(min = 56.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = null,
                        tint = InplaceXColors.Cyan,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = strings.text("company.action.rules"),
                        color = InplaceXColors.Cyan,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CompanyPrimaryAction(
                    modifier = Modifier.fillMaxWidth(),
                    strings = strings,
                    levelNumber = levelNumber,
                    playable = playable,
                    hasEnergy = hasEnergy,
                    requiredStars = requiredStars,
                    lockRequiresStars = lockRequiresStars,
                    onBuyEnergy = onBuyEnergy,
                    onPlay = onPlay,
                )
                TextButton(
                    onClick = onRules,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = null,
                        tint = InplaceXColors.Cyan,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = strings.text("company.action.rules"),
                        color = InplaceXColors.Cyan,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompanyPrimaryAction(
    modifier: Modifier,
    strings: LocalizationProvider,
    levelNumber: Int,
    playable: Boolean,
    hasEnergy: Boolean,
    requiredStars: Int,
    lockRequiresStars: Boolean,
    onBuyEnergy: () -> Unit,
    onPlay: () -> Unit,
) {
            Button(
                onClick = if (!hasEnergy && playable) onBuyEnergy else onPlay,
                enabled = playable,
                modifier = modifier
                    .heightIn(min = 56.dp)
                    .testTag("company-play"),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = InplaceXColors.Cobalt,
                    contentColor = InplaceXColors.White,
                    disabledContainerColor = InplaceXColors.NavySurface,
                    disabledContentColor = InplaceXColors.SurfaceMuted,
                ),
            ) {
                Icon(
                    imageVector = if (playable) Icons.Outlined.Bolt else Icons.Outlined.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        !playable && lockRequiresStars -> strings.text("company.action.need_stars")
                            .replace("{value}", requiredStars.toString())

                        !playable -> strings.text("company.scene.locked_level")
                        !hasEnergy -> strings.text("company.action.restore_energy")
                        else -> strings.text("company.action.play")
                            .replace("{value}", levelNumber.toString())
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
}

@Composable
private fun CompanyRulesDialog(
    strings: LocalizationProvider,
    level: CampaignLevelDefinition,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = strings.text("company.rules.title")
                    .replace("{value}", level.levelNumber.toString()),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    strings.text("company.mission.objective")
                        .replace("{digits}", level.config.codeLength.toString())
                        .replace("{moves}", level.config.attemptLimit.toString()),
                )
                Text(
                    strings.text("company.rules.time")
                        .replace("{value}", formatTime(level.raceTimeLimitSeconds)),
                )
                Text(
                    strings.text(
                        if (level.config.allowDuplicates) {
                            "company.rules.repeats.allowed"
                        } else {
                            "company.rules.repeats.forbidden"
                        },
                    ),
                )
                Text(strings.text("company.rules.rating"))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.text("company.action.close"))
            }
        },
    )
}

private fun missionTitleKey(level: CampaignLevelDefinition): String {
    return when (level.blockRole) {
        CampaignBlockRole.STANDARD -> "company.mission.title.standard"
        CampaignBlockRole.SPIKE -> "company.mission.title.spike"
        CampaignBlockRole.HARDCORE -> "company.mission.title.hardcore"
    }
}

private fun difficultyKey(tier: CampaignDifficultyTier): String {
    return when (tier) {
        CampaignDifficultyTier.EASY -> "company.difficulty.easy"
        CampaignDifficultyTier.MEDIUM -> "company.difficulty.medium"
        CampaignDifficultyTier.HARD -> "company.difficulty.hard"
        CampaignDifficultyTier.HARDCORE -> "company.difficulty.hardcore"
    }
}

private fun difficultyColor(tier: CampaignDifficultyTier): Color {
    return when (tier) {
        CampaignDifficultyTier.EASY -> InplaceXColors.Mint
        CampaignDifficultyTier.MEDIUM -> InplaceXColors.Cyan
        CampaignDifficultyTier.HARD -> InplaceXColors.Amber
        CampaignDifficultyTier.HARDCORE -> InplaceXColors.Coral
    }
}

private fun formatTime(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
