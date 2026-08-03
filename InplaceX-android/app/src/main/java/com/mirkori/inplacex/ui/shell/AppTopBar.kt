package com.mirkori.inplacex.ui.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.config.AppConfigCatalog
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.theme.InplaceXColors

@Composable
fun AppTopBar(
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
    val appearance = AppConfigCatalog.platformConfig.shellAppearance.topBar

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compact = maxWidth < 420.dp
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (compact) 4.dp else 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBack) {
                TopCircleAction(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = strings.text("top.back"),
                    onClick = onBackClick
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopStatPill(
                    icon = Icons.Outlined.Bolt,
                    iconTint = appearance.energyIcon.tintColor.takeOrElse(InplaceXColors.ToyOrangeTop),
                    value = "$energy/$energyMax",
                    showAdd = true,
                    compact = compact,
                    contentDescription = strings.text("top.energy")
                )
                TopStatPill(
                    icon = Icons.Outlined.MonetizationOn,
                    iconTint = appearance.coinsIcon.tintColor.takeOrElse(InplaceXColors.ToyOrangeTop),
                    value = coins.toString(),
                    showAdd = true,
                    compact = compact,
                    contentDescription = strings.text("top.coins")
                )
                if (showShop) {
                    TopShopAction(
                        label = strings.text("section.shop.short"),
                        compact = compact,
                        onClick = onShopClick,
                    )
                }
                TopCircleAction(
                    icon = Icons.Outlined.Settings,
                    contentDescription = strings.text("top.settings"),
                    onClick = onSettingsClick
                )
            }
        }
    }
}

@Composable
private fun TopCircleAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(48.dp)
            .shadow(7.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = InplaceXColors.ToyBlue,
        contentColor = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(2.dp, InplaceXColors.ToyCyan.copy(alpha = 0.72f)),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.background(topBlueBrush())) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun TopStatPill(
    icon: ImageVector,
    iconTint: Color,
    value: String,
    showAdd: Boolean,
    compact: Boolean,
    contentDescription: String,
) {
    Surface(
        modifier = Modifier.shadow(7.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        contentColor = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(2.dp, InplaceXColors.ToyCyan.copy(alpha = 0.68f)),
    ) {
        Row(
            modifier = Modifier
                .background(topBlueBrush())
                .padding(horizontal = if (compact) 5.dp else 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(if (compact) 20.dp else 24.dp),
            )
            Text(
                text = value,
                style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = Color.White,
            )
            if (showAdd) {
                Surface(
                    modifier = Modifier.size(if (compact) 20.dp else 24.dp),
                    shape = CircleShape,
                    color = InplaceXColors.ToyGreen,
                    contentColor = Color.White,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.54f)),
                    shadowElevation = 2.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.size(if (compact) 15.dp else 18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopShopAction(
    label: String,
    compact: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .shadow(7.dp, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        contentColor = Color.White,
        border = BorderStroke(2.dp, InplaceXColors.ToyCyan.copy(alpha = 0.68f)),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .background(topBlueBrush())
                .padding(horizontal = if (compact) 10.dp else 9.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ShoppingCart,
                contentDescription = label,
                modifier = Modifier.size(22.dp),
            )
            if (!compact) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun topBlueBrush(): Brush = Brush.verticalGradient(
    listOf(
        InplaceXColors.ToyBlueTop,
        InplaceXColors.ToyBlue,
        InplaceXColors.ToyBlueDeep,
    ),
)

private fun Color.takeOrElse(fallback: Color): Color =
    if (this == Color.Unspecified || this == Color.Transparent) fallback else this
