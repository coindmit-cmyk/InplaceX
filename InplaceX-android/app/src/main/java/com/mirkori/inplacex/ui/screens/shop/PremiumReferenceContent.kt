package com.mirkori.inplacex.ui.screens.shop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirkori.inplacex.R
import com.mirkori.inplacex.core.monetization.TemporaryProPolicy
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.services.BillingAvailability
import com.mirkori.inplacex.platform.services.BillingNotice
import com.mirkori.inplacex.platform.services.BillingProductId
import com.mirkori.inplacex.platform.services.BillingState
import com.mirkori.inplacex.ui.screens.shared.PagePrimaryButton
import com.mirkori.inplacex.ui.screens.shared.PageSecondaryButton
import com.mirkori.inplacex.ui.theme.PageColors

private enum class PremiumArtwork {
    REMOVE_ADS,
    TEMPORARY_PRO,
    PRO,
    PRO_PLUS,
}

internal enum class PremiumBillingActionTarget {
    BUY,
    RETRY,
}

internal data class PremiumBillingActionState(
    val enabled: Boolean,
    val target: PremiumBillingActionTarget,
)

internal fun premiumBillingActionState(
    state: BillingState,
    productId: BillingProductId,
    inProgress: Boolean,
): PremiumBillingActionState = PremiumBillingActionState(
    enabled = canStartBillingAction(state, productId, inProgress),
    target = if (state.pendingProduct == productId) {
        PremiumBillingActionTarget.RETRY
    } else {
        PremiumBillingActionTarget.BUY
    },
)

internal fun shouldShowPremiumBillingNotice(
    state: BillingState,
    inProgress: Boolean,
): Boolean = inProgress || state.notice !in setOf(
    BillingNotice.NONE,
    BillingNotice.CONFIGURATION_REQUIRED,
)

