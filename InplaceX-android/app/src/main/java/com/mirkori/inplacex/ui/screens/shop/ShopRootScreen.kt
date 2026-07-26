package com.mirkori.inplacex.ui.screens.shop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import com.mirkori.inplacex.ui.screens.shared.ScenePageColumn
import com.mirkori.inplacex.ui.screens.shared.SceneSplitStatRow
import com.mirkori.inplacex.ui.theme.InplaceXColors

private enum class ShopCategory {
    BOOSTS,
    PREMIUM,
}

@Composable
fun ShopRootScreen(
    progressState: GameProgressState,
    onWatchRewardedCoins: () -> Boolean,
    onBuyOpenPositionHint: () -> Boolean,
    onBuyCheckDigitHint: () -> Boolean,
    onBuyCheckPositionHint: () -> Boolean,
    onBuyExtraMovesBoost: () -> Boolean,
    onBuyExtraTimeBoost: () -> Boolean,
    onBuyEnergy: () -> Boolean,
    onBuyRemoveAds: () -> Boolean,
    onBuyPro: () -> Boolean,
    onBuyProPlus: () -> Boolean,
) {
    val strings = LocalAppStrings.current
    var categoryName by rememberSaveable { mutableStateOf(ShopCategory.BOOSTS.name) }
    var resultKey by rememberSaveable { mutableStateOf<String?>(null) }
    val category = ShopCategory.valueOf(categoryName)

    fun report(result: Boolean) {
        resultKey = if (result) "shop.result.success" else "shop.result.unavailable"
    }

    ScenePageColumn(
        modifier = Modifier.fillMaxSize(),
        scrollable = true,
    ) {
        SceneCard(accentColor = InplaceXColors.ToyCream.copy(alpha = 0.97f)) {
            Text(
                text = strings.text("shop.title"),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = strings.text("shop.hero.subtitle"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SceneSplitStatRow(
                leftLabel = strings.text("top.coins"),
                leftValue = progressState.coins.toString(),
                rightLabel = strings.text("top.energy"),
                rightValue = "${progressState.campaignEnergy}/${progressState.campaignEnergyMax}",
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = category == ShopCategory.BOOSTS,
                onClick = {
                    categoryName = ShopCategory.BOOSTS.name
                    resultKey = null
                },
                label = { Text(strings.text("shop.tab.boosts")) },
                colors = shellFilterChipColors(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            )
            FilterChip(
                selected = category == ShopCategory.PREMIUM,
                onClick = {
                    categoryName = ShopCategory.PREMIUM.name
                    resultKey = null
                },
                label = { Text(strings.text("shop.tab.premium")) },
                colors = shellFilterChipColors(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            )
        }

        resultKey?.let { key ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = if (key == "shop.result.success") {
                    InplaceXColors.Mint.copy(alpha = 0.16f)
                } else {
                    InplaceXColors.Coral.copy(alpha = 0.14f)
                },
                border = BorderStroke(
                    1.dp,
                    if (key == "shop.result.success") InplaceXColors.Mint else InplaceXColors.Coral,
                ),
            ) {
                Text(
                    text = strings.text(key),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        when (category) {
            ShopCategory.BOOSTS -> BoostsCatalog(
                progressState = progressState,
                onReport = ::report,
                onWatchRewardedCoins = onWatchRewardedCoins,
                onBuyOpenPositionHint = onBuyOpenPositionHint,
                onBuyCheckDigitHint = onBuyCheckDigitHint,
                onBuyCheckPositionHint = onBuyCheckPositionHint,
                onBuyExtraMovesBoost = onBuyExtraMovesBoost,
                onBuyExtraTimeBoost = onBuyExtraTimeBoost,
                onBuyEnergy = onBuyEnergy,
            )

            ShopCategory.PREMIUM -> PremiumCatalog(
                progressState = progressState,
                onReport = ::report,
                onBuyRemoveAds = onBuyRemoveAds,
                onBuyPro = onBuyPro,
                onBuyProPlus = onBuyProPlus,
            )
        }
    }
}

@Composable
private fun BoostsCatalog(
    progressState: GameProgressState,
    onReport: (Boolean) -> Unit,
    onWatchRewardedCoins: () -> Boolean,
    onBuyOpenPositionHint: () -> Boolean,
    onBuyCheckDigitHint: () -> Boolean,
    onBuyCheckPositionHint: () -> Boolean,
    onBuyExtraMovesBoost: () -> Boolean,
    onBuyExtraTimeBoost: () -> Boolean,
    onBuyEnergy: () -> Boolean,
) {
    val strings = LocalAppStrings.current
    SceneCard(accentColor = InplaceXColors.ToyCream.copy(alpha = 0.97f)) {
        Text(
            text = strings.text("shop.rewarded.title"),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = strings.text("shop.rewarded.coins"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { onReport(onWatchRewardedCoins()) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(strings.text("shop.rewarded.watch"))
        }
    }

    Text(
        text = strings.text("shop.hints"),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    ShopItemGrid(
        items = listOf(
            ShopItem(
                strings.text("shop.item.open_position"),
                progressState.openPositionHints,
                20,
                onBuyOpenPositionHint,
            ),
            ShopItem(
                strings.text("shop.item.check_digit"),
                progressState.checkDigitHints,
                15,
                onBuyCheckDigitHint,
            ),
            ShopItem(
                strings.text("shop.item.check_position"),
                progressState.checkPositionHints,
                25,
                onBuyCheckPositionHint,
            ),
            ShopItem(
                strings.text("shop.item.extra_moves"),
                progressState.extraMovesBoosts,
                30,
                onBuyExtraMovesBoost,
            ),
            ShopItem(
                strings.text("shop.item.extra_time"),
                progressState.extraTimeBoosts,
                30,
                onBuyExtraTimeBoost,
            ),
            ShopItem(
                strings.text("shop.item.energy"),
                progressState.campaignEnergy,
                25,
                onBuyEnergy,
            ),
        ),
        coins = progressState.coins,
        onReport = onReport,
    )
}

private data class ShopItem(
    val title: String,
    val stock: Int,
    val price: Int,
    val onBuy: () -> Boolean,
)

@Composable
private fun ShopItemGrid(
    items: List<ShopItem>,
    coins: Int,
    onReport: (Boolean) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 560.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach { item ->
                    ShopItemCard(item, coins, onReport)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowItems.forEach { item ->
                            ShopItemCard(
                                item = item,
                                coins = coins,
                                onReport = onReport,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowItems.size == 1) {
                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShopItemCard(
    item: ShopItem,
    coins: Int,
    onReport: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val affordable = coins >= item.price
    SceneCard(
        modifier = modifier,
        accentColor = InplaceXColors.ToyCream.copy(alpha = 0.95f),
    ) {
        Text(item.title, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = strings.text("shop.stock").replace("{count}", item.stock.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = strings.text("shop.price").replace("{price}", item.price.toString()),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        OutlinedButton(
            onClick = { onReport(item.onBuy()) },
            enabled = affordable,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    role = Role.Button
                    if (!affordable) {
                        stateDescription = strings.text("shop.not_enough_coins")
                    }
                },
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = InplaceXColors.Cobalt,
                disabledContentColor = InplaceXColors.InkMuted,
            ),
            border = BorderStroke(
                1.dp,
                if (affordable) InplaceXColors.Cobalt else InplaceXColors.Outline,
            ),
        ) {
            Text(
                if (affordable) strings.text("shop.buy") else strings.text("shop.not_enough_coins"),
            )
        }
    }
}

@Composable
private fun shellFilterChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = InplaceXColors.ToyCream.copy(alpha = 0.94f),
    labelColor = InplaceXColors.ToyBrown,
    selectedContainerColor = InplaceXColors.ToyOrange,
    selectedLabelColor = InplaceXColors.White,
)

@Composable
private fun PremiumCatalog(
    progressState: GameProgressState,
    onReport: (Boolean) -> Unit,
    onBuyRemoveAds: () -> Boolean,
    onBuyPro: () -> Boolean,
    onBuyProPlus: () -> Boolean,
) {
    val strings = LocalAppStrings.current
    Text(
        text = strings.text("shop.premium"),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    PremiumCard(
        title = strings.text("shop.product.remove_ads"),
        description = strings.text("shop.product.remove_ads.desc"),
        active = progressState.adFreePurchased,
        actionLabel = strings.text(if (progressState.adFreePurchased) "shop.owned" else "shop.buy"),
        onAction = { onReport(onBuyRemoveAds()) },
    )
    PremiumCard(
        title = strings.text("shop.product.pro"),
        description = strings.text("shop.product.pro.desc"),
        active = progressState.proSubscriptionActive,
        actionLabel = strings.text(if (progressState.proSubscriptionActive) "shop.active" else "shop.subscribe"),
        onAction = { onReport(onBuyPro()) },
    )
    PremiumCard(
        title = strings.text("shop.product.pro_plus"),
        description = strings.text("shop.product.pro_plus.desc"),
        active = progressState.proPlusSubscriptionActive,
        actionLabel = strings.text(if (progressState.proPlusSubscriptionActive) "shop.active" else "shop.subscribe"),
        onAction = { onReport(onBuyProPlus()) },
    )
}

@Composable
private fun PremiumCard(
    title: String,
    description: String,
    active: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val strings = LocalAppStrings.current
    SceneCard(
        accentColor = if (active) {
            InplaceXColors.Mint.copy(alpha = 0.12f)
        } else {
            InplaceXColors.ToyCream.copy(alpha = 0.95f)
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = strings.text(if (active) "shop.premium.unlocked" else "shop.premium.access"),
                style = MaterialTheme.typography.labelMedium,
                color = if (active) InplaceXColors.Mint else InplaceXColors.InkMuted,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onAction,
            enabled = !active,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(actionLabel)
        }
    }
}
