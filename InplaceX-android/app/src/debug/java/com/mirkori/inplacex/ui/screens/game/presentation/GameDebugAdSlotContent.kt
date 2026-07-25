package com.mirkori.inplacex.ui.screens.game.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.LocalAppStrings

@Composable
internal fun GameDebugAdSlotContent(
    debugSecret: String,
    openPositionHints: Int,
    checkDigitHints: Int,
    checkPositionHints: Int,
    extraMovesBoosts: Int,
    extraTimeBoosts: Int,
    onAddHintsClick: () -> Unit,
) {
    val strings = LocalAppStrings.current
    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(strings.text("game.ad_slot"), textAlign = TextAlign.Center)
        Text(
            strings.text("game.debug.secret").replace("{value}", debugSecret),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            strings.text("game.debug.hints")
                .replace("{open}", openPositionHints.toString())
                .replace("{digit}", checkDigitHints.toString())
                .replace("{position}", checkPositionHints.toString()),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            strings.text("game.debug.boosts")
                .replace("{moves}", extraMovesBoosts.toString())
                .replace("{time}", extraTimeBoosts.toString()),
            style = MaterialTheme.typography.labelSmall,
        )
        FilledTonalButton(onClick = onAddHintsClick) {
            Text(strings.text("game.debug.add_hints"))
        }
    }
}
