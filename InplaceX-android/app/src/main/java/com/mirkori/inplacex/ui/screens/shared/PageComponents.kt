package com.mirkori.inplacex.ui.screens.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.ui.theme.PageColors
import com.mirkori.inplacex.ui.theme.PageDimens
import com.mirkori.inplacex.ui.theme.PageType

@Composable
fun ContentCard(
    modifier: Modifier = Modifier,
    color: Color = PageColors.Cream,
    contentColor: Color = PageColors.Text,
    radius: Dp = PageDimens.CardRadius,
    content: @Composable ColumnScope.() -> Unit,
) {
    val opaqueColor = color.compositeOver(PageColors.Cream)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(radius),
        color = opaqueColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, PageColors.Border),
        shadowElevation = PageDimens.Elevation,
    ) {
        ProvideTextStyle(PageType.Body) {
            Column(
                modifier = Modifier.background(
                    Brush.verticalGradient(listOf(opaqueColor, PageColors.CreamSecondary.copy(alpha = .28f).compositeOver(opaqueColor))),
                ).drawWithContent {
                    drawContent()
                    drawLine(Color.White.copy(alpha = .65f), Offset(16.dp.toPx(), 2.dp.toPx()),
                        Offset(size.width - 16.dp.toPx(), 2.dp.toPx()), 1.dp.toPx())
                }.padding(PageDimens.Margin),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

@Composable
fun PageHeroCard(
    title: String,
    subtitle: String,
    accent: Color,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(PageDimens.HeroRadius),
        color = Color.Transparent, contentColor = Color.White,
        border = BorderStroke(1.dp, Color(0xFF69B7E2)), shadowElevation = 4.dp,
    ) {
      Column(Modifier.background(Brush.linearGradient(listOf(accent, PageColors.ChromeDark)))
          .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) leading() else if (icon != null) {
                Surface(shape = RoundedCornerShape(PageDimens.InnerRadius), color = Color.White.copy(alpha = .12f), contentColor = Color(0xFFFFD16D)) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, modifier = titleModifier.semantics { heading() }, style = PageType.Title)
                if (subtitle.isNotBlank()) Text(subtitle, style = PageType.Secondary, color = Color.White.copy(alpha = .94f))
            }
        }
        content()
      }
    }
}

@Composable
fun PageSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(title, color = Color.White, style = PageType.CardTitle,
        modifier = modifier.padding(vertical = 2.dp).semantics { heading() })
}

@Composable
fun PrimaryActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = SceneActionTile(
    title = title, subtitle = subtitle, leadingIcon = icon,
    trailingIcon = Icons.Outlined.ChevronRight, modifier = modifier,
    enabled = enabled, accentBrush = Brush.verticalGradient(listOf(
        Color.White.copy(alpha = .13f).compositeOver(accent), accent,
        Color.Black.copy(alpha = .38f).compositeOver(accent))),
    onClick = onClick,
)

@Composable
fun StatusCard(
    title: String,
    message: String? = null,
    accent: Color = PageColors.Profile,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    ContentCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, contentDescription = null, tint = accent) }
            Text(title, style = PageType.CardTitle, modifier = Modifier.weight(1f))
        }
        message?.let { Text(it, style = PageType.Body, color = PageColors.TextSecondary) }
        content()
    }
}

@Composable
fun PageStatusPill(label: String, accent: Color = PageColors.Profile, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier, shape = RoundedCornerShape(PageDimens.PillRadius),
        color = accent.copy(alpha = .10f).compositeOver(PageColors.Cream),
        contentColor = PageColors.Text,
        border = BorderStroke(1.dp, accent.copy(alpha = .45f)),
    ) {
        Text(label, style = PageType.Secondary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    ContentCard(modifier.heightIn(min = 84.dp)) {
        Text(label, style = PageType.Secondary, color = PageColors.TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, style = PageType.Title.copy(fontSize = 24.sp))
    }
}

@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(PageDimens.InnerRadius),
        color = PageColors.Cream, border = BorderStroke(1.dp, PageColors.Border),
    ) {
        Row(Modifier.padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed { index, label ->
                val selected = selectedIndex == index
                Surface(
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f).heightIn(min = PageDimens.TouchTarget)
                        .semantics { this.selected = selected; role = Role.Tab },
                    shape = RoundedCornerShape(PageDimens.PillRadius),
                    color = if (selected) accent else PageColors.Cream,
                    contentColor = if (selected && accent != PageColors.Shop && accent != PageColors.Company) Color.White else PageColors.Text,
                ) {
                    Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) { Text(label, style = PageType.Button) }
                }
            }
        }
    }
}

@Composable
fun StickyActionBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ContentCard(modifier, content = content)
}

@Composable
fun PagePrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = PageColors.Primary,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = PageDimens.TouchTarget),
        shape = RoundedCornerShape(PageDimens.ButtonRadius), color = Color.Transparent,
        contentColor = if (enabled) Color.White else PageColors.TextSecondary,
        border = BorderStroke(1.dp, if (enabled) Color.White.copy(alpha = .55f) else PageColors.Border),
        shadowElevation = if (enabled) 2.dp else 0.dp,
    ) {
        Row(Modifier.background(Brush.verticalGradient(if (enabled) listOf(
            Color.White.copy(alpha = .18f).compositeOver(accent), accent,
            Color.Black.copy(alpha = .25f).compositeOver(accent))
            else listOf(PageColors.Cream, PageColors.CreamSecondary)))
            .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) { ProvideTextStyle(PageType.Button) { content() } }
    }
}

@Composable
fun PageSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = PageDimens.TouchTarget),
        shape = RoundedCornerShape(PageDimens.ButtonRadius),
        border = BorderStroke(1.dp, PageColors.Border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = PageColors.Cream, contentColor = PageColors.Primary,
            disabledContainerColor = PageColors.CreamSecondary, disabledContentColor = PageColors.TextSecondary,
        ),
    ) { ProvideTextStyle(PageType.Button) { content() } }
}
