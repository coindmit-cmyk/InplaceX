package com.mirkori.inplacex.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun HomeRootScreen() {
    var screenState by remember { mutableStateOf(HomeScreenState.ROOT) }

    when (screenState) {
        HomeScreenState.ROOT -> {
            HomeSelectionScreen(
                onOpenPve = { screenState = HomeScreenState.PVE },
                onOpenPvp = { screenState = HomeScreenState.PVP }
            )
        }

        HomeScreenState.PVE -> {
            PveModesScreen(
                onBack = { screenState = HomeScreenState.ROOT }
            )
        }

        HomeScreenState.PVP -> {
            PvpModesScreen(
                onBack = { screenState = HomeScreenState.ROOT }
            )
        }
    }
}

@Composable
private fun HomeSelectionScreen(
    onOpenPve: () -> Unit,
    onOpenPvp: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(
                space = maxHeight * 0.03f,
                alignment = Alignment.CenterVertically
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Главный экран",
                style = MaterialTheme.typography.headlineMedium
            )

            Text(
                text = "Выбор между PvE и PvP режимами",
                style = MaterialTheme.typography.bodyLarge
            )

            Button(
                onClick = onOpenPve,
                modifier = Modifier.fillMaxWidth(fraction = 0.62f)
            ) {
                Text("PvE")
            }

            FilledTonalButton(
                onClick = onOpenPvp,
                modifier = Modifier.fillMaxWidth(fraction = 0.62f)
            ) {
                Text("PvP")
            }
        }
    }
}
