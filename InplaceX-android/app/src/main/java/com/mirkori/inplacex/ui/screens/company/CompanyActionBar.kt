package com.mirkori.inplacex.ui.screens.company

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.campaign.CampaignLevelDefinition
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import com.mirkori.inplacex.ui.theme.InplaceXColors

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
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        color = InplaceXColors.ToyCream,
        border = BorderStroke(2.dp, InplaceXColors.ToyCreamShadow),
        shadowElevation = 3.dp,
    ) {
        if (compact) {
            Row(
                modifier = Modifier.padding(7.dp),
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
                CompanyRulesAction(strings = strings, minimumHeight = 56, onRules = onRules)
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
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
                CompanyRulesAction(strings = strings, minimumHeight = 48, onRules = onRules)
            }
        }
    }
}

@Composable
private fun CompanyRulesAction(
    strings: LocalizationProvider,
    minimumHeight: Int,
    onRules: () -> Unit,
) {
    TextButton(
        onClick = onRules,
        modifier = Modifier.heightIn(min = minimumHeight.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.MenuBook,
            contentDescription = null,
            tint = InplaceXColors.ToyBlue,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = strings.text("company.action.rules"),
            color = InplaceXColors.ToyBlueDeep,
            fontWeight = FontWeight.Bold,
        )
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
    val actionText = when {
        !playable && lockRequiresStars -> strings.text("company.action.need_stars")
            .replace("{value}", requiredStars.toString())

        !playable -> strings.text("company.scene.locked_level")
        !hasEnergy -> strings.text("company.action.restore_energy")
        else -> strings.text("company.action.play")
            .replace("{value}", levelNumber.toString())
    }
    val action = if (!hasEnergy && playable) onBuyEnergy else onPlay
    val actionBrush = if (playable) {
        Brush.verticalGradient(
            listOf(InplaceXColors.ToyGreenTop, InplaceXColors.ToyGreen),
        )
    } else {
        Brush.verticalGradient(
            listOf(Color(0xFFC9C0AF), Color(0xFF8F877B)),
        )
    }

    Surface(
        modifier = modifier
            .heightIn(min = 56.dp)
            .shadow(7.dp, RoundedCornerShape(18.dp))
            .semantics {
                role = Role.Button
                stateDescription = actionText
                if (!playable) disabled()
            }
            .clickable(
                enabled = playable,
                role = Role.Button,
                onClick = action,
            )
            .testTag("company-play"),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = BorderStroke(2.dp, if (playable) Color(0xFFB8F25D) else Color(0xFFD5CBB8)),
        shadowElevation = 3.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(actionBrush)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (playable) Icons.Outlined.Bolt else Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = actionText,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
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
