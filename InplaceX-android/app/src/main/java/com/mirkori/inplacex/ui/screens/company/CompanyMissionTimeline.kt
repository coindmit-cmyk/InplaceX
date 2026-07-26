package com.mirkori.inplacex.ui.screens.company

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.campaign.CampaignBlockRole
import com.mirkori.inplacex.core.campaign.CampaignDifficultyTier
import com.mirkori.inplacex.core.campaign.CampaignLevelDefinition
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import com.mirkori.inplacex.ui.theme.InplaceXColors

@Composable
internal fun CompanyMissionTimelineItem(
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
                    .replace("{value}", formatCampaignTime(definition.raceTimeLimitSeconds)),
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
