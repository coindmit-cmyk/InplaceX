package com.mirkori.inplacex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.ui.background.ScreenBackgroundStyle
import com.mirkori.inplacex.ui.navigation.AppSection
import com.mirkori.inplacex.ui.screens.home.HomeRootScreen
import com.mirkori.inplacex.ui.screens.profile.ProfileRootScreen
import com.mirkori.inplacex.ui.screens.settings.SettingsRootScreen
import com.mirkori.inplacex.ui.screens.shop.ShopRootScreen
import com.mirkori.inplacex.ui.screens.social.SocialRootScreen
import com.mirkori.inplacex.ui.screens.tournaments.TournamentsRootScreen
import com.mirkori.inplacex.ui.shell.AppShell
import com.mirkori.inplacex.ui.shell.AppTopBar
import com.mirkori.inplacex.ui.shell.BottomLayerMode
import com.mirkori.inplacex.ui.shell.TopLayerMode
import com.mirkori.inplacex.ui.theme.InplaceXTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enableImmersiveFullscreen()

        setContent {
            InplaceXTheme {
                var currentSection by rememberSaveable { mutableStateOf(AppSection.HOME) }
                var isInGame by rememberSaveable { mutableStateOf(false) }
                var requestExitGame by rememberSaveable { mutableStateOf(false) }
                var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
                var currentLanguageName by rememberSaveable { mutableStateOf(AppLanguage.RU.name) }
                val currentLanguage = AppLanguage.valueOf(currentLanguageName)
                val strings = remember(currentLanguage) {
                    StaticLocalizationProvider.forLanguage(currentLanguage)
                }
                val isPremium = false

                val bottomMode = when {
                    isInGame -> if (isPremium) BottomLayerMode.NONE else BottomLayerMode.AD
                    else -> BottomLayerMode.MENU
                }

                val canGoBack = isSettingsOpen || isInGame

                CompositionLocalProvider(LocalAppStrings provides strings) {
                    AppShell(
                        currentSection = currentSection,
                        onSectionChange = { section ->
                            currentSection = section
                            isSettingsOpen = false
                        },
                        bottomMode = bottomMode,
                        topMode = TopLayerMode.OVERLAY,
                        backgroundStyle = ScreenBackgroundStyle.SolidColor(Color(0xFF4C6FFF)),
                        topContent = {
                            AppTopBar(
                                canGoBack = canGoBack,
                                onBackClick = {
                                    when {
                                        isSettingsOpen -> isSettingsOpen = false
                                        isInGame -> requestExitGame = true
                                    }
                                },
                                onSettingsClick = {
                                    isSettingsOpen = true
                                }
                            )
                        }
                    ) {
                        when {
                            isSettingsOpen -> SettingsRootScreen(
                                currentLanguage = currentLanguage,
                                onLanguageChange = { language ->
                                    currentLanguageName = language.name
                                }
                            )

                            currentSection == AppSection.HOME -> HomeRootScreen(
                                requestExitGame = requestExitGame,
                                onExitGameConsumed = { requestExitGame = false },
                                onInGameChange = { inGame -> isInGame = inGame }
                            )

                            currentSection == AppSection.SOCIAL -> SocialRootScreen()
                            currentSection == AppSection.TOURNAMENTS -> TournamentsRootScreen()
                            currentSection == AppSection.SHOP -> ShopRootScreen()
                            currentSection == AppSection.PROFILE -> ProfileRootScreen()
                        }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveFullscreen()
        }
    }

    private fun enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
