package com.mirkori.inplacex.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.LocalizationProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRootScreen(
    currentLanguage: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit
) {
    val strings = LocalAppStrings.current
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = strings.text("settings.title"),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = strings.text("settings.description"),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
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
                    .menuAnchor()
                    .fillMaxWidth()
            )

            DropdownMenu(
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
    }
}

private fun languageLabel(language: AppLanguage, strings: LocalizationProvider): String {
    return when (language) {
        AppLanguage.RU -> strings.text("settings.language.ru")
        AppLanguage.EN -> strings.text("settings.language.en")
    }
}
