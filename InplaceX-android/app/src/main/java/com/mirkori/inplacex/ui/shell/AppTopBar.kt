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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.config.AppConfigCatalog
import com.mirkori.inplacex.platform.localization.LocalAppStrings

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
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = strings.text("top.back"),
                    tint = appearance.backIcon.tintColor.takeOrElse(MaterialTheme.colorScheme.onSurface)
                )
            }
        } else {
            Box(modifier = Modifier.size(40.dp))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatChip(
                icon = Icons.Outlined.Bolt,
                iconTint = appearance.energyIcon.tintColor.takeOrElse(Color(0xFF4C6FFF)),
                value = energy.toString(),
                contentDescription = strings.text("top.energy")
            )
            StatChip(
                icon = Icons.Outlined.MonetizationOn,
                iconTint = appearance.coinsIcon.tintColor.takeOrElse(Color(0xFFCC8A00)),
                value = coins.toString(),
                contentDescription = strings.text("top.coins")
            )
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = strings.text("top.settings"),
                    tint = appearance.settingsIcon.tintColor.takeOrElse(MaterialTheme.colorScheme.onSurface)
                )
            }
        }
    }
}

@Composable
private fun StatChip(
    icon: ImageVector,
    iconTint: Color,
    value: String,
    contentDescription: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

private fun Color.takeOrElse(fallback: Color): Color = if (this == Color.Unspecified || this == Color.Transparent) fallback else this
