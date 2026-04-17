package com.mirkori.inplacex.ui.screens.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.platform.localization.LocalAppStrings

@Composable
fun ShopRootScreen(
    progressState: GameProgressState,
    onWatchRewardedCoins: () -> Unit,
    onBuyOpenPositionHint: () -> Unit,
    onBuyCheckDigitHint: () -> Unit,
    onBuyCheckPositionHint: () -> Unit,
    onBuyExtraMovesBoost: () -> Unit,
    onBuyExtraTimeBoost: () -> Unit,
    onBuyEnergy: () -> Unit,
    onBuyRemoveAds: () -> Unit,
    onBuyPro: () -> Unit,
    onBuyProPlus: () -> Unit,
) {
    val strings = LocalAppStrings.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = strings.text("shop.title"),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "${strings.text("top.coins")}: ${progressState.coins}",
            style = MaterialTheme.typography.titleMedium
        )

        SectionCard(strings.text("shop.rewarded.title"), strings.text("shop.rewarded.coins")) {
            Button(onClick = onWatchRewardedCoins) {
                Text(strings.text("shop.rewarded.watch"))
            }
        }

        SectionCard(strings.text("shop.hints")) {
            ShopItemCard(strings.text("shop.item.open_position"), "20 coins", onBuyOpenPositionHint)
            ShopItemCard(strings.text("shop.item.check_digit"), "15 coins", onBuyCheckDigitHint)
            ShopItemCard(strings.text("shop.item.check_position"), "25 coins", onBuyCheckPositionHint)
            ShopItemCard(strings.text("shop.item.extra_moves"), "30 coins", onBuyExtraMovesBoost)
            ShopItemCard(strings.text("shop.item.extra_time"), "30 coins", onBuyExtraTimeBoost)
            ShopItemCard(strings.text("shop.item.energy"), "25 coins", onBuyEnergy)
        }

        SectionCard(strings.text("shop.premium")) {
            PurchaseCard(
                title = strings.text("shop.product.remove_ads"),
                description = strings.text("shop.product.remove_ads.desc"),
                actionLabel = if (progressState.adFreePurchased) strings.text("shop.owned") else strings.text("shop.buy"),
                enabled = !progressState.adFreePurchased,
                onAction = onBuyRemoveAds,
            )
            PurchaseCard(
                title = strings.text("shop.product.pro"),
                description = strings.text("shop.product.pro.desc"),
                actionLabel = if (progressState.proSubscriptionActive) strings.text("shop.active") else strings.text("shop.subscribe"),
                enabled = !progressState.proSubscriptionActive,
                onAction = onBuyPro,
            )
            PurchaseCard(
                title = strings.text("shop.product.pro_plus"),
                description = strings.text("shop.product.pro_plus.desc"),
                actionLabel = if (progressState.proPlusSubscriptionActive) strings.text("shop.active") else strings.text("shop.subscribe"),
                enabled = !progressState.proPlusSubscriptionActive,
                onAction = onBuyProPlus,
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            if (description != null) {
                Text(text = description, style = MaterialTheme.typography.bodyMedium)
            }
            content()
        }
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

@Composable
private fun PurchaseCard(
    title: String,
    description: String,
    actionLabel: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onAction,
                enabled = enabled,
            ) {
                Text(actionLabel)
            }
        }
    }
}
