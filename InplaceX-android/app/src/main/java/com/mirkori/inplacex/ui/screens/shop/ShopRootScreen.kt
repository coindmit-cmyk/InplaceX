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
import com.mirkori.inplacex.core.monetization.TemporaryProPolicy
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.services.BillingAvailability
import com.mirkori.inplacex.platform.services.BillingNotice
import com.mirkori.inplacex.platform.services.BillingProductId
import com.mirkori.inplacex.platform.services.BillingState
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
    nowMs: Long = System.currentTimeMillis(),
    billingState: BillingState = BillingState(
        availability = BillingAvailability.UNAVAILABLE,
        notice = BillingNotice.CONFIGURATION_REQUIRED,
    ),
    billingInProgress: Boolean = false,
    onRefreshBilling: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onWatchRewardedCoins: ((Boolean) -> Unit) -> Unit,
    onBuyOpenPositionHint: () -> Boolean,
    onBuyCheckDigitHint: () -> Boolean,
    onBuyCheckPositionHint: () -> Boolean,
    onBuyExtraMovesBoost: () -> Boolean,
    onBuyExtraTimeBoost: () -> Boolean,
    onBuyEnergy: () -> Boolean,
    onBuyRemoveAds: () -> Unit,
    onBuyPro: () -> Unit,
    onBuyProPlus: () -> Unit,
    onRetryBillingPurchase: () -> Unit = {},
    onBuyTemporaryPro: () -> Boolean = { false },
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
                nowMs = nowMs,
                billingState = billingState,
                billingInProgress = billingInProgress,
                onReport = ::report,
                onRefreshBilling = onRefreshBilling,
                onOpenProfile = onOpenProfile,
                onBuyRemoveAds = onBuyRemoveAds,
                onBuyPro = onBuyPro,
                onBuyProPlus = onBuyProPlus,
                onRetryBillingPurchase = onRetryBillingPurchase,
                onBuyTemporaryPro = onBuyTemporaryPro,
            )
        }
    }
}

