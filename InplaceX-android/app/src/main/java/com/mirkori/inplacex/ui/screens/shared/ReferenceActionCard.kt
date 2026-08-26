package com.mirkori.inplacex.ui.screens.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.ui.theme.PageType

/** Compact illustrated-style navigation tile; callbacks and enabled state belong to the caller. */
@Composable
fun ReferenceActionCard(title: String, subtitle: String, icon: ImageVector, accent: Color,
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 170.dp),
        shape = RoundedCornerShape(18.dp), color = Color.Transparent, contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = .55f)), shadowElevation = 4.dp,
    ) {
        Column(Modifier.background(Brush.linearGradient(listOf(
            Color.White.copy(alpha = .18f).compositeOver(accent), accent,
            Color.Black.copy(alpha = .42f).compositeOver(accent))))
            .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(40.dp), tint = Color(0xFFFFEAC5))
            Text(title, style = PageType.CardTitle)
            Text(subtitle, style = PageType.Secondary, color = Color.White.copy(alpha = if (enabled) .95f else .72f))
        }
    }
}
