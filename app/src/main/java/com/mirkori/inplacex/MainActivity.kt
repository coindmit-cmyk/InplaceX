package com.mirkori.inplacex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.mirkori.inplacex.ui.navigation.AppSection
import com.mirkori.inplacex.ui.screens.home.HomeRootScreen
import com.mirkori.inplacex.ui.screens.profile.ProfileRootScreen
import com.mirkori.inplacex.ui.screens.shop.ShopRootScreen
import com.mirkori.inplacex.ui.screens.social.SocialRootScreen
import com.mirkori.inplacex.ui.screens.tournaments.TournamentsRootScreen
import com.mirkori.inplacex.ui.shell.AppShell
import com.mirkori.inplacex.ui.theme.InplaceXTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            InplaceXTheme {
                var currentSection by rememberSaveable { mutableStateOf(AppSection.HOME) }

                AppShell(
                    currentSection = currentSection,
                    onSectionChange = { currentSection = it },
                    isInGame = false,
                    isPremium = false
                ) {
                    when (currentSection) {
                        AppSection.HOME -> HomeRootScreen()
                        AppSection.SOCIAL -> SocialRootScreen()
                        AppSection.TOURNAMENTS -> TournamentsRootScreen()
                        AppSection.SHOP -> ShopRootScreen()
                        AppSection.PROFILE -> ProfileRootScreen()
                    }
                }
            }
        }
    }
}
