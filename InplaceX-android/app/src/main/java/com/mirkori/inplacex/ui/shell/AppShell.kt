package com.mirkori.inplacex.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.config.AppConfigCatalog
import com.mirkori.inplacex.ui.background.ScreenBackground
import com.mirkori.inplacex.ui.background.ScreenBackgroundStyle
import com.mirkori.inplacex.ui.layout.UiLayoutConfig
import com.mirkori.inplacex.ui.layout.UiLayoutConfigs
import com.mirkori.inplacex.ui.navigation.AppSection

@Composable
fun AppShell(
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    bottomMode: BottomLayerMode = BottomLayerMode.MENU,
    topMode: TopLayerMode = TopLayerMode.NONE,
    backgroundStyle: ScreenBackgroundStyle = ScreenBackgroundStyle.SolidColor(Color(0xFF4A73D9)),
    layoutConfig: UiLayoutConfig = UiLayoutConfigs.Default,
    topContent: (@Composable () -> Unit)? = null,
    bottomAdContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        ShellBackground(
            paddingValues = paddingValues,
            currentSection = currentSection,
            onSectionChange = onSectionChange,
            bottomMode = bottomMode,
            topMode = topMode,
            backgroundStyle = backgroundStyle,
            layoutConfig = layoutConfig,
            topContent = topContent,
            bottomAdContent = bottomAdContent,
            content = content
        )
    }
}

@Composable
private fun ShellBackground(
    paddingValues: PaddingValues,
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    bottomMode: BottomLayerMode,
    topMode: TopLayerMode,
    backgroundStyle: ScreenBackgroundStyle,
    layoutConfig: UiLayoutConfig,
    topContent: (@Composable () -> Unit)?,
    bottomAdContent: (@Composable () -> Unit)?,
    content: @Composable () -> Unit
) {
    val navBar = WindowInsets.navigationBars.asPaddingValues()
    val centerSurfaceColor = AppConfigCatalog.platformConfig.shellAppearance.centerSurface.solidColor

    ScreenBackground(
        style = backgroundStyle,
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val screenWidth = maxWidth
            val screenHeight = maxHeight

            val horizontalPadding = screenWidth * layoutConfig.shellHorizontalPaddingPercent
            val innerHorizontalPadding = screenWidth * layoutConfig.shellInnerHorizontalPaddingPercent
            val topSlotHeight = if (topMode == TopLayerMode.NONE || topContent == null) 0.dp
            else screenHeight * layoutConfig.topSlotHeightPercent
            val bottomSlotHeight = if (bottomMode == BottomLayerMode.NONE) 0.dp
            else screenHeight * layoutConfig.bottomSlotHeightPercent

            if (topMode != TopLayerMode.NONE && topContent != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = horizontalPadding)
                        .padding(top = layoutConfig.shellTopPadding)
                        .height(topSlotHeight)
                ) {
                    when (topMode) {
                        TopLayerMode.SURFACE -> {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                tonalElevation = 0.dp,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                            ) {
                                topContent()
                            }
                        }

                        TopLayerMode.OVERLAY -> {
                            Box(modifier = Modifier.fillMaxSize()) {
                                topContent()
                            }
                        }

                        TopLayerMode.NONE -> Unit
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .padding(top = layoutConfig.shellTopPadding + topSlotHeight + if (topSlotHeight > 0.dp) layoutConfig.topSlotBottomGap else 0.dp)
                    .padding(bottom = bottomSlotHeight + if (bottomSlotHeight > 0.dp) layoutConfig.shellBottomGap else 0.dp),
                tonalElevation = 0.dp,
                color = if (centerSurfaceColor != Color.Transparent) {
                    centerSurfaceColor
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = innerHorizontalPadding,
                            vertical = 4.dp
                        )
                ) {
                    content()
                }
            }

            if (bottomMode != BottomLayerMode.NONE) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = horizontalPadding)
                        .padding(bottom = navBar.calculateBottomPadding() + layoutConfig.bottomSlotBottomPadding)
                        .height(bottomSlotHeight)
                ) {
                    AppBottomSlot(
                        currentSection = currentSection,
                        onSectionChange = onSectionChange,
                        bottomMode = bottomMode,
                        adContent = bottomAdContent,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
