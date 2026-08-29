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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.config.AppConfigCatalog
import com.mirkori.inplacex.R
import com.mirkori.inplacex.ui.screens.social.FriendsReferenceBottomBar
import com.mirkori.inplacex.ui.screens.social.friendsReferenceHudHeight
import com.mirkori.inplacex.ui.background.ScreenBackground
import com.mirkori.inplacex.ui.background.ScreenBackgroundStyle
import com.mirkori.inplacex.ui.layout.UiLayoutConfig
import com.mirkori.inplacex.ui.layout.UiLayoutConfigs
import com.mirkori.inplacex.ui.navigation.AppSection
import com.mirkori.inplacex.ui.theme.InplaceXColors

@Composable
fun AppShell(
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    socialNotificationCount: Int = 0,
    bottomMode: BottomLayerMode = BottomLayerMode.MENU,
    topMode: TopLayerMode = TopLayerMode.NONE,
    centerMode: CenterLayerMode = CenterLayerMode.SURFACE,
    backgroundStyle: ScreenBackgroundStyle = ScreenBackgroundStyle.SolidColor(Color(0xFF4A73D9)),
    layoutConfig: UiLayoutConfig = UiLayoutConfigs.Default,
    topContent: (@Composable () -> Unit)? = null,
    bottomAdContent: (@Composable () -> Unit)? = null,
    illustratedReference: Boolean = false,
    content: @Composable () -> Unit
) {
    Scaffold(
        containerColor = InplaceXColors.Midnight,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        ShellBackground(
            paddingValues = paddingValues,
            currentSection = currentSection,
            onSectionChange = onSectionChange,
            socialNotificationCount = socialNotificationCount,
            bottomMode = bottomMode,
            topMode = topMode,
            centerMode = centerMode,
            backgroundStyle = backgroundStyle,
            layoutConfig = layoutConfig,
            topContent = topContent,
            bottomAdContent = bottomAdContent,
            illustratedReference = illustratedReference,
            content = content
        )
    }
}

@Composable
private fun ShellBackground(
    paddingValues: PaddingValues,
    currentSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
    socialNotificationCount: Int,
    bottomMode: BottomLayerMode,
    topMode: TopLayerMode,
    centerMode: CenterLayerMode,
    backgroundStyle: ScreenBackgroundStyle,
    layoutConfig: UiLayoutConfig,
    topContent: (@Composable () -> Unit)?,
    bottomAdContent: (@Composable () -> Unit)?,
    illustratedReference: Boolean,
    content: @Composable () -> Unit
) {
    val navBar = WindowInsets.navigationBars.asPaddingValues()
    val centerSurfaceColor = AppConfigCatalog.platformConfig.shellAppearance.centerSurface.solidColor
    val bottomSlotBottomPadding = if (illustratedReference) maxOf(navBar.calculateBottomPadding(), 14.dp) else when (bottomMode) {
        BottomLayerMode.MENU -> 0.dp
        BottomLayerMode.AD -> navBar.calculateBottomPadding() + layoutConfig.bottomSlotBottomPadding
        BottomLayerMode.AD_LOADING -> 0.dp
        BottomLayerMode.NONE -> 0.dp
    }

    ScreenBackground(
        style = if (illustratedReference) ScreenBackgroundStyle.DrawableResource(R.drawable.friends_room_v8, InplaceXColors.ToyWood) else backgroundStyle,
        modifier = Modifier
            .fillMaxSize()
            .then(if (illustratedReference) Modifier.testTag("friends-reference-shell") else Modifier)
            .padding(paddingValues)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val screenWidth = maxWidth
            val screenHeight = maxHeight

            val referenceScale = (screenWidth.value / 374f).coerceIn(.85f, 1.15f)
            val horizontalPadding = if (illustratedReference) 0.dp else screenWidth * layoutConfig.shellHorizontalPaddingPercent
            val innerHorizontalPadding = if (illustratedReference) 0.dp else screenWidth * layoutConfig.shellInnerHorizontalPaddingPercent
            val topSlotHeight = if (topMode == TopLayerMode.NONE || topContent == null) {
                0.dp
            } else {
                if (illustratedReference) friendsReferenceHudHeight(screenWidth, LocalDensity.current.fontScale)
                else maxOf(screenHeight * layoutConfig.topSlotHeightPercent, 56.dp)
            }
            val bottomSlotHeight = when (bottomMode) {
                BottomLayerMode.NONE -> 0.dp
                BottomLayerMode.MENU -> if (illustratedReference) 76.dp * referenceScale else maxOf(
                    screenHeight * layoutConfig.bottomSlotHeightPercent,
                    72.dp,
                )
                BottomLayerMode.AD -> screenHeight * layoutConfig.bottomSlotHeightPercent
                BottomLayerMode.AD_LOADING -> 0.dp
            }

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

            val centerLayerModifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .padding(top = layoutConfig.shellTopPadding + topSlotHeight + if (topSlotHeight > 0.dp) layoutConfig.topSlotBottomGap else 0.dp)
                .padding(bottom = bottomSlotHeight + (if (illustratedReference) bottomSlotBottomPadding else 0.dp) + if (bottomSlotHeight > 0.dp) layoutConfig.shellBottomGap else 0.dp)

            when (centerMode) {
                CenterLayerMode.SURFACE -> {
                    Surface(
                        modifier = centerLayerModifier.testTag("shell-center-surface"),
                        tonalElevation = 0.dp,
                        color = if (centerSurfaceColor != Color.Transparent) {
                            centerSurfaceColor
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                        },
                    ) {
                        ShellCenterContent(
                            innerHorizontalPadding = innerHorizontalPadding,
                            verticalPadding = if (illustratedReference) 0.dp else 4.dp,
                            content = content,
                        )
                    }
                }

                CenterLayerMode.TRANSPARENT -> {
                    Box(
                        modifier = centerLayerModifier.testTag("shell-center-transparent"),
                    ) {
                        ShellCenterContent(
                            innerHorizontalPadding = innerHorizontalPadding,
                            verticalPadding = if (illustratedReference) 0.dp else 4.dp,
                            content = content,
                        )
                    }
                }
            }

            if (bottomMode != BottomLayerMode.NONE) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = if (illustratedReference) 7.dp else horizontalPadding)
                        .padding(bottom = bottomSlotBottomPadding)
                        .height(bottomSlotHeight)
                ) {
                    if (illustratedReference && bottomMode == BottomLayerMode.MENU) {
                        FriendsReferenceBottomBar(currentSection, onSectionChange, socialNotificationCount, Modifier.fillMaxSize())
                    } else AppBottomSlot(
                        currentSection = currentSection,
                        onSectionChange = onSectionChange,
                        socialNotificationCount = socialNotificationCount,
                        bottomMode = bottomMode,
                        adContent = bottomAdContent,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun ShellCenterContent(
    innerHorizontalPadding: Dp,
    verticalPadding: Dp = 4.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = innerHorizontalPadding,
                vertical = verticalPadding,
            ),
    ) {
        content()
    }
}
