package com.mirkori.inplacex.ui.screens.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.navigation.AppSection
import com.mirkori.inplacex.ui.navigation.AppSectionCatalog
import com.mirkori.inplacex.ui.navigation.AppSectionIconCatalog

internal fun friendsReferenceHudHeight(width: Dp, fontScale: Float): Dp =
    if (width < 340.dp || fontScale > 1.3f) maxOf(104.dp, (19f * fontScale + 64f).dp)
    else 78.dp * (width.value / 374f).coerceIn(.85f, 1.15f)

@Composable
internal fun FriendsReferenceTopBar(
    energy: Int,
    energyMax: Int,
    coins: Int,
    showBack: Boolean,
    showShop: Boolean,
    onBackClick: () -> Unit,
    onShopClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val numberStyle = FriendsReferenceStyle.Body.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    val energyWidth = with(density) { measurer.measure("$energy/$energyMax", numberStyle).size.width.toDp() }
    val coinsWidth = with(density) { measurer.measure(coins.toString(), numberStyle).size.width.toDp() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val stacked = maxWidth < 340.dp || density.fontScale > 1.3f
        val plusFits = !stacked && energyWidth + coinsWidth + 132.dp + 88.dp + 21.dp <= maxWidth
        val decorationWidth = if (plusFits) 66.dp else 41.dp
        val energyPillWidth = maxOf(if (plusFits) 92.dp else 0.dp, energyWidth + decorationWidth)
        val coinsPillWidth = maxOf(if (plusFits) 111.dp else 0.dp, coinsWidth + decorationWidth)
        val resources: @Composable () -> Unit = {
            ReferenceResourcePill("$energy/$energyMax", strings.text("top.energy"), false,
                Modifier.width(energyPillWidth), plusFits, onShopClick)
            ReferenceResourcePill(coins.toString(), strings.text("top.coins"), true,
                Modifier.width(coinsPillWidth), plusFits, onShopClick)
        }
        val actions: @Composable () -> Unit = {
            if (showShop) {
                ReferenceChromeAction(Icons.Outlined.ShoppingCart, strings.text("section.shop.short"), onShopClick)
            }
            ReferenceChromeAction(Icons.Outlined.Settings, strings.text("top.settings"), onSettingsClick)
        }
        if (showBack) {
            ReferenceChromeAction(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                description = strings.text("top.back"),
                onClick = onBackClick,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 9.dp),
            )
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 9.dp),
            horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                resources()
                if (!stacked) actions()
            }
            if (stacked) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { actions() }
        }
    }
}

@Composable
private fun ReferenceResourcePill(value: String, label: String, coin: Boolean, modifier: Modifier, showAdd: Boolean, onClick: () -> Unit) {
    val height = maxOf(48.dp, (19f * LocalDensity.current.fontScale + 12f).dp)
    Box(modifier.height(height).semantics { contentDescription = "$label: $value" }
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        IllustratedSurface(FriendsReferenceStyle.Chrome, Modifier.fillMaxWidth(),
            rim = Color(0xFF59ADDF), radius = 18.dp) {
            Row(Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (coin) {
                    Box(Modifier.size(21.dp).background(Brush.verticalGradient(listOf(Color(0xFFFFD63B), Color(0xFFFF9A05))), CircleShape)
                        .border(1.dp, Color(0xFFFFE896), CircleShape), contentAlignment = Alignment.Center) {
                        Text("S", style = FriendsReferenceStyle.Body.copy(color = Color(0xFFA96102), fontWeight = FontWeight.Black, fontSize = 16.sp))
                    }
                } else Icon(Icons.Filled.Bolt, label, Modifier.size(21.dp), tint = Color(0xFF4BCBFF))
                Text(value, style = FriendsReferenceStyle.Body.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp),
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showAdd) Box(Modifier.size(21.dp).background(Brush.verticalGradient(listOf(Color(0xFF9EDD3A), Color(0xFF408808))), CircleShape)
                    .border(1.dp, Color(0xFFB8F07F), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, if (coin) label else null, Modifier.size(17.dp), tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ReferenceChromeAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(48.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        IllustratedSurface(FriendsReferenceStyle.Chrome, Modifier.size(44.dp), rim = Color(0xFF5AA8D5), radius = 13.dp) {
            Icon(icon, description, Modifier.align(Alignment.Center).size(26.dp), tint = FriendsReferenceStyle.LightRim)
        }
    }
}

@Composable
internal fun FriendsReferenceBottomBar(
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    socialNotificationCount: Int,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    IllustratedSurface(FriendsReferenceStyle.Chrome, modifier.fillMaxSize(), rim = Color(0xFF4F9AC5), radius = 16.dp) {
        Row(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            AppSection.entries.forEach { section ->
                val selected = currentSection == section
                val title = AppSectionCatalog.shortLabel(section, strings)
                val tabModifier = Modifier.weight(1f).fillMaxSize()
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSectionChange(section) })
                val tabContent: @Composable () -> Unit = {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                            Icon(AppSectionIconCatalog.spec(section).fallbackIcon, null,
                                Modifier.size(26.dp), tint = FriendsReferenceStyle.LightRim)
                            if (section == AppSection.SOCIAL) ReferenceCountBadge(socialNotificationCount,
                                Modifier.align(Alignment.TopEnd).padding(start = 17.dp))
                        }
                        Text(title, style = FriendsReferenceStyle.Small.copy(color = Color.White, fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
                            modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (selected) IllustratedSurface(listOf(Color(0xFF187CC3), Color(0xFF0964AC), Color(0xFF064677)),
                    tabModifier, rim = Color(0xFF89D1FF), radius = 14.dp) { tabContent() }
                else Box(tabModifier) { tabContent() }
            }
        }
    }
}
