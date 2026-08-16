package com.mirkori.inplacex.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Final owner-approved warm-room/polished-casual UI tokens. */
object FinalUiColors {
    val WarmPanelTop = Color(0xFFFFFAF0)
    val WarmPanelBottom = Color(0xFFF7E8CD)
    val WarmPanelSolid = Color(0xFFFFF6E4)
    val WarmBorder = Color(0xFFCFA45F)
    val WarmDivider = Color(0xFFB78B50)
    val WarmText = Color(0xFF342417)
    val WarmTextMuted = Color(0xFF6D563B)

    val ChromeTop = Color(0xFF365678)
    val Chrome = Color(0xFF223C5A)
    val ChromeDeep = Color(0xFF11263F)
    val ChromeBorder = Color(0xFF72B7EA)
    val ChromeText = Color(0xFFFFF9EC)

    val PrimaryTop = Color(0xFF2C82D8)
    val Primary = Color(0xFF1769B5)
    val PrimaryDeep = Color(0xFF0D4E91)

    val AttemptTop = Color(0xFFE7F4FF)
    val AttemptBottom = Color(0xFFC9E4F8)
    val AttemptIdleTop = Color(0xFFFFF7E6)
    val AttemptIdleBottom = Color(0xFFF6E2BC)
    val InputTop = Color(0xFFFFEDBF)
    val InputBottom = Color(0xFFF3D18C)
    val KeyTop = Color(0xFFFFF8E8)
    val KeyBottom = Color(0xFFF4DBAC)

    val ModeOrangeTop = Color(0xFFF8CA6A)
    val ModeOrange = Color(0xFFEBA62E)
    val ModeOrangeDeep = Color(0xFFD98A18)
    val ModeOrangeText = Color(0xFF714000)
    val ModePurpleTop = Color(0xFF9B73DC)
    val ModePurple = Color(0xFF704BB8)
    val ModePurpleDeep = Color(0xFF56349B)
    val ModeGreenTop = Color(0xFF97C751)
    val ModeGreen = Color(0xFF62962E)
    val ModeGreenDeep = Color(0xFF477A20)

    val StateNo = Color(0xFFE45F5A)
    val StateMaybe = Color(0xFFF0B62F)
    val StateExact = Color(0xFF63AD43)
    val StatePro = Color(0xFFAEBEC9)
    val LockedNo = Color(0xFFC95D5D)
    val LockedExact = Color(0xFF4C9A45)
    val Disabled = Color(0xFFB9B2A7)
}

object FinalUiDimens {
    val ScreenPadding = 4.dp
    val SectionGap = 4.dp
    val PanelPadding = 7.dp
    val CompactPanelPadding = 5.dp
    val InnerGap = 4.dp
    val MajorGap = 8.dp

    val ChromeRadius = 16.dp
    val PanelRadius = 14.dp
    val TileRadius = 20.dp
    val GroupRadius = 12.dp
    val ButtonRadius = 10.dp
    val AttemptRadius = 7.dp
    val MatrixRadius = 5.dp

    val PanelBorder = 1.dp
    val SelectedBorder = 2.dp
    val PanelElevation = 2.dp
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
    val matrixCellHeight: Dp,
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
            matrixGap = 2.dp,
            matrixRadius = 5.dp,
            matrixDigitSize = 11.sp,
            matrixCellHeight = 26.dp,
            attemptTextSize = 13.sp,
            attemptRowHeight = 28.dp,
            inputSlotHeight = 34.dp,
            inputSlotGap = 5.dp,
            keypadHeight = 32.dp,
            topPanelMinHeight = 56.dp,
            helpersHeight = 40.dp,
            toolsHeight = 36.dp,
        )

        in 5..6 -> GameFieldLayoutMetrics(
            attemptsWeight = 0.37f,
            matrixWeight = 0.63f,
            matrixGap = 2.dp,
            matrixRadius = 5.dp,
            matrixDigitSize = 10.5.sp,
            matrixCellHeight = 24.dp,
            attemptTextSize = 12.5.sp,
            attemptRowHeight = 27.dp,
            inputSlotHeight = 32.dp,
            inputSlotGap = 4.dp,
            keypadHeight = 31.dp,
            topPanelMinHeight = 56.dp,
            helpersHeight = 40.dp,
            toolsHeight = 36.dp,
        )

        in 7..8 -> GameFieldLayoutMetrics(
            attemptsWeight = 0.32f,
            matrixWeight = 0.68f,
            matrixGap = 1.5.dp,
            matrixRadius = 4.dp,
            matrixDigitSize = 10.sp,
            matrixCellHeight = 21.dp,
            attemptTextSize = 11.5.sp,
            attemptRowHeight = 26.dp,
            inputSlotHeight = 30.dp,
            inputSlotGap = 3.dp,
            keypadHeight = 30.dp,
            topPanelMinHeight = 54.dp,
            helpersHeight = 38.dp,
            toolsHeight = 35.dp,
        )

        else -> GameFieldLayoutMetrics(
            attemptsWeight = 0.36f,
            matrixWeight = 0.64f,
            matrixGap = 1.dp,
            matrixRadius = 4.dp,
            matrixDigitSize = 9.sp,
            matrixCellHeight = 19.dp,
            attemptTextSize = 9.5.sp,
            attemptRowHeight = 24.dp,
            inputSlotHeight = 28.dp,
            inputSlotGap = 2.dp,
            keypadHeight = 28.dp,
            topPanelMinHeight = 52.dp,
            helpersHeight = 36.dp,
            toolsHeight = 34.dp,
        )
    }
    return if (!compactHeight) {
        base
    } else {
        base.copy(
            matrixCellHeight = (base.matrixCellHeight - 2.dp).coerceAtLeast(16.dp),
            attemptRowHeight = (base.attemptRowHeight - 2.dp).coerceAtLeast(22.dp),
            inputSlotHeight = (base.inputSlotHeight - 2.dp).coerceAtLeast(26.dp),
            keypadHeight = (base.keypadHeight - 2.dp).coerceAtLeast(26.dp),
            topPanelMinHeight = (base.topPanelMinHeight - 4.dp).coerceAtLeast(48.dp),
            helpersHeight = (base.helpersHeight - 2.dp).coerceAtLeast(34.dp),
            toolsHeight = (base.toolsHeight - 2.dp).coerceAtLeast(32.dp),
        )
    }
}
