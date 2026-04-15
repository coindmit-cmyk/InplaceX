package com.mirkori.inplacex.ui.screens.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.LocalAppStrings

@Composable
fun ShopRootScreen() {
    val strings = LocalAppStrings.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = strings.text("shop.title"),
            style = MaterialTheme.typography.headlineSmall
        )
        FilledTonalButton(onClick = { }) {
            Text(strings.text("shop.hints"))
        }
        FilledTonalButton(onClick = { }) {
            Text(strings.text("shop.premium"))
        }
    }
}
