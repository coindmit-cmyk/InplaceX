package com.mirkori.inplacex.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val InplaceXDarkColorScheme = darkColorScheme(
    primary = InplaceXColors.Cyan,
    onPrimary = InplaceXColors.Midnight,
    primaryContainer = InplaceXColors.Cobalt,
    onPrimaryContainer = InplaceXColors.White,
    secondary = InplaceXColors.Indigo,
    onSecondary = InplaceXColors.White,
    tertiary = InplaceXColors.Amber,
    onTertiary = InplaceXColors.Midnight,
    background = InplaceXColors.Midnight,
    onBackground = InplaceXColors.White,
    surface = InplaceXColors.MidnightElevated,
    onSurface = InplaceXColors.White,
    surfaceVariant = InplaceXColors.NavySurface,
    onSurfaceVariant = InplaceXColors.SurfaceMuted,
    outline = InplaceXColors.Outline.copy(alpha = 0.64f),
    error = InplaceXColors.Coral,
)

private val InplaceXLightColorScheme = lightColorScheme(
    primary = InplaceXColors.ToyBlue,
    onPrimary = InplaceXColors.White,
    primaryContainer = InplaceXColors.ToyBlueTop,
    onPrimaryContainer = InplaceXColors.White,
    secondary = InplaceXColors.ToyPurple,
    onSecondary = InplaceXColors.White,
    tertiary = InplaceXColors.ToyOrange,
    onTertiary = InplaceXColors.ToyBrown,
    background = InplaceXColors.ToyWood,
    onBackground = InplaceXColors.ToyCream,
    surface = InplaceXColors.ToyCream,
    onSurface = InplaceXColors.ToyBrown,
    surfaceVariant = InplaceXColors.ToyCreamShadow.copy(alpha = 0.72f),
    onSurfaceVariant = InplaceXColors.ToyBrown.copy(alpha = 0.74f),
    outline = InplaceXColors.ToyCreamShadow,
    error = InplaceXColors.ToyRed,
)

private val InplaceXShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun InplaceXTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) InplaceXDarkColorScheme else InplaceXLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = InplaceXShapes,
        content = content
    )
}
