package com.mirkori.inplacex.platform.config

import androidx.compose.ui.graphics.Color
import com.mirkori.inplacex.ui.background.ScreenBackgroundPreset

data class ShellAppearanceConfig(
    val appBackground: LayerBackgroundConfig,
    val topBar: TopBarAppearanceConfig,
    val centerSurface: LayerBackgroundConfig,
    val bottomBar: LayerBackgroundConfig,
)

data class TopBarAppearanceConfig(
    val container: LayerBackgroundConfig,
    val backIcon: IconAssetConfig,
    val energyIcon: IconAssetConfig,
    val coinsIcon: IconAssetConfig,
    val settingsIcon: IconAssetConfig,
)

data class LayerBackgroundConfig(
    val useOwnBackground: Boolean,
    val imageAssetPath: String? = null,
    val preset: ScreenBackgroundPreset? = null,
    val solidColor: Color = Color.Transparent,
)

data class IconAssetConfig(
    val imageAssetPath: String? = null,
    val tintColor: Color = Color.Unspecified,
)
