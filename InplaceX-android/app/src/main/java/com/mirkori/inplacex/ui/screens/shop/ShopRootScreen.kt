package com.mirkori.inplacex.ui.screens.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.screens.shared.SceneActionTile
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import com.mirkori.inplacex.ui.screens.shared.ScenePageColumn
import com.mirkori.inplacex.ui.screens.shared.SceneSplitStatRow

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

    ScenePageColumn(
        modifier = Modifier.fillMaxSize(),
        scrollable = true
    ) {
        SceneCard(accentColor = Color.White.copy(alpha = 0.76f)) {
            Text(
                text = strings.text("shop.title"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = strings.text("shop.rewarded.coins"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SceneSplitStatRow(
                leftLabel = strings.text("top.coins"),
                leftValue = progressState.coins.toString(),
                rightLabel = strings.text("top.energy"),
                rightValue = "${progressState.campaignEnergy}/${progressState.campaignEnergyMax}",
            )
            Button(
                onClick = onWatchRewardedCoins,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(strings.text("shop.rewarded.watch"))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SceneActionTile(
                title = strings.text("shop.hints"),
                subtitle = "5 helper types for matches and campaign runs",
                modifier = Modifier.weight(1f),
                accentBrush = Brush.verticalGradient(listOf(Color(0xFF80D5FF), Color(0xFF4C8FFF))),
                onClick = {}
            )
            SceneActionTile(
                title = strings.text("shop.premium"),
                subtitle = "Ad-free, Pro and Pro+ access",
                modifier = Modifier.weight(1f),
                accentBrush = Brush.verticalGradient(listOf(Color(0xFFFFD37A), Color(0xFFF19A2A))),
                onClick = {}
            )
        }

        SceneCard(accentColor = Color.White.copy(alpha = 0.72f)) {
            Text(
                text = strings.text("shop.hints"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            ShopLine(strings.text("shop.item.open_position"), "20", onBuyOpenPositionHint)
            ShopLine(strings.text("shop.item.check_digit"), "15", onBuyCheckDigitHint)
            ShopLine(strings.text("shop.item.check_position"), "25", onBuyCheckPositionHint)
            ShopLine(strings.text("shop.item.extra_moves"), "30", onBuyExtraMovesBoost)
            ShopLine(strings.text("shop.item.extra_time"), "30", onBuyExtraTimeBoost)
            ShopLine(strings.text("shop.item.energy"), "25", onBuyEnergy)
        }

        SceneCard(accentColor = Color.White.copy(alpha = 0.72f)) {
            Text(
                text = strings.text("shop.premium"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            PremiumLine(
                title = strings.text("shop.product.remove_ads"),
                active = progressState.adFreePurchased,
                actionLabel = strings.text(if (progressState.adFreePurchased) "shop.owned" else "shop.buy"),
                onAction = onBuyRemoveAds
            )
            PremiumLine(
                title = strings.text("shop.product.pro"),
                active = progressState.proSubscriptionActive,
                actionLabel = strings.text(if (progressState.proSubscriptionActive) "shop.active" else "shop.subscribe"),
                onAction = onBuyPro
            )
            PremiumLine(
                title = strings.text("shop.product.pro_plus"),
                active = progressState.proPlusSubscriptionActive,
                actionLabel = strings.text(if (progressState.proPlusSubscriptionActive) "shop.active" else "shop.subscribe"),
                onAction = onBuyProPlus
            )
        }
    }
}

@Composable
private fun ShopLine(
    title: String,
    price: String,
    onBuy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text("$price coins", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onBuy) {
            Text("Buy")
        }
    }
}

@Composable
private fun PremiumLine(
    title: String,
    active: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                if (active) "Unlocked" else "Premium access",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(
            onClick = onAction,
            enabled = !active
        ) {
            Text(actionLabel)
        }
    }
}
