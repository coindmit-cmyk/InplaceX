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

@Composable
fun DebugSecretAdSlot(
    debugSecret: String?,
    adsDisabled: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (adsDisabled) "Реклама отключена" else "Рекламный баннер игрового экрана",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        debugSecret?.let { secret ->
            Text(
                text = "Секрет: $secret",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFB71C1C),
                textAlign = TextAlign.Center
            )
        }
    }
}
