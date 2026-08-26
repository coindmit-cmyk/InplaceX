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
import com.mirkori.inplacex.ui.theme.PageColors
import com.mirkori.inplacex.ui.theme.PageType
import com.mirkori.inplacex.ui.screens.shared.StickyActionBar
import com.mirkori.inplacex.ui.screens.shared.PagePrimaryButton
import com.mirkori.inplacex.ui.screens.shared.PageSecondaryButton

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
    definition: CampaignLevelDefinition? = null,
) {
    StickyActionBar {
        // Small windows prioritize the route; the same objective remains in the rules dialog.
        definition?.takeUnless { compact }?.let {
            Text(strings.text(missionTitleKey(it)), style = PageType.CardTitle)
            Text(strings.text("company.mission.objective")
                .replace("{digits}", it.config.codeLength.toString())
                .replace("{moves}", it.config.attemptLimit.toString()),
                style = PageType.Secondary, color = PageColors.TextSecondary)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
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
            CompanyRulesAction(
                strings = strings,
                minimumHeight = 56,
                showLabel = false,
                onRules = onRules,
            )
        }
    }
}

@Composable
private fun CompanyRulesAction(
    strings: LocalizationProvider,
    minimumHeight: Int,
    showLabel: Boolean,
    onRules: () -> Unit,
) {
    PageSecondaryButton(
        onClick = onRules,
        modifier = Modifier.heightIn(min = minimumHeight.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.MenuBook,
            contentDescription = if (showLabel) null else strings.text("company.action.rules"),
            tint = PageColors.Primary,
            modifier = Modifier.size(if (showLabel) 20.dp else 26.dp),
        )
        if (showLabel) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = strings.text("company.action.rules"),
                color = InplaceXColors.ToyBlueDeep,
                fontWeight = FontWeight.Bold,
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
    val actionText = when {
        !playable && lockRequiresStars -> strings.text("company.action.need_stars")
            .replace("{value}", requiredStars.toString())

        !playable -> strings.text("company.scene.locked_level")
        !hasEnergy -> strings.text("company.action.restore_energy")
        else -> strings.text("company.action.play")
            .replace("{value}", levelNumber.toString())
    }
    val action = if (!hasEnergy && playable) onBuyEnergy else onPlay
    PagePrimaryButton(
        onClick = action,
        enabled = playable,
        accent = PageColors.Success,
        modifier = modifier.heightIn(min = 56.dp)
            .semantics { stateDescription = actionText }
            .testTag("company-play"),
    ) {
        Icon(if (playable) Icons.Outlined.Bolt else Icons.Outlined.Lock,
            contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(actionText, style = PageType.Button)
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
