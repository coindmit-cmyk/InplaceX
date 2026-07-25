package com.mirkori.inplacex.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mirkori.inplacex.platform.localization.LocalAppStrings

@Composable
fun PveModesScreen(
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(
                space = maxHeight * 0.025f,
                alignment = Alignment.CenterVertically
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = strings.text("home.pve.screen.title"),
                style = MaterialTheme.typography.headlineSmall
            )

            Text(
                text = strings.text("home.pve.screen.description"),
                style = MaterialTheme.typography.bodyLarge
            )

            FilledTonalButton(
                onClick = { },
                modifier = Modifier.fillMaxWidth(fraction = 0.68f)
            ) {
                Text(strings.text("home.pve.race"))
            }

            FilledTonalButton(
                onClick = { },
                modifier = Modifier.fillMaxWidth(fraction = 0.68f)
            ) {
                Text(strings.text("home.pve.campaign"))
            }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(fraction = 0.42f)
            ) {
                Text(strings.text("top.back"))
            }
        }
    }
}
