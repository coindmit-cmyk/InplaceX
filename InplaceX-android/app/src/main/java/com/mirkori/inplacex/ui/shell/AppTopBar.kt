package com.mirkori.inplacex.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    coins: Int,
    showBack: Boolean,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val appearance = AppConfigCatalog.platformConfig.shellAppearance.topBar

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showBack) {
            TopCircleAction(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = strings.text("top.back"),
                tint = appearance.backIcon.tintColor.takeOrElse(MaterialTheme.colorScheme.onSurface),
                onClick = onBackClick
            )
        } else {
            Box(modifier = Modifier.size(44.dp))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopStatPill(
                icon = Icons.Outlined.Bolt,
                iconTint = appearance.energyIcon.tintColor.takeOrElse(Color(0xFF4267FF)),
                value = energy.toString(),
                contentDescription = strings.text("top.energy")
            )
            TopStatPill(
                icon = Icons.Outlined.MonetizationOn,
                iconTint = appearance.coinsIcon.tintColor.takeOrElse(Color(0xFFE09A12)),
                value = coins.toString(),
                contentDescription = strings.text("top.coins")
            )
            TopCircleAction(
                icon = Icons.Outlined.Settings,
                contentDescription = strings.text("top.settings"),
                tint = appearance.settingsIcon.tintColor.takeOrElse(MaterialTheme.colorScheme.onSurface),
                onClick = onSettingsClick
            )
        }
    }
}

@Composable
private fun TopCircleAction(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = InplaceXColors.Surface.copy(alpha = 0.94f),
        contentColor = tint,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            InplaceXColors.Cobalt.copy(alpha = 0.22f)
        )
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
            )
        }
    }
}

@Composable
private fun TopStatPill(
    icon: ImageVector,
    iconTint: Color,
    value: String,
    contentDescription: String,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = InplaceXColors.Surface.copy(alpha = 0.94f),
        contentColor = InplaceXColors.Ink,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            InplaceXColors.Cobalt.copy(alpha = 0.22f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun Color.takeOrElse(fallback: Color): Color =
    if (this == Color.Unspecified || this == Color.Transparent) fallback else this
