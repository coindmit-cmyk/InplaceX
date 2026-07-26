package com.mirkori.inplacex.ui.screens.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.data.local.ModeStats
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.ui.screens.company.CompanyRootScreen
import com.mirkori.inplacex.ui.screens.home.HomeRootScreen
import com.mirkori.inplacex.ui.screens.home.HomeScreenState
import com.mirkori.inplacex.ui.screens.profile.ProfileRootScreen
import com.mirkori.inplacex.ui.screens.shop.ShopRootScreen
import com.mirkori.inplacex.ui.screens.social.SocialRootScreen
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShellSectionsSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun socialExplainsThatOnlineActionsAreNotAvailableYet() {
        setContent { SocialRootScreen() }

        composeRule.onNodeWithText("Онлайн готовится").assertIsDisplayed()
        composeRule.onNodeWithText("Новых приглашений нет.").assertIsDisplayed()
    }

    @Test
    fun shopExplainsInsufficientBalance() {
        setContent {
            ShopRootScreen(
                progressState = progress(coins = 0),
                onWatchRewardedCoins = { false },
                onBuyOpenPositionHint = { false },
                onBuyCheckDigitHint = { false },
                onBuyCheckPositionHint = { false },
                onBuyExtraMovesBoost = { false },
                onBuyExtraTimeBoost = { false },
                onBuyEnergy = { false },
                onBuyRemoveAds = { false },
                onBuyPro = { false },
                onBuyProPlus = { false },
            )
        }

        composeRule.onAllNodesWithText("Не хватает монет").assertCountEquals(6)
    }

    @Test
    fun profileReportsFailedSignIn() {
        setContent {
            ProfileRootScreen(
                progressState = progress(),
                onGooglePlaySignIn = { false },
            )
        }

        composeRule.onNodeWithText("Войти через Google Play").performClick()
        composeRule.onNodeWithText(
            "Вход сейчас недоступен. Вы остаётесь в гостевом режиме, прогресс не потерян.",
        ).assertIsDisplayed()
    }

    @Test
    fun homeCompanyCardChangesGlobalSection() {
        var opened = false
        setContent {
            HomeRootScreen(
                screenState = HomeScreenState.ROOT,
                onScreenStateChange = {},
                onOpenCompany = { opened = true },
            )
        }

        composeRule.onNodeWithText("Продолжить компанию").performClick()
        composeRule.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun companyShowsCampaignMapAndPrimaryAction() {
        setContent {
            CompanyRootScreen(
                progressState = progress(),
                campaignProgress = emptyList(),
                activeLevelNumber = null,
                onActiveLevelNumberChange = {},
            )
        }

        composeRule.onNodeWithText("Компания").assertIsDisplayed()
        composeRule.onNodeWithTag("company-level-4").assertIsDisplayed()
        composeRule.onNodeWithTag("company-level-3")
            .assertIsDisplayed()
            .assertIsSelected()
        composeRule.onNodeWithTag("company-play").assertIsDisplayed()
    }

    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppStrings provides StaticLocalizationProvider.forLanguage(AppLanguage.RU),
            ) {
                InplaceXTheme(content = content)
            }
        }
    }

    private fun progress(coins: Int = 100): GameProgressState = GameProgressState(
        playerDisplayName = "Player_7065",
        googlePlaySignedIn = false,
        openPositionHints = 0,
        checkDigitHints = 0,
        checkPositionHints = 0,
        extraMovesBoosts = 0,
        extraTimeBoosts = 0,
        coins = coins,
        campaignEnergy = 3,
        campaignEnergyMax = 5,
        campaignEnergyRefillMinutes = 30,
        matchesPlayed = 4,
        matchesWon = 2,
        highestUnlockedCampaignLevel = 3,
        totalCampaignRating = 12,
        pveStats = ModeStats(wins = 1, losses = 1),
        pvpStats = ModeStats(wins = 1, losses = 1),
        companyStats = ModeStats(wins = 0, losses = 0),
        adFreePurchased = false,
        proSubscriptionActive = false,
        proPlusSubscriptionActive = false,
    )
}
