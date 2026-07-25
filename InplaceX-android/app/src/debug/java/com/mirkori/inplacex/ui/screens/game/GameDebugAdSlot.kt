package com.mirkori.inplacex.ui.screens.game

import androidx.compose.runtime.Composable
import com.mirkori.inplacex.ui.screens.game.presentation.GameDebugAdSlotContent

@Composable
fun GameDebugAdSlot(
    debugSecret: String,
    openPositionHints: Int,
    checkDigitHints: Int,
    checkPositionHints: Int,
    extraMovesBoosts: Int,
    extraTimeBoosts: Int,
    onAddHintsClick: () -> Unit,
) {
    GameDebugAdSlotContent(
        debugSecret = debugSecret,
        openPositionHints = openPositionHints,
        checkDigitHints = checkDigitHints,
        checkPositionHints = checkPositionHints,
        extraMovesBoosts = extraMovesBoosts,
        extraTimeBoosts = extraTimeBoosts,
        onAddHintsClick = onAddHintsClick,
    )
}
