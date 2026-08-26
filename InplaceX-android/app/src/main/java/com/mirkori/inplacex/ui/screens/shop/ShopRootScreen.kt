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
import com.mirkori.inplacex.ui.screens.shared.PagePrimaryButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import com.mirkori.inplacex.ui.screens.shared.PageSecondaryButton as OutlinedButton
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
import com.mirkori.inplacex.ui.theme.PageColors
import com.mirkori.inplacex.ui.theme.PageType
import com.mirkori.inplacex.ui.screens.shared.PageHeroCard
import com.mirkori.inplacex.ui.screens.shared.PageStatusPill
import com.mirkori.inplacex.ui.screens.shared.PageSectionHeader
import com.mirkori.inplacex.ui.screens.shared.SegmentedControl
import com.mirkori.inplacex.ui.screens.shared.StatusCard
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector

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
        PageHeroCard(
            title = strings.text("shop.title"),
            subtitle = strings.text("shop.hero.subtitle"),
            accent = PageColors.Shop,
            icon = Icons.Outlined.ShoppingBag,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageStatusPill("${strings.text("top.coins")} · ${progressState.coins}", PageColors.Shop, Modifier.weight(1f))
                PageStatusPill("${strings.text("top.energy")} · ${progressState.campaignEnergy}/${progressState.campaignEnergyMax}", PageColors.Shop, Modifier.weight(1f))
            }
        }
        SegmentedControl(
            options = listOf(strings.text("shop.tab.boosts"), strings.text("shop.tab.premium")),
            selectedIndex = category.ordinal,
            onSelect = { categoryName = ShopCategory.entries[it].name; resultKey = null },
            accent = PageColors.Shop,
        )
        resultKey?.let { key ->
            StatusCard(
                title = strings.text(key),
                accent = if (key == "shop.result.success") PageColors.Success else PageColors.Error,
            )
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
    StatusCard(
        title = strings.text("shop.rewarded.title"),
        message = strings.text("shop.rewarded.coins"),
        accent = PageColors.Shop,
        icon = Icons.Outlined.PlayCircle,
    ) {
        Button(
            onClick = { onWatchRewardedCoins(onReport) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(strings.text("shop.rewarded.watch"))
        }
    }

    PageSectionHeader(strings.text("shop.hints"))
    ShopItemGrid(
        items = listOf(
            ShopItem(
                strings.text("shop.item.open_position"),
                progressState.openPositionHints,
                20,
                onBuyOpenPositionHint,
                strings.text("shop.item.open_position.desc"),
                Icons.Outlined.Lightbulb,
            ),
            ShopItem(
                strings.text("shop.item.check_digit"),
                progressState.checkDigitHints,
                15,
                onBuyCheckDigitHint,
                strings.text("shop.item.check_digit.desc"),
                Icons.Outlined.Search,
            ),
            ShopItem(
                strings.text("shop.item.check_position"),
                progressState.checkPositionHints,
                25,
                onBuyCheckPositionHint,
                strings.text("shop.item.check_position.desc"),
                Icons.Outlined.Pin,
            ),
            ShopItem(
                strings.text("shop.item.extra_moves"),
                progressState.extraMovesBoosts,
                30,
                onBuyExtraMovesBoost,
                strings.text("shop.item.extra_moves.desc"),
                Icons.Outlined.AddCircleOutline,
            ),
            ShopItem(
                strings.text("shop.item.extra_time"),
                progressState.extraTimeBoosts,
                30,
                onBuyExtraTimeBoost,
                strings.text("shop.item.extra_time.desc"),
                Icons.Outlined.Timer,
            ),
            ShopItem(
                strings.text("shop.item.energy"),
                progressState.campaignEnergy,
                25,
                onBuyEnergy,
                strings.text("shop.item.energy.desc"),
                Icons.Outlined.Bolt,
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
    val description: String,
    val icon: ImageVector,
)

@Composable
private fun ShopItemGrid(
    items: List<ShopItem>,
    coins: Int,
    onReport: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { item -> ShopItemCard(item, coins, onReport) }
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
        accentColor = PageColors.Cream,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = PageColors.CreamSecondary) {
                Icon(item.icon, contentDescription = null, tint = PageColors.Text,
                    modifier = Modifier.padding(12.dp).size(28.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, style = PageType.CardTitle)
                Text(strings.text("shop.price").replace("{price}", item.price.toString()), style = PageType.Secondary)
            }
        }
        Text(item.description, style = PageType.Body, color = PageColors.TextSecondary)
        Text(strings.text("shop.stock").replace("{count}", item.stock.toString()), style = PageType.Secondary)
        Button(
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
        ) {
            Text(
                if (affordable) strings.text("shop.buy") else strings.text("shop.not_enough_coins"),
            )
        }
    }
}

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
    PageSectionHeader(strings.text("shop.premium"))
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
            positive -> PageColors.Success.copy(alpha = 0.12f)
            state.notice == BillingNotice.NONE && state.availability == BillingAvailability.READY ->
                PageColors.Cream
            else -> PageColors.Error.copy(alpha = 0.10f)
        },
    ) {
        Text(
            text = when {
                inProgress -> strings.text("shop.billing.checking")
                noticeKey != null -> strings.text(noticeKey)
                state.availability == BillingAvailability.READY -> strings.text("shop.billing.ready")
                else -> strings.text("shop.billing.unavailable")
            },
            style = PageType.Body,
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
            PageColors.Success.copy(alpha = 0.12f)
        } else {
            PageColors.Cream
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.text("shop.product.temporary_pro"),
                modifier = Modifier.weight(1f),
                style = PageType.CardTitle,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = strings.text("shop.price").replace("{price}", price),
                style = MaterialTheme.typography.labelLarge,
                color = PageColors.Text,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = strings.text("shop.product.temporary_pro.desc"),
            style = PageType.Body,
            color = PageColors.TextSecondary,
        )
        Text(
            text = status,
            style = PageType.Body,
            color = if (active || includedInPermanent) PageColors.Success else PageColors.TextSecondary,
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
            PageColors.Success.copy(alpha = 0.12f)
        } else {
            PageColors.Cream
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = PageType.CardTitle,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (active) {
                    strings.text("shop.premium.unlocked")
                } else {
                    priceLabel ?: strings.text("shop.premium.access")
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (active) PageColors.Success else PageColors.TextSecondary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = description,
            style = PageType.Body,
            color = PageColors.TextSecondary,
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
