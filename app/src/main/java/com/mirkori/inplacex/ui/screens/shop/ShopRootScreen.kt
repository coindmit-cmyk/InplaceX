package com.mirkori.inplacex.ui.screens.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.data.local.GameProgressState

@Composable
fun ShopRootScreen(
    progressState: GameProgressState,
    onBuyOpenPositionHint: () -> Unit,
    onBuyCheckDigitHint: () -> Unit,
    onBuyCheckPositionHint: () -> Unit,
    onBuyExtraMovesBoost: () -> Unit,
    onBuyExtraTimeBoost: () -> Unit,
    onBuyEnergy: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Shop",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Coins: ${progressState.coins}",
            style = MaterialTheme.typography.titleMedium
        )

        ShopItemCard("Open position hint", "20 coins", onBuyOpenPositionHint)
        ShopItemCard("Check digit hint", "15 coins", onBuyCheckDigitHint)
        ShopItemCard("Check position hint", "25 coins", onBuyCheckPositionHint)
        ShopItemCard("Extra moves boost", "30 coins", onBuyExtraMovesBoost)
        ShopItemCard("Extra time boost", "30 coins", onBuyExtraTimeBoost)
        ShopItemCard("Campaign energy +1", "25 coins", onBuyEnergy)
    }
}

@Composable
private fun ShopItemCard(
    title: String,
    price: String,
    onBuy: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = price, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onBuy) {
                Text("Buy")
            }
        }
    }
}