@Composable
internal fun PremiumOverviewReferenceContent(
    compactReference: Boolean,
) {
    val strings = LocalAppStrings.current
    val rows = listOf(
        PremiumOverviewRow(
            title = strings.text("shop.product.remove_ads"),
            description = strings.text("shop.product.remove_ads.desc"),
            artwork = PremiumOverviewArtwork.RemoveAds,
        ),
        PremiumOverviewRow(
            title = strings.text("shop.premium.overview.pro.title"),
            description = strings.text("shop.premium.overview.pro.description"),
            artwork = PremiumOverviewArtwork.Icon(Icons.Outlined.Bolt, Color(0xFF285EB7)),
        ),
        PremiumOverviewRow(
            title = strings.text("shop.premium.overview.maximum.title"),
            description = strings.text("shop.premium.overview.maximum.description"),
            artwork = PremiumOverviewArtwork.Icon(Icons.Outlined.Star, Color(0xFFE9A300)),
        ),
    )
    val combinedDescription = rows.joinToString(". ") { "${it.title}: ${it.description}" }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (compactReference) 325.dp else 300.dp)
            .testTag("shop-premium-overview")
            .semantics {
                contentDescription = combinedDescription
            },
        shape = RoundedCornerShape(17.dp),
        color = Color.Transparent,
        contentColor = PageColors.Text,
        border = BorderStroke(1.dp, Color(0xFFDCA64A)),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFFFF4DE), Color(0xFFFFEBC8)),
                    ),
                )
                .padding(horizontal = 13.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.art_premium_crown_v10),
                    contentDescription = null,
                    modifier = Modifier.size(25.dp),
                )
                Text(
                    text = strings.text("shop.premium.overview.title"),
                    fontSize = 19.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                            .height(1.dp)
                            .background(Color(0xFFD8B374)),
                    )
                }
                PremiumOverviewFeature(
                    row = row,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private data class PremiumOverviewRow(
    val title: String,
    val description: String,
    val artwork: PremiumOverviewArtwork,
)

private sealed interface PremiumOverviewArtwork {
    data object RemoveAds : PremiumOverviewArtwork
    data class Icon(val image: ImageVector, val tint: Color) : PremiumOverviewArtwork
}

@Composable
private fun PremiumOverviewFeature(
    row: PremiumOverviewRow,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (val artwork = row.artwork) {
            PremiumOverviewArtwork.RemoveAds -> RemoveAdsBadge(54.dp)
            is PremiumOverviewArtwork.Icon -> PremiumRoundIcon(
                image = artwork.image,
                tint = artwork.tint,
                size = 54.dp,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = row.title,
                color = PageColors.Text,
                fontSize = 16.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.description,
                color = PageColors.TextSecondary,
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun PremiumProductsReferenceContent(
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("shop-premium-products"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (shouldShowPremiumBillingNotice(billingState, billingInProgress)) {
            PremiumBillingNotice(
                state = billingState,
                inProgress = billingInProgress,
                onRefresh = onRefreshBilling,
                onOpenProfile = onOpenProfile,
                onRetryPurchase = onRetryBillingPurchase,
            )
        }
        ReferenceBillingProductCard(
            productId = BillingProductId.REMOVE_ADS,
            title = strings.text("shop.product.remove_ads"),
            description = strings.text("shop.product.remove_ads.desc"),
            artwork = PremiumArtwork.REMOVE_ADS,
            minHeight = 150.dp,
            active = billingState.entitlements.adFreePurchased,
            billingState = billingState,
            inProgress = billingInProgress,
            onBuy = onBuyRemoveAds,
            onRetry = onRetryBillingPurchase,
        )
        ReferenceTemporaryProCard(
            progressState = progressState,
            nowMs = nowMs,
            onAction = { onReport(onBuyTemporaryPro()) },
        )
        ReferenceBillingProductCard(
            productId = BillingProductId.PRO_SUBSCRIPTION,
            title = strings.text("shop.product.pro"),
            description = strings.text("shop.product.pro.desc"),
            artwork = PremiumArtwork.PRO,
            minHeight = 160.dp,
            active = billingState.entitlements.effectiveProAccessActive,
            billingState = billingState,
            inProgress = billingInProgress,
            onBuy = onBuyPro,
            onRetry = onRetryBillingPurchase,
        )
        ReferenceBillingProductCard(
            productId = BillingProductId.PRO_PLUS_SUBSCRIPTION,
            title = strings.text("shop.product.pro_plus"),
            description = strings.text("shop.product.pro_plus.desc"),
            artwork = PremiumArtwork.PRO_PLUS,
            minHeight = 190.dp,
            active = billingState.entitlements.proPlusSubscriptionActive,
            billingState = billingState,
            inProgress = billingInProgress,
            onBuy = onBuyProPlus,
            onRetry = onRetryBillingPurchase,
        )
    }
}

@Composable
private fun ReferenceBillingProductCard(
    productId: BillingProductId,
    title: String,
    description: String,
    artwork: PremiumArtwork,
    minHeight: Dp,
    active: Boolean,
    billingState: BillingState,
    inProgress: Boolean,
    onBuy: () -> Unit,
    onRetry: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val product = billingState.products[productId]
    val actionState = premiumBillingActionState(billingState, productId, inProgress)
    val descriptionWithTerm = if (productId == BillingProductId.REMOVE_ADS) {
        description
    } else {
        val duration = product?.accessDurationSeconds?.let { seconds ->
            formatPrepaidAccessDuration(seconds) { key -> strings.text(key) }
        }
        val term = if (duration == null) {
            strings.text("shop.product.prepaid_duration_unknown")
        } else {
            strings.text("shop.product.prepaid_duration").replace("{duration}", duration)
        }
        "$description $term"
    }
    val isPending = billingState.pendingProduct == productId
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
    val topLabel = when {
        active -> strings.text("shop.premium.unlocked")
        product != null -> formatBillingPrice(product.amountMinor, product.currency)
        else -> strings.text("shop.premium.access")
    }

    ReferencePremiumProductCard(
        title = title,
        description = descriptionWithTerm,
        topLabel = topLabel,
        status = null,
        actionLabel = actionLabel,
        actionEnabled = actionState.enabled,
        active = active,
        artwork = artwork,
        minHeight = minHeight,
        actionTag = "shop-premium-action-${productId.name.lowercase()}",
        onAction = if (actionState.target == PremiumBillingActionTarget.RETRY) onRetry else onBuy,
    )
}

@Composable
private fun ReferenceTemporaryProCard(
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

    ReferencePremiumProductCard(
        title = strings.text("shop.product.temporary_pro"),
        description = strings.text("shop.product.temporary_pro.desc"),
        topLabel = strings.text("shop.price").replace("{price}", price),
        status = status,
        actionLabel = actionLabel,
        actionEnabled = enabled,
        active = active || includedInPermanent,
        artwork = PremiumArtwork.TEMPORARY_PRO,
        minHeight = 196.dp,
        actionTag = "shop-premium-action-temporary-pro",
        onAction = onAction,
    )
}

@Composable
private fun ReferencePremiumProductCard(
    title: String,
    description: String,
    topLabel: String,
    status: String?,
    actionLabel: String,
    actionEnabled: Boolean,
    active: Boolean,
    artwork: PremiumArtwork,
    minHeight: Dp,
    actionTag: String,
    onAction: () -> Unit,
) {
    val semanticsText = buildString {
        append(title)
        append(". ")
        append(description)
        append(". ")
        append(topLabel)
        status?.let {
            append(". ")
            append(it)
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .testTag("shop-premium-product-${artwork.name.lowercase()}")
            .semantics { contentDescription = semanticsText },
        shape = RoundedCornerShape(17.dp),
        color = Color.Transparent,
        contentColor = PageColors.Text,
        border = BorderStroke(
            1.dp,
            if (active) Color(0xFF77A747) else Color(0xFFDCA64A),
        ),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        if (active) {
                            listOf(Color(0xFFF1F8D8), Color(0xFFFFEDCB))
                        } else {
                            listOf(Color(0xFFFFF5E1), Color(0xFFFFEBCB))
                        },
                    ),
                )
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                PremiumProductArtwork(
                    artwork = artwork,
                    modifier = Modifier.size(58.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f),
                            color = PageColors.Text,
                            fontSize = 17.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = topLabel,
                            color = if (active) PageColors.Success else PageColors.TextSecondary,
                            fontSize = 10.5.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = description,
                        color = PageColors.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                    status?.let { value ->
                        Text(
                            text = value,
                            color = if (active) PageColors.Success else PageColors.Text,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            PagePrimaryButton(
                onClick = onAction,
                enabled = actionEnabled,
                accent = Color(0xFF1269C4),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(actionTag)
                    .semantics {
                        role = Role.Button
                        if (!actionEnabled) stateDescription = actionLabel
                    },
            ) {
                Text(
                    text = actionLabel,
                    fontSize = 14.sp,
                    lineHeight = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PremiumProductArtwork(
    artwork: PremiumArtwork,
    modifier: Modifier = Modifier,
) {
    when (artwork) {
        PremiumArtwork.REMOVE_ADS -> RemoveAdsBadge(58.dp, modifier)
        PremiumArtwork.TEMPORARY_PRO -> ProTextBadge(
            label = "PRO",
            artworkResource = R.drawable.art_pro_hour_badge_v11,
            size = 58.dp,
            modifier = modifier,
        )
        PremiumArtwork.PRO -> ProTextBadge(
            label = "PRO",
            artworkResource = R.drawable.art_pro_badge_v11,
            size = 58.dp,
            modifier = modifier,
        )
        PremiumArtwork.PRO_PLUS -> ProTextBadge(
            label = "PRO+",
            artworkResource = R.drawable.art_pro_plus_badge_v11,
            size = 58.dp,
            modifier = modifier,
        )
    }
}

@Composable
private fun RemoveAdsBadge(
    badgeSize: Dp,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(badgeSize),
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.art_remove_ads_v11),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = 1.45f, scaleY = 1.45f),
            )
        }
    }
}

@Composable
private fun PremiumRoundIcon(
    image: ImageVector,
    tint: Color,
    size: Dp,
) {
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = Color(0xFFFFF1D6),
        border = BorderStroke(1.dp, Color(0xFFE6BD72)),
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = image,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size * .60f),
            )
        }
    }
}

@Composable
private fun ProTextBadge(
    label: String,
    artworkResource: Int,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(artworkResource),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = 1.28f, scaleY = 1.28f),
        )
        Text(
            text = label,
            color = Color(0xFFFFEB43),
            fontSize = if (label.length > 3) 11.sp else 14.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun PremiumBillingNotice(
    state: BillingState,
    inProgress: Boolean,
    onRefresh: () -> Unit,
    onOpenProfile: () -> Unit,
    onRetryPurchase: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val noticeKey = billingNoticeLocalizationKey(state.notice)
    val title = when {
        inProgress -> strings.text("shop.billing.checking")
        noticeKey != null -> strings.text(noticeKey)
        state.availability == BillingAvailability.READY -> strings.text("shop.billing.ready")
        else -> strings.text("shop.billing.unavailable")
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("shop-premium-billing-notice"),
        shape = RoundedCornerShape(15.dp),
        color = Color(0xFFFFE8C0),
        contentColor = PageColors.Text,
        border = BorderStroke(1.dp, Color(0xFFDCA64A)),
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            when {
                state.notice == BillingNotice.LINKED_ACCOUNT_REQUIRED -> PagePrimaryButton(
                    onClick = onOpenProfile,
                    enabled = !inProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.text("shop.billing.open_profile"))
                }
                shouldRetryPendingPurchase(state) -> PagePrimaryButton(
                    onClick = onRetryPurchase,
                    enabled = !inProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.text("shop.billing.retry_payment"))
                }
                else -> PageSecondaryButton(
                    onClick = onRefresh,
                    enabled = !inProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.text("shop.billing.refresh"))
                }
            }
        }
    }
}
