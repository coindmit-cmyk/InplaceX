package com.mirkori.inplacex.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mirkori.inplacex.ui.screens.game.presentation.GamePresentationCallbacks
import com.mirkori.inplacex.ui.screens.game.presentation.GamePresentationLayout
import com.mirkori.inplacex.ui.screens.game.state.GameFieldUiState

/**
 * Pure game-field renderer. Match ownership, persistence, inventory and navigation live in the
 * route layer; this entry point only renders [uiState] and forwards user intent through callbacks.
 */
@Composable
fun GameScreen(
    uiState: GameFieldUiState,
    callbacks: GamePresentationCallbacks,
    modifier: Modifier = Modifier,
    debugSlot: (@Composable () -> Unit)? = null,
) {
    GamePresentationLayout(
        uiState = uiState,
        callbacks = callbacks,
        modifier = modifier,
        debugSlot = debugSlot,
    )
}
