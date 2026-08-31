package com.mirkori.inplacex.ui.screens.company

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.campaign.CampaignLevelDefinition
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import com.mirkori.inplacex.ui.screens.shared.PagePrimaryButton
import com.mirkori.inplacex.ui.theme.PageColors
import com.mirkori.inplacex.ui.theme.PageType

@Composable
internal fun CompanyActionBar(
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
    definition: CampaignLevelDefinition,
    bestRating: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("company-level-card"),
        shape = RoundedCornerShape(16.dp),
        color = PageColors.Cream,
        contentColor = PageColors.Text,
        border = BorderStroke(1.dp, PageColors.Border),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(PageColors.Cream)
                .padding(horizontal = if (compact) 9.dp else 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics {
                        role = Role.Button
                        contentDescription = strings.text("company.action.rules")
                    }
                    .testTag("company-rules")
                    .clickable(role = Role.Button, onClick = onRules),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = strings.text("company.scene.level")
                        .replace("{value}", levelNumber.toString()),
                    style = if (compact) PageType.Body else PageType.CardTitle,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
                Text(
                    text = strings.text(missionTitleKey(definition)),
                    style = PageType.Body,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = strings.text("company.mission.objective")
                        .replace("{digits}", definition.config.codeLength.toString())
                        .replace("{moves}", definition.config.attemptLimit.toString()),
                    style = PageType.Secondary,
                    color = PageColors.TextSecondary,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = strings.text("company.mission.time")
                                .replace("{value}", formatCampaignTime(definition.raceTimeLimitSeconds)),
                            style = PageType.Secondary,
                            color = PageColors.TextSecondary,
                            maxLines = 1,
                        )
                        if (bestRating > 0) {
                            Text(
                                text = "${strings.text("company.scene.best")}: $bestRating",
                                style = PageType.Secondary,
                                color = PageColors.Success,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (!compact) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        repeat(definition.config.codeLength.coerceAtMost(4)) { index ->
                            Box(
                                modifier = Modifier
                                    .size(19.dp)
                                    .background(
                                        color = when (index % 4) {
                                            0 -> Color(0xFFE94857)
                                            1 -> Color(0xFFFFA800)
                                            2 -> Color(0xFF3CAE16)
                                            else -> PageColors.Primary
                                        },
                                        shape = RoundedCornerShape(5.dp),
                                    ),
                            )
                        }
                    }
                }
            }

            CompanyPrimaryAction(
                modifier = Modifier
                    .width(if (compact) 96.dp else 100.dp)
                    .height(if (compact) 80.dp else 70.dp),
                strings = strings,
                levelNumber = levelNumber,
                playable = playable,
                hasEnergy = hasEnergy,
                requiredStars = requiredStars,
                lockRequiresStars = lockRequiresStars,
                onBuyEnergy = onBuyEnergy,
                onPlay = onPlay,
            )
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
    val largeText = LocalDensity.current.fontScale > 1.3f
    val actionText = when {
        !playable && lockRequiresStars -> strings.text("company.action.need_stars")
            .replace("{value}", requiredStars.toString())
        !playable -> strings.text("company.scene.locked_level")
        !hasEnergy -> strings.text("company.action.restore_energy")
        else -> strings.text("company.action.play")
            .replace("{value}", levelNumber.toString())
    }
    val buttonLabel = when {
        !playable && lockRequiresStars -> strings.text("company.action.need_stars")
            .replace("{value}", requiredStars.toString())
        !playable -> strings.text("company.scene.locked_level")
        !hasEnergy -> strings.text("company.action.restore_energy")
        else -> strings.text("social.test_friend.play")
    }
    val action = if (!hasEnergy && playable) onBuyEnergy else onPlay

    PagePrimaryButton(
        onClick = action,
        enabled = playable,
        accent = Color(0xFF3FA91A),
        modifier = modifier
            .semantics { stateDescription = actionText }
            .testTag("company-play"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = buttonLabel,
                style = PageType.Button,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (playable && hasEnergy && !largeText) {
                Row(
                    modifier = Modifier.padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(1.toString(), style = PageType.Button)
                }
            } else if (!playable) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .size(18.dp),
                )
            }
        }
    }
}

@Composable
internal fun CompanyRulesDialog(
    strings: LocalizationProvider,
    level: CampaignLevelDefinition,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        shape = RoundedCornerShape(24.dp),
        containerColor = PageColors.Cream,
        titleContentColor = PageColors.Text,
        textContentColor = PageColors.Text,
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
                        .replace("{value}", formatCampaignTime(level.raceTimeLimitSeconds)),
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
