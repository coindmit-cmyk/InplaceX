package com.mirkori.inplacex.ui.screens.company

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.campaign.CampaignBlockRole
import com.mirkori.inplacex.core.campaign.CampaignDifficultyTier
import com.mirkori.inplacex.core.campaign.CampaignLevelDefinition
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import com.mirkori.inplacex.ui.theme.InplaceXColors
import com.mirkori.inplacex.ui.theme.PageColors
import com.mirkori.inplacex.ui.theme.PageType

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
    val stateText = when {
        locked -> strings.text("company.state.locked")
        completed -> strings.text("company.state.completed")
        else -> strings.text("company.scene.unlocked")
    }
    val accentColor = when {
        selected -> InplaceXColors.ToyOrange
        completed -> InplaceXColors.ToyGreen
        locked -> Color(0xFF8F8A80)
        else -> InplaceXColors.ToyBlue
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        TimelineLevelBadge(
            levelNumber = definition.levelNumber,
            selected = selected,
            completed = completed,
            locked = locked,
            first = first,
            last = last,
            compact = compact,
        )

        Surface(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 8.dp)

                .semantics {
                    this.selected = selected
                    stateDescription = stateText
                    role = Role.Button
                }
                .testTag("company-level-${definition.levelNumber}")
                .clickable(role = Role.Button, onClick = onSelect),
            shape = RoundedCornerShape(20.dp),
            color = PageColors.Cream,
            border = BorderStroke(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) InplaceXColors.ToyOrangeTop else PageColors.Border,
            ),
            shadowElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Brush.horizontalGradient(
                            colors = if (selected) {
                                listOf(Color(0xFFFFF8E8), Color(0xFFFFEAC0))
                            } else {
                                listOf(PageColors.Cream, Color(0xFFFFE9C3))
                            },
                        ),
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.text(missionTitleKey(definition)),
                            color = PageColors.Text,
                            style = PageType.CardTitle,
                            fontWeight = FontWeight.Black,
                            maxLines = 2,
                        )
                        Text(
                            text = strings.text(difficultyKey(definition.difficultyTier)),
                            color = difficultyColor(definition.difficultyTier),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    when {
                        completed -> Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = stateText,
                            tint = InplaceXColors.ToyGreen,
                            modifier = Modifier.size(28.dp),
                        )

                        locked -> Surface(
                            modifier = Modifier.size(30.dp),
                            shape = CircleShape,
                            color = Color(0xFFE1D3BA),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Lock,
                                    contentDescription = stateText,
                                    tint = Color(0xFF675F54),
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        }

                        selected -> Surface(
                            modifier = Modifier.size(28.dp),
                            shape = CircleShape,
                            color = InplaceXColors.ToyOrangeTop,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("▶", color = Color.White, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }

                MissionCodePreview(
                    definition = definition,
                    completed = completed,
                    locked = locked,
                    selected = selected,
                )

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
                        color = Color(0xFF6E6559),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else if (selected) {
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
private fun TimelineLevelBadge(
    levelNumber: Int,
    selected: Boolean,
    completed: Boolean,
    locked: Boolean,
    first: Boolean,
    last: Boolean,
    compact: Boolean,
) {
    val badgeTop = when {
        selected -> PageColors.Company
        completed -> PageColors.CreamSecondary
        locked -> Color(0xFFB2A58D)
        else -> PageColors.CreamSecondary
    }
    val badgeBottom = when {
        selected -> PageColors.Company
        completed -> PageColors.CreamSecondary
        locked -> Color(0xFF776F64)
        else -> PageColors.CreamSecondary
    }

    Column(
        modifier = Modifier
            .width(if (compact) 56.dp else 64.dp)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(8.dp)
                .background(if (first) Color.Transparent else Color.White.copy(alpha = 0.76f)),
        )
        Surface(
            modifier = Modifier
                .size(if (selected) 54.dp else 48.dp)
,
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            border = BorderStroke(
                if (selected) 3.dp else 2.dp,
                if (selected) Color(0xFFFFD34E) else Color.White.copy(alpha = 0.72f),
            ),
            shadowElevation = 4.dp,
        ) {
            Box(
                modifier = Modifier.background(
                    Brush.verticalGradient(listOf(badgeTop, badgeBottom)),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = levelNumber.toString(),
                    color = if (locked) Color.White else PageColors.Text,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                )
            }
        }
        Box(
            modifier = Modifier
                .width(4.dp)
                .weight(1f)
                .background(if (last) Color.Transparent else Color.White.copy(alpha = 0.76f)),
        )
    }
}

@Composable
private fun MissionCodePreview(
    definition: CampaignLevelDefinition,
    completed: Boolean,
    locked: Boolean,
    selected: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(definition.config.codeLength.coerceAtMost(4)) { index ->
                val digit = if (locked) {
                    "?"
                } else {
                    ((definition.levelNumber + index * 3) % 10).toString()
                }
                val tileColor = when (index % 4) {
                    0 -> Color(0xFFE94857)
                    1 -> Color(0xFFFFA800)
                    2 -> Color(0xFF3CAE16)
                    else -> InplaceXColors.ToyBlue
                }
                Surface(
                    modifier = Modifier.size(31.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = if (locked) Color(0xFF55515A) else tileColor,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)),
                    shadowElevation = 2.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = digit,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(4) { index ->
                val color = when {
                    completed && index < 3 -> InplaceXColors.ToyGreen
                    selected && index == 0 -> InplaceXColors.ToyOrange
                    else -> Color(0xFFB9AC99)
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(color, CircleShape),
                )
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
                tint = if (index < stars) InplaceXColors.ToyOrangeTop else Color(0xFFB7AA98),
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(PageColors.Border),
        )
        Text(
            text = strings.text("company.mission.objective")
                .replace("{digits}", definition.config.codeLength.toString())
                .replace("{moves}", definition.config.attemptLimit.toString()),
            color = PageColors.Text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = strings.text("company.mission.time")
                    .replace("{value}", formatCampaignTime(definition.raceTimeLimitSeconds)),
                color = InplaceXColors.ToyBlueDeep,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = strings.text(
                    if (definition.config.allowDuplicates) {
                        "company.mission.repeats.allowed"
                    } else {
                        "company.mission.repeats.forbidden"
                    },
                ),
                color = Color(0xFF746958),
                style = MaterialTheme.typography.labelMedium,
            )
            if (bestRating > 0) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = strings.text("company.mission.best")
                        .replace("{value}", bestRating.toString()),
                    color = InplaceXColors.ToyGreen,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
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
        CampaignDifficultyTier.EASY -> InplaceXColors.ToyGreen
        CampaignDifficultyTier.MEDIUM -> InplaceXColors.ToyBlue
        CampaignDifficultyTier.HARD -> InplaceXColors.ToyOrange
        CampaignDifficultyTier.HARDCORE -> InplaceXColors.ToyRed
    }
}