@Composable
private fun BoostsCatalog(
    progressState: GameProgressState,
    onReport: (Boolean) -> Unit,
    onWatchRewardedCoins: ((Boolean) -> Unit) -> Unit,
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
            onClick = { onWatchRewardedCoins(onReport) },
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
    nowMs: Long,
    billingState: BillingState,
    billingInProgress: Boolean,
    onReport: (Boolean) -> Unit,
    onRefreshBilling: () -> Unit,
    onOpenProfile: () -> Unit,
    onBuyRemoveAds: () -> Unit,
    onBuyPro: () -> Unit,
    onBuyProPlus: () -> Unit,
    onRetryBillingPurchase: () -> Unit,
    onBuyTemporaryPro: () -> Boolean,
) {
    val strings = LocalAppStrings.current
    Text(
        text = strings.text("shop.premium"),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
    BillingStatusCard(
        state = billingState,
        inProgress = billingInProgress,
        onRefresh = onRefreshBilling,
        onOpenProfile = onOpenProfile,
        onRetryPurchase = onRetryBillingPurchase,
    )
    BillingPremiumCard(
        productId = BillingProductId.REMOVE_ADS,
        title = strings.text("shop.product.remove_ads"),
        description = strings.text("shop.product.remove_ads.desc"),
        active = billingState.entitlements.adFreePurchased,
        billingState = billingState,
        inProgress = billingInProgress,
        onBuy = onBuyRemoveAds,
        onRetry = onRetryBillingPurchase,
    )
    TemporaryProCard(
        progressState = progressState,
        nowMs = nowMs,
        onAction = { onReport(onBuyTemporaryPro()) },
    )
    BillingPremiumCard(
        productId = BillingProductId.PRO_SUBSCRIPTION,
        title = strings.text("shop.product.pro"),
        description = strings.text("shop.product.pro.desc"),
        active = billingState.entitlements.effectiveProAccessActive,
        billingState = billingState,
        inProgress = billingInProgress,
        onBuy = onBuyPro,
        onRetry = onRetryBillingPurchase,
    )
    BillingPremiumCard(
        productId = BillingProductId.PRO_PLUS_SUBSCRIPTION,
        title = strings.text("shop.product.pro_plus"),
        description = strings.text("shop.product.pro_plus.desc"),
        active = billingState.entitlements.proPlusSubscriptionActive,
        billingState = billingState,
        inProgress = billingInProgress,
        onBuy = onBuyProPlus,
        onRetry = onRetryBillingPurchase,
    )
}

@Composable
private fun BillingStatusCard(
    state: BillingState,
    inProgress: Boolean,
    onRefresh: () -> Unit,
    onOpenProfile: () -> Unit,
    onRetryPurchase: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val noticeKey = billingNoticeLocalizationKey(state.notice)
    val positive = state.notice == BillingNotice.PAYMENT_CONFIRMED ||
        state.notice == BillingNotice.PRODUCT_ALREADY_ACTIVE
    SceneCard(
        accentColor = when {
            positive -> InplaceXColors.Mint.copy(alpha = 0.12f)
            state.notice == BillingNotice.NONE && state.availability == BillingAvailability.READY ->
                InplaceXColors.ToyCream.copy(alpha = 0.95f)
            else -> InplaceXColors.Coral.copy(alpha = 0.10f)
        },
    ) {
        Text(
            text = when {
                inProgress -> strings.text("shop.billing.checking")
                noticeKey != null -> strings.text(noticeKey)
                state.availability == BillingAvailability.READY -> strings.text("shop.billing.ready")
                else -> strings.text("shop.billing.unavailable")
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        when {
            state.notice == BillingNotice.LINKED_ACCOUNT_REQUIRED -> Button(
                onClick = onOpenProfile,
                enabled = !inProgress,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(strings.text("shop.billing.open_profile"))
            }

            shouldRetryPendingPurchase(state) -> Button(
                onClick = onRetryPurchase,
                enabled = !inProgress,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(strings.text("shop.billing.retry_payment"))
            }

            else -> OutlinedButton(
                onClick = onRefresh,
                enabled = !inProgress,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(strings.text("shop.billing.refresh"))
            }
        }
    }
}

@Composable
private fun BillingPremiumCard(
    productId: BillingProductId,
    title: String,
    description: String,
    active: Boolean,
    billingState: BillingState,
    inProgress: Boolean,
    onBuy: () -> Unit,
    onRetry: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val product = billingState.products[productId]
    val isPending = billingState.pendingProduct == productId
    val prepaidAccessDescription = if (productId == BillingProductId.REMOVE_ADS) {
        description
    } else {
        val duration = product?.accessDurationSeconds?.let { seconds ->
            formatPrepaidAccessDuration(seconds) { key -> strings.text(key) }
        }
        "$description ${if (duration == null) {
            strings.text("shop.product.prepaid_duration_unknown")
        } else {
            strings.text("shop.product.prepaid_duration").replace("{duration}", duration)
        }}"
    }
    val actionLabel = when {
        active -> strings.text(if (productId == BillingProductId.REMOVE_ADS) "shop.owned" else "shop.active")
        inProgress -> strings.text("shop.billing.checking")
        isPending && billingState.notice == BillingNotice.AWAITING_ENTITLEMENT ->
            strings.text("shop.billing.refresh")
        isPending -> strings.text("shop.billing.retry_payment")
        product == null -> strings.text("shop.billing.unavailable_short")
        else -> strings.text("shop.billing.buy_for").replace(
            "{price}",
            formatBillingPrice(product.amountMinor, product.currency),
        )
    }
    PremiumCard(
        title = title,
        description = prepaidAccessDescription,
        active = active,
        priceLabel = product?.let { formatBillingPrice(it.amountMinor, it.currency) },
        actionLabel = actionLabel,
        actionEnabled = canStartBillingAction(billingState, productId, inProgress),
        onAction = if (isPending) onRetry else onBuy,
    )
}

internal fun formatPrepaidAccessDuration(
    durationSeconds: Long,
    text: (String) -> String,
): String {
    require(durationSeconds > 0)
    val (count, key) = when {
        durationSeconds % 86_400L == 0L -> durationSeconds / 86_400L to "shop.product.duration_days"
        durationSeconds % 3_600L == 0L -> durationSeconds / 3_600L to "shop.product.duration_hours"
        else -> (durationSeconds + 59L) / 60L to "shop.product.duration_minutes"
    }
    return text(key).replace("{count}", count.toString())
}

@Composable
private fun TemporaryProCard(
    progressState: GameProgressState,
    nowMs: Long,
    onAction: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val includedInPermanent = progressState.proSubscriptionActive || progressState.proPlusSubscriptionActive
    val active = progressState.temporaryProActiveAt(nowMs)
    val affordable = progressState.coins >= TemporaryProPolicy.PRICE_COINS
    val enabled = !includedInPermanent && affordable
    val price = TemporaryProPolicy.PRICE_COINS.toString()
    val status = when {
        includedInPermanent -> strings.text("shop.temporary_pro.included")
        active -> strings.text("shop.temporary_pro.remaining").replace(
            "{time}",
            TemporaryProPolicy.formatRemaining(progressState.temporaryProExpiresAtMs, nowMs),
        )
        else -> strings.text("shop.temporary_pro.duration")
    }
    val actionLabel = when {
        includedInPermanent -> strings.text("shop.temporary_pro.included")
        !affordable -> strings.text("shop.not_enough_coins")
        active -> strings.text("shop.temporary_pro.extend").replace("{price}", price)
        else -> strings.text("shop.temporary_pro.buy").replace("{price}", price)
    }

    SceneCard(
        accentColor = if (active || includedInPermanent) {
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
                text = strings.text("shop.product.temporary_pro"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = strings.text("shop.price").replace("{price}", price),
                style = MaterialTheme.typography.labelLarge,
                color = InplaceXColors.ToyOrange,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = strings.text("shop.product.temporary_pro.desc"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active || includedInPermanent) InplaceXColors.Mint else InplaceXColors.InkMuted,
            fontWeight = FontWeight.SemiBold,
        )
        Button(
            onClick = onAction,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    role = Role.Button
                    if (!enabled) {
                        stateDescription = actionLabel
                    }
                },
        ) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun PremiumCard(
    title: String,
    description: String,
    active: Boolean,
    priceLabel: String?,
    actionLabel: String,
    actionEnabled: Boolean,
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
                text = if (active) {
                    strings.text("shop.premium.unlocked")
                } else {
                    priceLabel ?: strings.text("shop.premium.access")
                },
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
            enabled = actionEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(actionLabel)
        }
    }
}

internal fun billingNoticeLocalizationKey(notice: BillingNotice): String? = when (notice) {
    BillingNotice.NONE -> null
    BillingNotice.CHECKOUT_OPENED -> "shop.billing.checkout_opened"
    BillingNotice.AWAITING_PAYMENT -> "shop.billing.awaiting_payment"
    BillingNotice.AWAITING_ENTITLEMENT -> "shop.billing.awaiting_entitlement"
    BillingNotice.PAYMENT_CONFIRMED -> "shop.billing.confirmed"
    BillingNotice.PAYMENT_CANCELLED -> "shop.billing.cancelled"
    BillingNotice.PAYMENT_REFUNDED -> "shop.billing.refunded"
    BillingNotice.CHECKOUT_EXPIRED -> "shop.billing.checkout_expired"
    BillingNotice.LINKED_ACCOUNT_REQUIRED -> "shop.billing.link_required"
    BillingNotice.PROVIDER_UNAVAILABLE -> "shop.billing.provider_unavailable"
    BillingNotice.OFFLINE -> "shop.billing.offline"
    BillingNotice.RETRY_REQUIRED -> "shop.billing.retry_required"
    BillingNotice.PRODUCT_ALREADY_ACTIVE -> "shop.billing.already_active"
    BillingNotice.BUSY -> "shop.billing.busy"
    BillingNotice.CONFIGURATION_REQUIRED -> "shop.billing.configuration_required"
}

internal fun shouldRetryPendingPurchase(state: BillingState): Boolean =
    state.pendingProduct != null && state.notice in setOf(
        BillingNotice.CHECKOUT_OPENED,
        BillingNotice.AWAITING_PAYMENT,
        BillingNotice.CHECKOUT_EXPIRED,
        BillingNotice.PROVIDER_UNAVAILABLE,
        BillingNotice.RETRY_REQUIRED,
    )

internal fun canStartBillingAction(
    state: BillingState,
    productId: BillingProductId,
    inProgress: Boolean,
): Boolean {
    if (inProgress || state.availability != BillingAvailability.READY) return false
    if (state.products[productId] == null) return false
    val alreadyActive = when (productId) {
        BillingProductId.REMOVE_ADS -> state.entitlements.adFreePurchased
        BillingProductId.PRO_SUBSCRIPTION -> state.entitlements.effectiveProAccessActive
        BillingProductId.PRO_PLUS_SUBSCRIPTION -> state.entitlements.proPlusSubscriptionActive
    }
    if (alreadyActive) return false
    return state.pendingProduct == null || state.pendingProduct == productId
}

internal fun formatBillingPrice(amountMinor: Long, currency: String): String {
    require(amountMinor >= 0)
    require(currency.matches(Regex("[A-Z]{3}")))
    val whole = amountMinor / 100
    val fraction = (amountMinor % 100).toString().padStart(2, '0')
    return if (currency == "RUB") "$whole,$fraction ₽" else "$whole.$fraction $currency"
}
