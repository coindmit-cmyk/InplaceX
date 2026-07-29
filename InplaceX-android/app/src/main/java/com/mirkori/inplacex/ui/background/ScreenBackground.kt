package com.mirkori.inplacex.ui.background

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import com.mirkori.inplacex.ui.theme.InplaceXColors

sealed interface ScreenBackgroundStyle {
    data class Preset(
        val preset: ScreenBackgroundPreset
    ) : ScreenBackgroundStyle

    data class ImageAsset(
        val assetPath: String,
        val fallbackColor: Color
    ) : ScreenBackgroundStyle

    data class DrawableResource(
        val resourceId: Int,
        val fallbackColor: Color,
    ) : ScreenBackgroundStyle

    data class SolidColor(
        val color: Color
    ) : ScreenBackgroundStyle
}

enum class ScreenBackgroundPreset {
    WarmWorkshop,
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
        if (style is ScreenBackgroundStyle.DrawableResource) {
            Image(
                painter = painterResource(style.resourceId),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("screen-background-drawable"),
                contentScale = ContentScale.Crop,
            )
        }
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
        ScreenBackgroundPreset.WarmWorkshop -> {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val windowWidth = size.width * 0.62f
                val windowHeight = size.height * 0.30f
                drawRoundRect(
                    color = InplaceXColors.ToyCream.copy(alpha = 0.24f),
                    topLeft = Offset((size.width - windowWidth) / 2f, size.height * 0.06f),
                    size = Size(windowWidth, windowHeight),
                    cornerRadius = CornerRadius(size.width * 0.10f),
                )
                drawCircle(
                    color = Color(0xFFFFE29A).copy(alpha = 0.28f),
                    radius = size.width * 0.36f,
                    center = Offset(size.width * 0.50f, size.height * 0.18f),
                )
                val deskTop = size.height * 0.44f
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            InplaceXColors.ToyWoodTop.copy(alpha = 0.46f),
                            InplaceXColors.ToyWood.copy(alpha = 0.72f),
                        ),
                        startY = deskTop,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, deskTop),
                    size = Size(size.width, size.height - deskTop),
                )
                repeat(8) { index ->
                    val y = deskTop + (size.height - deskTop) * (index + 1) / 9f
                    drawLine(
                        color = Color(0xFF6A2D0D).copy(alpha = 0.12f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y + size.width * 0.025f),
                        strokeWidth = 2f,
                    )
                }
            }
        }
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
        is ScreenBackgroundStyle.DrawableResource -> Brush.verticalGradient(
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
        ScreenBackgroundPreset.WarmWorkshop -> {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF9B4715),
                    Color(0xFFD9822E),
                    Color(0xFFC66B20),
                    Color(0xFF7B330F),
                )
            )
        }
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
