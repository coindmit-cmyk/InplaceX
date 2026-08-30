package com.mirkori.inplacex.ui.screens.shop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import com.mirkori.inplacex.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirkori.inplacex.core.monetization.TemporaryProPolicy
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.services.BillingAvailability
import com.mirkori.inplacex.platform.services.BillingNotice
import com.mirkori.inplacex.platform.services.BillingProductId
import com.mirkori.inplacex.platform.services.BillingState
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import com.mirkori.inplacex.ui.screens.shared.SceneSplitStatRow
import com.mirkori.inplacex.ui.theme.InplaceXColors
import com.mirkori.inplacex.ui.theme.PageColors
import com.mirkori.inplacex.ui.theme.PageType
import com.mirkori.inplacex.ui.screens.shared.PageHeroCard
import com.mirkori.inplacex.ui.screens.shared.PageStatusPill
import com.mirkori.inplacex.ui.screens.shared.PageSectionHeader
import com.mirkori.inplacex.ui.screens.shared.StatusCard
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
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

enum class ShopPremiumDestination {
    OVERVIEW,
    PRODUCTS,
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
    premiumDestination: ShopPremiumDestination? = null,
    onPremiumDestinationChange: (ShopPremiumDestination) -> Unit = {},
) {
    val strings = LocalAppStrings.current
    var categoryName by rememberSaveable { mutableStateOf(ShopCategory.BOOSTS.name) }
    var savedPremiumDestinationName by rememberSaveable {
        mutableStateOf(ShopPremiumDestination.OVERVIEW.name)
    }
    var resultKey by rememberSaveable { mutableStateOf<String?>(null) }
    val category = ShopCategory.valueOf(categoryName)
    val activePremiumDestination = premiumDestination
        ?: ShopPremiumDestination.valueOf(savedPremiumDestinationName)

    fun navigatePremium(destination: ShopPremiumDestination) {
        if (premiumDestination == null) {
            savedPremiumDestinationName = destination.name
        }
        onPremiumDestinationChange(destination)
    }

    fun report(result: Boolean) {
        resultKey = if (result) "shop.result.success" else "shop.result.unavailable"
    }

    BackHandler(enabled = activePremiumDestination == ShopPremiumDestination.PRODUCTS) {
        navigatePremium(ShopPremiumDestination.OVERVIEW)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("shop-reference-screen"),
    ) {
        val compactReference = maxWidth >= 320.dp && LocalDensity.current.fontScale <= 1.2f
        val compactViewport = compactReference && maxHeight < 650.dp
        val premiumOverview = category == ShopCategory.PREMIUM &&
            activePremiumDestination == ShopPremiumDestination.OVERVIEW
        val heroHeight = when {
            premiumOverview && compactReference -> 95.dp
            compactViewport -> 80.dp
            else -> 82.dp
        }
        val rewardedHeight = when {
            premiumOverview && compactReference -> 180.dp
            compactViewport -> 136.dp
            else -> 141.dp
        }
        val itemHeight = if (compactViewport) 144.dp else 152.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 15.dp),
        ) {
            if (activePremiumDestination == ShopPremiumDestination.PRODUCTS) {
                PremiumProductsReferenceContent(
                    progressState = progressState,
                    nowMs = nowMs,
                    billingState = billingState,
                    billingInProgress = billingInProgress,
                    resultKey = resultKey,
                    onReport = ::report,
                    onRefreshBilling = onRefreshBilling,
                    onOpenProfile = onOpenProfile,
                    onBuyRemoveAds = onBuyRemoveAds,
                    onBuyPro = onBuyPro,
                    onBuyProPlus = onBuyProPlus,
                    onRetryBillingPurchase = onRetryBillingPurchase,
                    onBuyTemporaryPro = onBuyTemporaryPro,
                )
            } else {
                PageHeroCard(
                    title = strings.text("shop.title"),
                    subtitle = strings.text("shop.hero.subtitle"),
                    accent = if (premiumOverview) Color(0xFF285A7E) else PageColors.Friends,
                    leading = {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.art_shop_bag_v10),
                                contentDescription = null,
                                modifier = Modifier
                                    .requiredSize(76.dp)
                                    .offset(x = (-2).dp, y = (-4).dp),
                            )
                        }
                    },
                    modifier = Modifier
                        .heightIn(min = heroHeight)
                        .testTag("shop-hero"),
                )
                if (premiumOverview && compactReference) {
                    Spacer(Modifier.height(10.dp))
                }
                RewardedAdCard(
                    compact = compactReference,
                    height = rewardedHeight,
                    onWatch = { onWatchRewardedCoins(::report) },
                    modifier = Modifier.testTag("shop-reward"),
                )
                Spacer(Modifier.height(if (premiumOverview) 8.dp else if (compactViewport) 6.dp else 8.dp))
                ShopCategoryControl(
                    selected = category,
                    onSelect = {
                        categoryName = it.name
                        if (it != ShopCategory.PREMIUM) {
                            navigatePremium(ShopPremiumDestination.OVERVIEW)
                        }
                        resultKey = null
                    },
                )
                Spacer(Modifier.height(if (premiumOverview) 6.dp else if (compactViewport) 4.dp else 6.dp))
                resultKey?.let { key ->
                    StatusCard(
                        title = strings.text(key),
                        accent = if (key == "shop.result.success") PageColors.Success else PageColors.Error,
                    )
                    Spacer(Modifier.height(6.dp))
                }

                when (category) {
                    ShopCategory.BOOSTS -> BoostsCatalog(
                        progressState = progressState,
                        onReport = ::report,
                        compactReference = compactReference,
                        itemHeight = itemHeight,
                        sectionToGridGap = if (compactViewport) 6.dp else 8.dp,
                        onBuyOpenPositionHint = onBuyOpenPositionHint,
                        onBuyCheckDigitHint = onBuyCheckDigitHint,
                        onBuyCheckPositionHint = onBuyCheckPositionHint,
                        onBuyExtraMovesBoost = onBuyExtraMovesBoost,
                        onBuyExtraTimeBoost = onBuyExtraTimeBoost,
                        onBuyEnergy = onBuyEnergy,
                    )

                    ShopCategory.PREMIUM -> PremiumOverviewReferenceContent(
                        compactReference = compactReference,
                        onOpenProducts = {
                            resultKey = null
                            navigatePremium(ShopPremiumDestination.PRODUCTS)
                        },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun RewardedAdCard(
    compact: Boolean,
    height: Dp,
    onWatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    if (!compact) {
        PageHeroCard(
            title = strings.text("shop.rewarded.title"),
            subtitle = strings.text("shop.rewarded.coins"),
            accent = PageColors.Friends,
            modifier = modifier,
            leading = {
                Image(
                    painter = painterResource(R.drawable.art_reward_coins_gems_v10),
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            },
        ) {
            Button(
                onClick = onWatch,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(strings.text("shop.rewarded.watch"))
            }
        }
        return
    }

    val rewardAmount = "+" + strings.text("shop.price").replace("{price}", "20")
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        shape = shape,
        color = Color.Transparent,
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color(0xFFD79872)),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF6743B4),
                            Color(0xFF342D72),
                            Color(0xFF171D4D),
                        ),
                    ),
                )
                .padding(start = 8.dp, top = 7.dp, end = 14.dp, bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val rewardArtworkSlot = if (height < 140.dp) 104.dp else 112.dp
            Box(
                modifier = Modifier.size(rewardArtworkSlot),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.art_reward_coins_gems_v10),
                    contentDescription = null,
                    modifier = Modifier
                        .requiredSize(rewardArtworkSlot + 28.dp)
                        .offset(x = 4.dp, y = (-14).dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = strings.text("shop.rewarded.title"),
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        fontSize = 18.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Image(
                        painter = painterResource(R.drawable.art_reward_video_v10),
                        contentDescription = null,
                        modifier = Modifier
                            .size(42.dp)
                            .offset(y = 8.dp),
                    )
                }
                Text(
                    text = rewardAmount,
                    color = Color(0xFFFFE447),
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = strings.text("shop.rewarded.coins"),
                    color = Color.White.copy(alpha = .96f),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                RewardedWatchButton(
                    label = strings.text("shop.rewarded.watch"),
                    onClick = onWatch,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun RewardedWatchButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(110.dp)
            .height(48.dp)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color(0xFFC99CFF)),
            shadowElevation = 2.dp,
        ) {
            Box(
                modifier = Modifier.background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF9F65D7), Color(0xFF7138A9)),
                    ),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ShopCategoryControl(
    selected: ShopCategory,
    onSelect: (ShopCategory) -> Unit,
) {
    val strings = LocalAppStrings.current
    val options = listOf(
        ShopCategory.BOOSTS to strings.text("shop.tab.boosts"),
        ShopCategory.PREMIUM to strings.text("shop.tab.premium"),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("shop-tabs"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            shape = RoundedCornerShape(15.dp),
            color = PageColors.Cream,
            border = BorderStroke(1.dp, PageColors.Border),
            shadowElevation = 2.dp,
        ) {}
        Row(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup(),
        ) {
            options.forEach { (category, label) ->
                val isSelected = selected == category
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = { onSelect(category) },
                        )
                        .semantics {
                            this.selected = isSelected
                            contentDescription = label
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp),
                            shape = RoundedCornerShape(15.dp),
                            color = Color.Transparent,
                            shadowElevation = 1.dp,
                        ) {
                            Box(
                                modifier = Modifier.background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFFFFB300), Color(0xFFF18700)),
                                    ),
                                ),
                            )
                        }
                    }
                    Text(
                        text = label,
                        color = if (isSelected) Color.White else PageColors.Text,
                        fontSize = 14.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShopSectionHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .padding(start = 4.dp)
            .testTag("shop-section")
            .semantics { heading() },
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = CircleShape,
            color = Color(0xFFFFE5A7),
            contentColor = Color(0xFF6B4C1E),
            border = BorderStroke(1.dp, Color(0xFFD99C26)),
            shadowElevation = 1.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            text = title,
            color = Color.White,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BoostsCatalog(
    progressState: GameProgressState,
    onReport: (Boolean) -> Unit,
    compactReference: Boolean,
    itemHeight: Dp,
    sectionToGridGap: Dp,
    onBuyOpenPositionHint: () -> Boolean,
    onBuyCheckDigitHint: () -> Boolean,
    onBuyCheckPositionHint: () -> Boolean,
    onBuyExtraMovesBoost: () -> Boolean,
    onBuyExtraTimeBoost: () -> Boolean,
    onBuyEnergy: () -> Boolean,
) {
    val strings = LocalAppStrings.current
    ShopSectionHeader(strings.text("shop.hints"))
    Spacer(Modifier.height(sectionToGridGap))
    ShopItemGrid(
        items = listOf(
            ShopItem(
                strings.text("shop.item.open_position"),
                progressState.openPositionHints,
                20,
                onBuyOpenPositionHint,
                strings.text("shop.item.open_position.desc"),
                Icons.Outlined.Lightbulb, R.drawable.art_hint_open_position_v10,
            ),
            ShopItem(
                strings.text("shop.item.check_digit"),
                progressState.checkDigitHints,
                15,
                onBuyCheckDigitHint,
                strings.text("shop.item.check_digit.desc"),
                Icons.Outlined.Search, R.drawable.art_hint_check_digit_v10,
            ),
            ShopItem(
                strings.text("shop.item.energy"),
                progressState.campaignEnergy,
                25,
                onBuyEnergy,
                strings.text("shop.item.energy.desc"),
                Icons.Outlined.Bolt, R.drawable.art_energy_v10,
            ),
            ShopItem(
                strings.text("shop.item.check_position"),
                progressState.checkPositionHints,
                25,
                onBuyCheckPositionHint,
                strings.text("shop.item.check_position.desc"),
                Icons.Outlined.Pin, R.drawable.art_hint_check_position_v10,
            ),
            ShopItem(
                strings.text("shop.item.extra_moves"),
                progressState.extraMovesBoosts,
                30,
                onBuyExtraMovesBoost,
                strings.text("shop.item.extra_moves.desc"),
                Icons.Outlined.AddCircleOutline, R.drawable.art_boost_extra_moves_v10,
            ),
            ShopItem(
                strings.text("shop.item.extra_time"),
                progressState.extraTimeBoosts,
                30,
                onBuyExtraTimeBoost,
                strings.text("shop.item.extra_time.desc"),
                Icons.Outlined.Timer, R.drawable.art_boost_extra_time_v10,
            ),
        ),
        coins = progressState.coins,
        onReport = onReport,
        compactReference = compactReference,
        itemHeight = itemHeight,
    )
}

private data class ShopItem(
    val title: String,
    val stock: Int,
    val price: Int,
    val onBuy: () -> Boolean,
    val description: String,
    val icon: ImageVector,
    val artwork: Int? = null,
)

@Composable
private fun ShopItemGrid(
    items: List<ShopItem>,
    coins: Int,
    onReport: (Boolean) -> Unit,
    compactReference: Boolean,
    itemHeight: Dp,
) {
    BoxWithConstraints {
        val columns = if (compactReference && maxWidth >= 320.dp) 2 else 1
        val rows = items.withIndex().toList().chunked(columns)
        Column {
            rows.forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) {
                    Spacer(
                        Modifier.height(
                            if (columns == 2 && rowIndex == 2) 64.dp else 8.dp,
                        ),
                    )
                }
                Row(
                    modifier = if (columns == 2) {
                        Modifier
                            .fillMaxWidth()
                            .height(itemHeight)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { indexedItem ->
                        ShopItemCard(
                            item = indexedItem.value,
                            coins = coins,
                            onReport = onReport,
                            compact = columns == 2,
                            rowHeight = if (itemHeight >= 150.dp) 92.dp else 84.dp,
                            artworkSize = if (itemHeight >= 150.dp) 88.dp else 82.dp,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("shop-item-${indexedItem.index}")
                                .then(if (columns == 2) Modifier.fillMaxHeight() else Modifier),
                        )
                    }
                    repeat(columns - row.size) {
                        Spacer(Modifier.weight(1f))
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
    compact: Boolean,
    rowHeight: Dp,
    artworkSize: Dp,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val affordable = coins >= item.price
    val stockLabel = strings.text("shop.stock").replace("{count}", item.stock.toString())
    val priceLabel = strings.text("shop.price").replace("{price}", item.price.toString())
    val errorLabel = strings.text("shop.not_enough_coins")

    if (!compact) {
        ExpandedShopItemCard(
            item = item,
            affordable = affordable,
            stockLabel = stockLabel,
            priceLabel = priceLabel,
            errorLabel = errorLabel,
            onReport = onReport,
            modifier = modifier,
        )
        return
    }

    val titleParts = compactShopTitle(item.title)
    Surface(
        modifier = modifier.semantics {
            contentDescription = "${item.title}. ${item.description}. $stockLabel. $priceLabel"
        },
        shape = RoundedCornerShape(16.dp),
        color = PageColors.Cream,
        contentColor = PageColors.Text,
        border = BorderStroke(1.dp, PageColors.Border),
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(PageColors.Cream, Color(0xFFFFEAC5)),
                    ),
                )
                .padding(horizontal = 7.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.artwork != null) {
                    Box(
                        modifier = Modifier.size(60.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(item.artwork),
                            contentDescription = null,
                            modifier = Modifier.requiredSize(
                                if (item.artwork == R.drawable.art_hint_open_position_v10) {
                                    artworkSize + 12.dp
                                } else {
                                    artworkSize
                                },
                            ),
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.size(60.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = PageColors.Shop,
                            modifier = Modifier.size(50.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = titleParts.first,
                        color = PageColors.Text,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (titleParts.second.isBlank()) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (titleParts.second.isNotBlank()) {
                        Text(
                            text = titleParts.second,
                            color = PageColors.Text,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = stockLabel,
                        color = PageColors.Text,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!affordable) {
                        Text(
                            text = errorLabel,
                            color = PageColors.Error,
                            fontSize = 9.sp,
                            lineHeight = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            ShopPriceButton(
                price = item.price,
                priceLabel = priceLabel,
                errorLabel = errorLabel,
                enabled = affordable,
                onClick = { onReport(item.onBuy()) },
            )
        }
    }
}

private fun compactShopTitle(title: String): Pair<String, String> {
    val separator = title.indexOf(':')
    if (separator in 1 until title.lastIndex) {
        return title.substring(0, separator + 1).trim() to title.substring(separator + 1).trim()
    }
    val increment = title.indexOf("+1")
    return if (increment > 0) {
        title.substring(0, increment).trim() to title.substring(increment).trim()
    } else title to ""
}

@Composable
private fun ShopPriceButton(
    price: Int,
    priceLabel: String,
    errorLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                role = Role.Button
                contentDescription = priceLabel
                if (!enabled) stateDescription = errorLabel
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color(0xFF79B6C2)),
            shadowElevation = if (enabled) 2.dp else 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            if (enabled) {
                                listOf(Color(0xFF2D7E91), Color(0xFF105466))
                            } else {
                                listOf(Color(0xFFCABEA9), Color(0xFFAA9C88))
                            },
                        ),
                    ),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = price.toString(),
                    fontSize = 17.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(Modifier.width(7.dp))
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFFFFD83F), Color(0xFFF48A00)),
                            ),
                            CircleShape,
                        )
                        .border(1.dp, Color(0xFFFFEAA0), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "S",
                        color = Color(0xFFA55B00),
                        fontSize = 11.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedShopItemCard(
    item: ShopItem,
    affordable: Boolean,
    stockLabel: String,
    priceLabel: String,
    errorLabel: String,
    onReport: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SceneCard(
        modifier = modifier.semantics {
            contentDescription = "${item.title}. ${item.description}. $stockLabel. $priceLabel"
        },
        accentColor = PageColors.Cream,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (item.artwork != null) Image(painterResource(item.artwork), null, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
            else Icon(item.icon, contentDescription = null, tint = PageColors.Shop, modifier = Modifier.size(40.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, style = PageType.Secondary, fontWeight = FontWeight.Bold)
            }
        }
        Text(item.description, style = PageType.Secondary, color = PageColors.TextSecondary)
        Text(stockLabel, style = PageType.Secondary)
        if (!affordable) Text(errorLabel, style = PageType.Secondary, color = PageColors.Error)
        Button(
            onClick = { onReport(item.onBuy()) },
            enabled = affordable,
            accent = Color(0xFF186276),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics {
                    role = Role.Button
                    if (!affordable) {
                        stateDescription = errorLabel
                    }
                },
        ) {
            Text(priceLabel)
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
