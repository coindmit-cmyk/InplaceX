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
import com.mirkori.inplacex.ui.screens.game.GameFieldParams
import com.mirkori.inplacex.ui.screens.game.GameFieldScreen
import com.mirkori.inplacex.ui.screens.game.TypeGame

@Composable
fun HomeRootScreen() {
    var screenState by remember { mutableStateOf(HomeScreenState.ROOT) }
    var pveTitle by remember { mutableStateOf("PvE игра") }
    var pveParams by remember {
        mutableStateOf(
            GameFieldParams(
                typeGame = TypeGame.RaceMatch,
                useHints = true,
                timeAll = 0,
                timeMove = 0,
                limitMoves = 0,
                lenSecret = 6
            )
        )
    }

    when (screenState) {
        HomeScreenState.ROOT -> {
            HomeSelectionScreen(
                onOpenPve = { screenState = HomeScreenState.PVE_SETUP },
                onOpenPvp = { screenState = HomeScreenState.PVP_GAME }
            )
        }

        HomeScreenState.PVE_SETUP -> {
            PveGameSetupScreen(
                onBack = { screenState = HomeScreenState.ROOT },
                onStartGame = { params, modeTitle ->
                    pveParams = params
                    pveTitle = "PvE • $modeTitle"
                    screenState = HomeScreenState.PVE_GAME
                }
            )
        }

        HomeScreenState.PVE_GAME -> {
            GameFieldScreen(
                title = pveTitle,
                params = pveParams,
                onBack = { screenState = HomeScreenState.PVE_SETUP }
            )
        }

        HomeScreenState.PVP_GAME -> {
            GameFieldScreen(
                title = "PvP игра",
                params = GameFieldParams(
                    typeGame = TypeGame.DuelMatch,
                    useHints = false,
                    timeAll = 0,
                    timeMove = 30,
                    limitMoves = 0,
                    lenSecret = 6
                ),
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
                text = "Главный экран",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "PvE теперь открывает экран создания матча",
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
