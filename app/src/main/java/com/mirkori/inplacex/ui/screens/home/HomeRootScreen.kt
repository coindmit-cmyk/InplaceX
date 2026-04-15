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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mirkori.inplacex.core.model.GameModeDefinition
import com.mirkori.inplacex.platform.config.AppConfigCatalog
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.screens.game.GameFieldParams
import com.mirkori.inplacex.ui.screens.game.GameFieldScreen
import com.mirkori.inplacex.ui.screens.game.TypeGame

@Composable
fun HomeRootScreen(
    requestExitGame: Boolean = false,
    onExitGameConsumed: () -> Unit = {},
    onInGameChange: (Boolean) -> Unit = {}
) {
    var screenState by remember { mutableStateOf(HomeScreenState.ROOT) }
    val pveMode = AppConfigCatalog.gameModes.first { it.id == "pve_race" }
    val pvpMode = AppConfigCatalog.gameModes.first { it.id == "pvp_duel" }
    val strings = LocalAppStrings.current

    LaunchedEffect(screenState) {
        onInGameChange(screenState != HomeScreenState.ROOT)
    }

    LaunchedEffect(requestExitGame) {
        if (requestExitGame && screenState != HomeScreenState.ROOT) {
            screenState = HomeScreenState.ROOT
            onExitGameConsumed()
        }
    }

    when (screenState) {
        HomeScreenState.ROOT -> {
            HomeSelectionScreen(
                pveMode = pveMode,
                pvpMode = pvpMode,
                onOpenPve = { screenState = HomeScreenState.PVE_GAME },
                onOpenPvp = { screenState = HomeScreenState.PVP_GAME }
            )
        }
        HomeScreenState.PVE_GAME -> {
            GameFieldScreen(
                title = strings.text(pveMode.titleKey),
                params = pveMode.toFieldParams(TypeGame.RaceMatch),
                onBack = { screenState = HomeScreenState.ROOT }
            )
        }
        HomeScreenState.PVP_GAME -> {
            GameFieldScreen(
                title = strings.text(pvpMode.titleKey),
                params = pvpMode.toFieldParams(TypeGame.DuelMatch),
                onBack = { screenState = HomeScreenState.ROOT }
            )
        }
    }
}

@Composable
private fun HomeSelectionScreen(
    pveMode: GameModeDefinition,
    pvpMode: GameModeDefinition,
    onOpenPve: () -> Unit,
    onOpenPvp: () -> Unit
) {
    val strings = LocalAppStrings.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(
                space = maxHeight * 0.03f,
                alignment = Alignment.CenterVertically
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = strings.text("home.title"),
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = strings.text("home.subtitle"),
                style = MaterialTheme.typography.bodyLarge
            )
            Button(
                onClick = onOpenPve,
                modifier = Modifier.fillMaxWidth(fraction = 0.62f)
            ) {
                Text(strings.text(pveMode.titleKey))
            }
            FilledTonalButton(
                onClick = onOpenPvp,
                modifier = Modifier.fillMaxWidth(fraction = 0.62f)
            ) {
                Text(strings.text(pvpMode.titleKey))
            }
        }
    }
}

private fun GameModeDefinition.toFieldParams(typeGame: TypeGame): GameFieldParams {
    return GameFieldParams(
        typeGame = typeGame,
        useHints = hintsEnabled,
        timeAll = totalTimeLimitSeconds ?: 0,
        timeMove = turnTimeLimitSeconds ?: 0,
        limitMoves = config.attemptLimit,
        lenSecret = config.codeLength
    )
}
