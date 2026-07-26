package com.mirkori.inplacex.ui.screens.company

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
                CompanyRulesAction(strings = strings, minimumHeight = 56, onRules = onRules)
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
