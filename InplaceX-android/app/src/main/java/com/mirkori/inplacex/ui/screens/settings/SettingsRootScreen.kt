package com.mirkori.inplacex.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import com.mirkori.inplacex.VariantSettingsToolsAction
import com.mirkori.inplacex.platform.ads.AdConsentDecision
import com.mirkori.inplacex.platform.feedback.AppFeedbackSettings
import com.mirkori.inplacex.platform.web.MirkoriWebsitePage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRootScreen(
    currentLanguage: AppLanguage,
    adConsentDecision: AdConsentDecision,
    feedbackSettings: AppFeedbackSettings,
    onLanguageChange: (AppLanguage) -> Unit,
    onVibrationChange: (Boolean) -> Unit,
    onSoundChange: (Boolean) -> Unit,
    onMusicChange: (Boolean) -> Unit,
    onOpenAdPrivacy: () -> Unit,
    onOpenWebsitePage: (MirkoriWebsitePage) -> Unit,
    onOpenInternalTools: () -> Unit,
    onClose: () -> Unit
) {
    val strings = LocalAppStrings.current
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(strings.text("top.back"))
            }
        },
        title = {
            Text(text = strings.text("settings.title"))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = strings.text("settings.description"),
                    textAlign = TextAlign.Center
                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = languageLabel(currentLanguage, strings),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(strings.text("settings.language")) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(strings.text("settings.language.ru")) },
                            onClick = {
                                expanded = false
                                onLanguageChange(AppLanguage.RU)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(strings.text("settings.language.en")) },
                            onClick = {
                                expanded = false
                                onLanguageChange(AppLanguage.EN)
                            }
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = strings.text("settings.feedback.title"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    SettingsToggleRow(
                        title = strings.text("settings.vibration"),
                        description = strings.text("settings.vibration.description"),
                        checked = feedbackSettings.vibrationEnabled,
                        onCheckedChange = onVibrationChange,
                    )
                    SettingsToggleRow(
                        title = strings.text("settings.sound"),
                        description = strings.text("settings.sound.description"),
                        checked = feedbackSettings.soundEnabled,
                        onCheckedChange = onSoundChange,
                    )
                    SettingsToggleRow(
                        title = strings.text("settings.music"),
                        description = strings.text("settings.music.description"),
                        checked = feedbackSettings.musicEnabled,
                        onCheckedChange = onMusicChange,
                    )
                }

                TextButton(
                    onClick = onOpenAdPrivacy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        strings.text("settings.ad_privacy") + ": " +
                            strings.text(adConsentDecision.localizationKey()),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = strings.text("settings.information.title"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    SettingsLinkRow(
                        title = strings.text("settings.contact"),
                        onClick = { onOpenWebsitePage(MirkoriWebsitePage.CONTACT) },
                    )
                    SettingsLinkRow(
                        title = strings.text("settings.terms"),
                        onClick = { onOpenWebsitePage(MirkoriWebsitePage.TERMS) },
                    )
                    SettingsLinkRow(
                        title = strings.text("settings.privacy"),
                        onClick = { onOpenWebsitePage(MirkoriWebsitePage.PRIVACY) },
                    )
                    SettingsLinkRow(
                        title = strings.text("settings.about"),
                        onClick = { onOpenWebsitePage(MirkoriWebsitePage.ABOUT) },
                    )
                    SettingsLinkRow(
                        title = strings.text("settings.open_source_licenses"),
                        onClick = { onOpenWebsitePage(MirkoriWebsitePage.OPEN_SOURCE_LICENSES) },
                    )
                }

                VariantSettingsToolsAction(onOpen = onOpenInternalTools)
            }
        }
    )
}

@Composable
private fun SettingsLinkRow(
    title: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        onClick = { onCheckedChange(!checked) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

private fun AdConsentDecision.localizationKey(): String = when (this) {
    AdConsentDecision.UNDECIDED -> "ads.privacy.status.undecided"
    AdConsentDecision.ACCEPTED -> "ads.privacy.status.accepted"
    AdConsentDecision.DECLINED -> "ads.privacy.status.declined"
}

private fun languageLabel(language: AppLanguage, strings: LocalizationProvider): String {
    return when (language) {
        AppLanguage.RU -> strings.text("settings.language.ru")
        AppLanguage.EN -> strings.text("settings.language.en")
    }
}
