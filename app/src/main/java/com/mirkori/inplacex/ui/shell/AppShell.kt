package com.mirkori.inplacex.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mirkori.inplacex.ui.background.ScreenBackground
import com.mirkori.inplacex.ui.background.ScreenBackgroundPreset
import com.mirkori.inplacex.ui.layout.UiLayoutConfig
import com.mirkori.inplacex.ui.layout.UiLayoutConfigs
import com.mirkori.inplacex.ui.navigation.AppSection
import androidx.compose.ui.unit.dp

@Composable
fun AppShell(
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    isInGame: Boolean = false,
    isPremium: Boolean = false,
    backgroundPreset: ScreenBackgroundPreset = ScreenBackgroundPreset.DefaultBlue,
    layoutConfig: UiLayoutConfig = UiLayoutConfigs.Default,
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
            isInGame = isInGame,
            isPremium = isPremium,
            backgroundPreset = backgroundPreset,
            layoutConfig = layoutConfig,
            content = content
        )
    }
}

@Composable
private fun ShellBackground(
    paddingValues: PaddingValues,
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    isInGame: Boolean,
    isPremium: Boolean,
    backgroundPreset: ScreenBackgroundPreset,
    layoutConfig: UiLayoutConfig,
    content: @Composable () -> Unit
) {
    val navBar = WindowInsets.navigationBars.asPaddingValues()

    ScreenBackground(
        preset = backgroundPreset,
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
            val bottomSlotHeight = screenHeight * layoutConfig.bottomSlotHeightPercent

            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .padding(top = layoutConfig.shellTopPadding)
                    .padding(bottom = bottomSlotHeight + layoutConfig.shellBottomGap),
                tonalElevation = 0.dp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
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
                    isInGame = isInGame,
                    isPremium = isPremium,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
