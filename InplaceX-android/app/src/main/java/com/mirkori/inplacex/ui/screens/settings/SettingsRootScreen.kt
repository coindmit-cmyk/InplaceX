package com.mirkori.inplacex.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import com.mirkori.inplacex.VariantSettingsToolsAction
import com.mirkori.inplacex.platform.ads.AdConsentDecision

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRootScreen(
    currentLanguage: AppLanguage,
    adConsentDecision: AdConsentDecision,
    onLanguageChange: (AppLanguage) -> Unit,
    onOpenAdPrivacy: () -> Unit,
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
                modifier = Modifier.fillMaxWidth(),
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

                TextButton(
                    onClick = onOpenAdPrivacy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        strings.text("settings.ad_privacy") + ": " +
                            strings.text(adConsentDecision.localizationKey()),
                    )
                }

                VariantSettingsToolsAction(onOpen = onOpenInternalTools)
            }
        }
    )
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
