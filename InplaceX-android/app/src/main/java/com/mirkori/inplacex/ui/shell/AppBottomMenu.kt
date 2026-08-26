package com.mirkori.inplacex.ui.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.mirkori.inplacex.ui.theme.FinalUiColors
import com.mirkori.inplacex.ui.theme.PageColors
import com.mirkori.inplacex.ui.theme.PageType
import com.mirkori.inplacex.ui.theme.InplaceXColors

@Composable
fun AppBottomMenu(
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    socialNotificationCount: Int = 0,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxSize(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
        border = BorderStroke(1.dp, FinalUiColors.ChromeBorder.copy(alpha = 0.58f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            PageColors.Chrome,
                            PageColors.Chrome,
                            PageColors.ChromeDark,
                        )
                    )
                )
                .padding(horizontal = 5.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppSection.entries.forEach { section ->
                BottomMenuItem(
                    section = section,
                    selected = section == currentSection,
                    notificationCount = if (section == AppSection.SOCIAL) socialNotificationCount else 0,
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
    notificationCount: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val title = AppSectionCatalog.shortLabel(section, strings)

    Surface(
        modifier = modifier
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .clickable(
                role = Role.Tab,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) PageColors.Primary else Color.Transparent,
        tonalElevation = 0.dp,
        border = if (selected) {
            BorderStroke(1.dp, FinalUiColors.ChromeBorder)
        } else {
            null
        }
    ) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val navigationStyle = if (maxWidth < 72.dp && LocalDensity.current.fontScale > 1.2f)
            PageType.Navigation.copy(fontSize = 9.sp) else PageType.Navigation
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp),
                contentAlignment = Alignment.Center
            ) {
                BadgedBox(
                    badge = {
                        if (notificationCount > 0) {
                            Badge { Text(notificationCount.coerceAtMost(99).toString()) }
                        }
                    },
                ) {
                    Icon(
                        imageVector = AppSectionIconCatalog.spec(section).fallbackIcon,
                        contentDescription = title,
                        tint = if (selected) Color.White else InplaceXColors.ToyCream,
                    )
                }
            }
            Text(
                text = title,
                textAlign = TextAlign.Center,
                style = navigationStyle,
                color = if (selected) Color.White else InplaceXColors.ToyCream,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
      }
    }
}
