package com.mirkori.inplacex.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.navigation.AppSection
import com.mirkori.inplacex.ui.navigation.AppSectionCatalog
import com.mirkori.inplacex.ui.navigation.AppSectionIconCatalog
import com.mirkori.inplacex.ui.theme.InplaceXColors

@Composable
fun AppBottomMenu(
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(24.dp),
        color = InplaceXColors.MidnightElevated.copy(alpha = 0.96f),
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            InplaceXColors.Cyan.copy(alpha = 0.18f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppSection.entries.forEach { section ->
                BottomMenuItem(
                    section = section,
                    selected = section == currentSection,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onClick = { onSectionChange(section) }
                )
            }
        }
    }
}

@Composable
private fun BottomMenuItem(
    section: AppSection,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val title = AppSectionCatalog.shortLabel(section, strings)

    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) InplaceXColors.Cobalt.copy(alpha = 0.24f) else Color.Transparent,
        tonalElevation = 0.dp,
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(1.dp, InplaceXColors.Cyan.copy(alpha = 0.72f))
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppSectionIconCatalog.spec(section).fallbackIcon,
                    contentDescription = title,
                    tint = if (selected) InplaceXColors.Cyan else InplaceXColors.SurfaceMuted
                )
            }
            Text(
                text = title,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 11.sp
                ),
                color = if (selected) InplaceXColors.White else InplaceXColors.SurfaceMuted,
                maxLines = 1
            )
        }
    }
}
