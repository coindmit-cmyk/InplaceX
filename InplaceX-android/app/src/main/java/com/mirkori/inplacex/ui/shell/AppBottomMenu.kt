package com.mirkori.inplacex.ui.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
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
        modifier = modifier
            .fillMaxSize()
            .shadow(10.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(2.dp, InplaceXColors.ToyCyan.copy(alpha = 0.62f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            InplaceXColors.ToyBlue,
                            InplaceXColors.ToyBlueDeep,
                            Color(0xFF062661),
                        )
                    )
                )
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
        modifier = modifier
            .then(
                if (selected) {
                    Modifier.shadow(8.dp, RoundedCornerShape(18.dp))
                } else {
                    Modifier
                }
            )
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .clickable(
                role = Role.Tab,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) InplaceXColors.ToyBlueTop else Color.Transparent,
        tonalElevation = 0.dp,
        border = if (selected) {
            BorderStroke(2.dp, InplaceXColors.ToyCyan)
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
                    tint = if (selected) Color.White else InplaceXColors.ToyCream
                )
            }
            Text(
                text = title,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 11.sp
                ),
                color = if (selected) Color.White else InplaceXColors.ToyCream,
                fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}
