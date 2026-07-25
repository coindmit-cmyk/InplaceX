package com.mirkori.inplacex.ui.background

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.mirkori.inplacex.ui.theme.InplaceXColors

sealed interface ScreenBackgroundStyle {
    data class Preset(
        val preset: ScreenBackgroundPreset
    ) : ScreenBackgroundStyle

    data class ImageAsset(
        val assetPath: String,
        val fallbackColor: Color
    ) : ScreenBackgroundStyle

    data class SolidColor(
        val color: Color
    ) : ScreenBackgroundStyle
}

enum class ScreenBackgroundPreset {
    SoftSky,
    DefaultBlue,
    DeepBlue,
    Violet,
    Dark
}

@Composable
fun ScreenBackground(
    style: ScreenBackgroundStyle,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush(style))
    ) {
        BackgroundDecor(style)
        content()
    }
}

@Composable
private fun BoxScope.BackgroundDecor(
    style: ScreenBackgroundStyle
) {
    val preset = (style as? ScreenBackgroundStyle.Preset)?.preset ?: return

    when (preset) {
        ScreenBackgroundPreset.SoftSky,
        ScreenBackgroundPreset.DefaultBlue,
        ScreenBackgroundPreset.DeepBlue,
        ScreenBackgroundPreset.Violet,
        ScreenBackgroundPreset.Dark -> Unit
    }
}

private fun backgroundBrush(
    style: ScreenBackgroundStyle
): Brush {
    return when (style) {
        is ScreenBackgroundStyle.Preset -> backgroundBrush(style.preset)
        is ScreenBackgroundStyle.ImageAsset -> Brush.verticalGradient(
            colors = listOf(style.fallbackColor, style.fallbackColor)
        )
        is ScreenBackgroundStyle.SolidColor -> Brush.verticalGradient(
            colors = listOf(style.color, style.color)
        )
    }
}

private fun backgroundBrush(
    preset: ScreenBackgroundPreset
): Brush {
    return when (preset) {
        ScreenBackgroundPreset.SoftSky -> {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFDCEBFF),
                    InplaceXColors.SurfaceMuted,
                    InplaceXColors.Surface
                )
            )
        }

        ScreenBackgroundPreset.DefaultBlue -> {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF7AA7FF),
                    Color(0xFF5F8EF0),
                    Color(0xFF4A73D9)
                )
            )
        }

        ScreenBackgroundPreset.DeepBlue -> {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF4F7BFF),
                    Color(0xFF2E59D9),
                    Color(0xFF183A9E)
                )
            )
        }

        ScreenBackgroundPreset.Violet -> {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF9B8CFF),
                    Color(0xFF6E63E6),
                    Color(0xFF473EB3)
                )
            )
        }

        ScreenBackgroundPreset.Dark -> {
            Brush.verticalGradient(
                colors = listOf(
                    InplaceXColors.NavySurface,
                    InplaceXColors.MidnightElevated,
                    InplaceXColors.Midnight
                )
            )
        }
    }
}
