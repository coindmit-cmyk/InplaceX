package com.mirkori.inplacex.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.ui.theme.FinalUiColors
import com.mirkori.inplacex.ui.theme.FinalUiDimens

@Composable
fun WarmPanel(
    modifier: Modifier = Modifier,
    shapeRadius: Dp = FinalUiDimens.PanelRadius,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    accent: Color? = null,
    accentStrength: Float = 0.08f,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(shapeRadius)
    val panelTop = accent?.tintOver(FinalUiColors.WarmPanelTop, accentStrength)
        ?: FinalUiColors.WarmPanelTop
    val panelMiddle = accent?.tintOver(FinalUiColors.WarmPanelSolid, accentStrength * 0.58f)
        ?: FinalUiColors.WarmPanelSolid
    val panelBottom = accent?.tintOver(FinalUiColors.WarmPanelBottom, accentStrength * 0.72f)
        ?: FinalUiColors.WarmPanelBottom
    val panelBorder = accent?.tintOver(FinalUiColors.WarmBorder, 0.38f)
        ?: FinalUiColors.WarmBorder
    Surface(
        modifier = modifier,
        shape = shape,
        color = Color.Transparent,
        contentColor = FinalUiColors.WarmText,
        border = BorderStroke(FinalUiDimens.PanelBorder, panelBorder),
        shadowElevation = FinalUiDimens.PanelElevation,
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(panelTop, panelMiddle, panelBottom),
                    ),
                )
                .padding(contentPadding),
        ) {
            content()
        }
    }
}

@Composable
fun CompactAttemptRow(
    guess: String,
    score: Int,
    latest: Boolean,
    contentDescription: String,
    textSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(FinalUiDimens.AttemptRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (latest) {
                        listOf(FinalUiColors.AttemptTop, FinalUiColors.AttemptBottom)
                    } else {
                        listOf(FinalUiColors.AttemptIdleTop, FinalUiColors.AttemptIdleBottom)
                    },
                ),
            )
            .border(
                width = 1.dp,
                color = if (latest) {
                    FinalUiColors.Primary
                } else {
                    FinalUiColors.WarmDivider.copy(alpha = 0.48f)
                },
                shape = shape,
            )
            .semantics { this.contentDescription = contentDescription }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = guess,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            fontSize = textSize,
            fontWeight = if (latest) FontWeight.SemiBold else FontWeight.Medium,
            color = FinalUiColors.WarmText,
            maxLines = 1,
        )
        Text(
            text = "→",
            modifier = Modifier.padding(horizontal = 4.dp),
            fontSize = textSize,
            color = FinalUiColors.WarmTextMuted,
        )
        Text(
            text = score.toString(),
            fontFamily = FontFamily.Monospace,
            fontSize = textSize,
            fontWeight = FontWeight.Bold,
            color = FinalUiColors.WarmText,
        )
    }
}

enum class AnalysisCellVisualState {
    EMPTY,
    NO,
    MAYBE,
    EXACT,
    LOCKED_NO,
    LOCKED_EXACT,
    DISABLED,
}

@Composable
fun WarmAnalysisCell(
    digit: Char,
    state: AnalysisCellVisualState,
    stateDescription: String,
    enabled: Boolean,
    contentDescription: String,
    digitSize: TextUnit,
    radius: Dp,
    preserveSquare: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visual = analysisCellVisual(state)
    val shape = RoundedCornerShape(radius)
    val sizedModifier = if (preserveSquare) modifier.aspectRatio(1f) else modifier
    Box(
        modifier = sizedModifier
            .clip(shape)
            .background(visual.fill)
            .border(visual.borderWidth, visual.border, shape)
            .semantics {
                role = Role.Button
                this.stateDescription = stateDescription
                this.contentDescription = contentDescription
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (state == AnalysisCellVisualState.NO || state == AnalysisCellVisualState.LOCKED_NO) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawLine(
                    color = visual.border.copy(alpha = 0.58f),
                    start = androidx.compose.ui.geometry.Offset(size.width * 0.22f, size.height * 0.78f),
                    end = androidx.compose.ui.geometry.Offset(size.width * 0.78f, size.height * 0.22f),
                    strokeWidth = 1.2.dp.toPx(),
                )
            }
        }
        Text(
            text = digit.toString(),
            fontSize = digitSize,
            fontWeight = visual.weight,
            color = visual.text,
            maxLines = 1,
        )
    }
}

