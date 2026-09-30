package com.mirkori.inplacex.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.mirkori.inplacex.platform.localization.LocalAppStrings

@Composable
fun DebugSecretAdSlot(
    debugSecret: String?,
    adsDisabled: Boolean,
    developerModeEnabled: Boolean,
) {
    val strings = LocalAppStrings.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (adsDisabled) "Ads disabled" else strings.text("game.ad_slot"),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        if (developerModeEnabled && debugSecret != null) {
            Text(
                text = strings.text("game.debug.secret").replace("{value}", debugSecret),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFB71C1C),
                textAlign = TextAlign.Center
            )
        }
    }
}
