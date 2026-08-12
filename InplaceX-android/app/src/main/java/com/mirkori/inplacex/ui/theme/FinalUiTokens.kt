package com.mirkori.inplacex.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Final owner-approved warm-room/polished-casual UI tokens. */
object FinalUiColors {
    val WarmPanelTop = Color(0xFFFFF9EC)
    val WarmPanelBottom = Color(0xFFF6E5C7)
    val WarmPanelSolid = Color(0xFFFFF4DE)
    val WarmBorder = Color(0xFFD8B879)
    val WarmDivider = Color(0xFFB9955F)
    val WarmText = Color(0xFF3B2918)
    val WarmTextMuted = Color(0xFF725A3C)

    val ChromeTop = Color(0xFF365678)
    val Chrome = Color(0xFF223C5A)
    val ChromeDeep = Color(0xFF11263F)
    val ChromeBorder = Color(0xFF72B7EA)
    val ChromeText = Color(0xFFFFF9EC)

    val PrimaryTop = Color(0xFF2C82D8)
    val Primary = Color(0xFF1769B5)
    val PrimaryDeep = Color(0xFF0D4E91)

    val ModeOrangeTop = Color(0xFFF8CA6A)
    val ModeOrange = Color(0xFFEBA62E)
    val ModePurpleTop = Color(0xFF9B73DC)
    val ModePurple = Color(0xFF704BB8)
    val ModeGreenTop = Color(0xFF97C751)
    val ModeGreen = Color(0xFF62962E)

    val StateNo = Color(0xFFE97872)
    val StateMaybe = Color(0xFFE6B83E)
    val StateExact = Color(0xFF79B95D)
    val StatePro = Color(0xFFAEBEC9)
    val LockedNo = Color(0xFFC95D5D)
    val LockedExact = Color(0xFF4C9A45)
    val Disabled = Color(0xFFB9B2A7)
}

object FinalUiDimens {
    val ScreenPadding = 4.dp
    val SectionGap = 4.dp
    val PanelPadding = 8.dp
    val CompactPanelPadding = 6.dp
    val InnerGap = 4.dp
    val MajorGap = 8.dp

    val ChromeRadius = 20.dp
    val PanelRadius = 18.dp
    val TileRadius = 20.dp
    val GroupRadius = 14.dp
    val ButtonRadius = 12.dp
    val AttemptRadius = 8.dp
    val MatrixRadius = 6.dp

    val PanelBorder = 1.dp
    val SelectedBorder = 2.dp
    val PanelElevation = 3.dp
    val TileElevation = 4.dp
    val ChromeElevation = 4.dp
    val MinimumTouchTarget = 44.dp
}

@Immutable
data class GameFieldLayoutMetrics(
    val attemptsWeight: Float,
    val matrixWeight: Float,
    val matrixGap: Dp,
    val matrixRadius: Dp,
    val matrixDigitSize: TextUnit,
    val attemptTextSize: TextUnit,
    val attemptRowHeight: Dp,
    val inputSlotHeight: Dp,
    val inputSlotGap: Dp,
    val keypadHeight: Dp,
    val topPanelMinHeight: Dp,
    val helpersHeight: Dp,
    val toolsHeight: Dp,
)

/**
 * Production modes currently expose 4..10 digits. The visual system is optimized for 4..8 and
 * intentionally keeps a supported ultra-compact fallback for 9..10 instead of changing rules.
 */
fun finalGameFieldMetrics(
    codeLength: Int,
    compactHeight: Boolean,
): GameFieldLayoutMetrics {
    val normalized = codeLength.coerceIn(4, 10)
    val base = when (normalized) {
        4 -> GameFieldLayoutMetrics(
            attemptsWeight = 0.40f,
            matrixWeight = 0.60f,
            matrixGap = 3.dp,
            matrixRadius = 6.dp,
            matrixDigitSize = 12.sp,
            attemptTextSize = 14.sp,
            attemptRowHeight = 30.dp,
            inputSlotHeight = 42.dp,
            inputSlotGap = 6.dp,
            keypadHeight = 38.dp,
            topPanelMinHeight = 62.dp,
            helpersHeight = 44.dp,
            toolsHeight = 40.dp,
        )

        in 5..6 -> GameFieldLayoutMetrics(
            attemptsWeight = 0.37f,
            matrixWeight = 0.63f,
            matrixGap = 2.5.dp,
            matrixRadius = 6.dp,
            matrixDigitSize = 11.5.sp,
            attemptTextSize = 13.5.sp,
            attemptRowHeight = 29.dp,
            inputSlotHeight = 40.dp,
            inputSlotGap = 4.dp,
            keypadHeight = 38.dp,
            topPanelMinHeight = 62.dp,
            helpersHeight = 44.dp,
            toolsHeight = 40.dp,
        )

        in 7..8 -> GameFieldLayoutMetrics(
            attemptsWeight = 0.32f,
            matrixWeight = 0.68f,
            matrixGap = 2.dp,
            matrixRadius = 5.dp,
            matrixDigitSize = 11.sp,
            attemptTextSize = 12.5.sp,
            attemptRowHeight = 28.dp,
            inputSlotHeight = 36.dp,
            inputSlotGap = 3.dp,
            keypadHeight = 36.dp,
            topPanelMinHeight = 60.dp,
            helpersHeight = 42.dp,
            toolsHeight = 38.dp,
        )

        else -> GameFieldLayoutMetrics(
            attemptsWeight = 0.36f,
            matrixWeight = 0.64f,
            matrixGap = 1.dp,
            matrixRadius = 5.dp,
            matrixDigitSize = 9.5.sp,
            attemptTextSize = 10.sp,
            attemptRowHeight = 26.dp,
            inputSlotHeight = 32.dp,
            inputSlotGap = 2.dp,
            keypadHeight = 34.dp,
            topPanelMinHeight = 58.dp,
            helpersHeight = 40.dp,
            toolsHeight = 36.dp,
        )
    }
    return if (!compactHeight) {
        base
    } else {
        base.copy(
            attemptRowHeight = (base.attemptRowHeight - 2.dp).coerceAtLeast(24.dp),
            inputSlotHeight = (base.inputSlotHeight - 2.dp).coerceAtLeast(30.dp),
            keypadHeight = (base.keypadHeight - 2.dp).coerceAtLeast(32.dp),
            topPanelMinHeight = (base.topPanelMinHeight - 4.dp).coerceAtLeast(54.dp),
            helpersHeight = (base.helpersHeight - 2.dp).coerceAtLeast(38.dp),
            toolsHeight = (base.toolsHeight - 2.dp).coerceAtLeast(34.dp),
        )
    }
}