@Composable
fun WarmSegmentButton(
    label: String,
    selected: Boolean,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 30.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (selected) {
                        listOf(
                            accent.tintOver(FinalUiColors.WarmPanelTop, 0.34f),
                            accent.tintOver(FinalUiColors.WarmPanelBottom, 0.58f),
                        )
                    } else {
                        listOf(
                            accent.tintOver(FinalUiColors.WarmPanelTop, 0.08f),
                            accent.tintOver(FinalUiColors.WarmPanelBottom, 0.14f),
                        )
                    },
                ),
            )
            .border(
                if (selected) FinalUiDimens.SelectedBorder else FinalUiDimens.PanelBorder,
                if (selected) accent else accent.tintOver(FinalUiColors.WarmBorder, 0.20f),
                shape,
            )
            .semantics { this.selected = selected }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = FinalUiColors.WarmText,
            maxLines = 1,
        )
    }
}

@Composable
fun WarmPrimaryButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(FinalUiDimens.ButtonRadius)
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = FinalUiDimens.MinimumTouchTarget)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (enabled) {
                        listOf(FinalUiColors.PrimaryTop, FinalUiColors.Primary, FinalUiColors.PrimaryDeep)
                    } else {
                        listOf(
                            FinalUiColors.Disabled.copy(alpha = 0.72f),
                            FinalUiColors.Disabled.copy(alpha = 0.58f),
                        )
                    },
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.30f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.82f),
            maxLines = 1,
        )
    }
}

@Composable
fun WarmSecondaryButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(FinalUiDimens.ButtonRadius)
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = FinalUiDimens.MinimumTouchTarget)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        FinalUiColors.KeyTop.copy(alpha = if (enabled) 1f else 0.68f),
                        FinalUiColors.KeyBottom.copy(alpha = if (enabled) 1f else 0.62f),
                    ),
                ),
            )
            .border(1.dp, FinalUiColors.WarmBorder, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = FinalUiColors.WarmText.copy(alpha = if (enabled) 1f else 0.64f),
            maxLines = 1,
        )
    }
}

private data class AnalysisCellVisual(
    val fill: Color,
    val border: Color,
    val borderWidth: Dp,
    val text: Color,
    val weight: FontWeight,
)

private fun analysisCellVisual(state: AnalysisCellVisualState): AnalysisCellVisual = when (state) {
    AnalysisCellVisualState.EMPTY -> AnalysisCellVisual(
        fill = FinalUiColors.InputTop,
        border = FinalUiColors.WarmBorder.copy(alpha = 0.74f),
        borderWidth = 1.dp,
        text = FinalUiColors.WarmText,
        weight = FontWeight.Medium,
    )
    AnalysisCellVisualState.NO -> AnalysisCellVisual(
        fill = FinalUiColors.StateNo.copy(alpha = 0.24f),
        border = FinalUiColors.StateNo,
        borderWidth = 1.dp,
        text = FinalUiColors.WarmTextMuted,
        weight = FontWeight.Medium,
    )
    AnalysisCellVisualState.MAYBE -> AnalysisCellVisual(
        fill = FinalUiColors.StateMaybe.copy(alpha = 0.34f),
        border = FinalUiColors.StateMaybe,
        borderWidth = 1.dp,
        text = FinalUiColors.WarmText,
        weight = FontWeight.Medium,
    )
    AnalysisCellVisualState.EXACT -> AnalysisCellVisual(
        fill = FinalUiColors.StateExact.copy(alpha = 0.42f),
        border = FinalUiColors.StateExact,
        borderWidth = 2.dp,
        text = FinalUiColors.WarmText,
        weight = FontWeight.Bold,
    )
    AnalysisCellVisualState.LOCKED_NO -> AnalysisCellVisual(
        fill = FinalUiColors.LockedNo.copy(alpha = 0.30f),
        border = FinalUiColors.LockedNo,
        borderWidth = 2.dp,
        text = FinalUiColors.LockedNo,
        weight = FontWeight.Bold,
    )
    AnalysisCellVisualState.LOCKED_EXACT -> AnalysisCellVisual(
        fill = FinalUiColors.LockedExact.copy(alpha = 0.82f),
        border = FinalUiColors.LockedExact,
        borderWidth = 2.dp,
        text = Color.White,
        weight = FontWeight.Bold,
    )
    AnalysisCellVisualState.DISABLED -> AnalysisCellVisual(
        fill = FinalUiColors.Disabled.copy(alpha = 0.22f),
        border = FinalUiColors.Disabled.copy(alpha = 0.62f),
        borderWidth = 1.dp,
        text = FinalUiColors.WarmTextMuted.copy(alpha = 0.55f),
        weight = FontWeight.Medium,
    )
}

private fun Color.tintOver(base: Color, alpha: Float): Color =
    copy(alpha = alpha.coerceIn(0f, 1f)).compositeOver(base)
