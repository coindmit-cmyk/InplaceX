package com.mirkori.inplacex.ui.screens.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirkori.inplacex.ui.theme.FinalUiColors
import com.mirkori.inplacex.ui.theme.InplaceXColors

@Composable
fun SceneBackdrop(
    modifier: Modifier = Modifier,
    topColor: Color = InplaceXColors.NavySurface,
    bottomColor: Color = InplaceXColors.ToyWood,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(topColor, bottomColor)
                )
            )
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 28.dp, end = 22.dp)
                .size(124.dp)
                .clip(CircleShape)
                .background(InplaceXColors.ToyCream.copy(alpha = 0.12f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp, bottom = 32.dp)
                .size(156.dp)
                .clip(CircleShape)
                .background(InplaceXColors.ToyOrange.copy(alpha = 0.14f))
        )
        content()
    }
}

@Composable
fun ScenePageColumn(
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    verticalSpacing: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollModifier = if (scrollable) {
        Modifier.verticalScroll(rememberScrollState())
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(scrollModifier)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = content
    )
}

@Composable
fun SceneCard(
    modifier: Modifier = Modifier,
    accentColor: Color = InplaceXColors.ToyCream.copy(alpha = 0.97f),
    contentColor: Color = InplaceXColors.ToyBrown,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = accentColor,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 3.dp,
        border = BorderStroke(1.dp, InplaceXColors.ToyCreamShadow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            content()
        }
    }
}

@Composable
fun SceneBadge(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = InplaceXColors.ToyCream,
        contentColor = InplaceXColors.ToyBrown,
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, InplaceXColors.ToyCreamShadow),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = InplaceXColors.ToyBrown.copy(alpha = 0.72f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SceneActionTile(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLineTitle: Boolean = false,
    compact: Boolean = false,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    stateDescription: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    contentColor: Color = Color.White,
    accentBrush: Brush = Brush.verticalGradient(
        listOf(FinalUiColors.PrimaryTop, FinalUiColors.PrimaryDeep)
    ),
    onClick: () -> Unit,
) {
    val tileShape = RoundedCornerShape(20.dp)
    val tileMinHeight = if (compact) 94.dp else 104.dp
    val tileHorizontalPadding = if (compact) 13.dp else 16.dp
    val tileVerticalPadding = if (compact) 11.dp else 14.dp
    val tileSpacing = if (compact) 10.dp else 12.dp
    val leadingSize = if (compact) 50.dp else 54.dp
    val leadingShape = RoundedCornerShape(14.dp)
    val leadingIconSize = if (compact) 27.dp else 30.dp
    val trailingSize = if (compact) 40.dp else 44.dp
    val trailingIconSize = if (compact) 23.dp else 26.dp
    val semanticsModifier = Modifier.semantics {
        role = Role.Button
        stateDescription?.let { this.stateDescription = it }
    }
    Surface(
        modifier = modifier
            .heightIn(min = tileMinHeight)
            .then(semanticsModifier)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = tileShape,
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.42f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(accentBrush)
                .padding(horizontal = tileHorizontalPadding, vertical = tileVerticalPadding),
            horizontalArrangement = Arrangement.spacedBy(tileSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.let { icon ->
                Surface(
                    modifier = Modifier.size(leadingSize),
                    shape = leadingShape,
                    color = Color.White.copy(alpha = 0.17f),
                    contentColor = contentColor,
                    border = BorderStroke(1.dp, contentColor.copy(alpha = 0.24f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(leadingIconSize),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    fontSize = if (compact) 18.sp else 20.sp,
                    lineHeight = if (compact) 21.sp else 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = if (singleLineTitle) 1 else 2,
                    softWrap = !singleLineTitle,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    fontSize = if (compact) 12.5.sp else 14.sp,
                    lineHeight = if (compact) 16.sp else 19.sp,
                    fontWeight = FontWeight.Medium,
                    color = contentColor.copy(alpha = if (enabled) 0.90f else 0.62f),
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailingIcon?.let { icon ->
                Surface(
                    modifier = Modifier.size(trailingSize),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.16f),
                    contentColor = contentColor,
                    border = BorderStroke(1.dp, contentColor.copy(alpha = 0.34f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(trailingIconSize),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SceneSplitStatRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SceneBadge(
            label = leftLabel,
            value = leftValue,
            modifier = Modifier.weight(1f)
        )
        SceneBadge(
            label = rightLabel,
            value = rightValue,
            modifier = Modifier.weight(1f)
        )
    }
}
