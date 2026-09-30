package com.mirkori.inplacex.ui.layout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class UiLayoutConfig(
    val shellHorizontalPaddingPercent: Float = 0.010f,
    val shellInnerHorizontalPaddingPercent: Float = 0.012f,
    val shellTopPadding: Dp = 0.dp,
    val topSlotHeightPercent: Float = 0.090f,
    val topSlotBottomGap: Dp = 4.dp,
    val shellBottomGap: Dp = 4.dp,
    val bottomSlotHeightPercent: Float = 0.092f,
    val bottomSlotBottomPadding: Dp = 2.dp,
    val bottomMenuButtonCorner: Dp = 14.dp,
    val bottomMenuButtonGap: Dp = 2.dp
)

object UiLayoutConfigs {
    val Default = UiLayoutConfig()
    val Compact = UiLayoutConfig(
        shellHorizontalPaddingPercent = 0.008f,
        shellInnerHorizontalPaddingPercent = 0.010f,
        topSlotHeightPercent = 0.082f,
        bottomSlotHeightPercent = 0.086f,
        bottomMenuButtonGap = 1.dp
    )
}
