package com.mirkori.inplacex.ui.screens.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.data.local.ModeStats
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.ui.screens.company.CompanyRootScreen
import com.mirkori.inplacex.ui.screens.home.HomeRootScreen
import com.mirkori.inplacex.ui.screens.home.HomeScreenState
import com.mirkori.inplacex.ui.screens.home.RaceResultDialog
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
    fun shopOffersOneHourOfProForCoins() {
        var purchased = false
        setContent {
            ShopRootScreen(
                progressState = progress(coins = 60),
                nowMs = 1_725_000_000_000L,
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
                onBuyTemporaryPro = {
                    purchased = true
                    true
                },
            )
        }

        composeRule.onNodeWithText("Премиум").performClick()
        composeRule.onNodeWithText("PRO на 1 час").assertIsDisplayed()
        composeRule.onNodeWithText("Доступ на 01:00:00").assertIsDisplayed()
        composeRule.onNodeWithText("Купить за 60 монет").performClick()
        composeRule.runOnIdle { assertTrue(purchased) }
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
    fun duelRemainsOpenAfterThePlayersFirstAcceptedGuess() {
        setContent {
            var screenState by remember { mutableStateOf(HomeScreenState.ROOT) }
            HomeRootScreen(
                screenState = screenState,
                onScreenStateChange = { screenState = it },
            )
        }

        composeRule.onNodeWithText("Дуэль").performClick()
        composeRule.onNodeWithText("Секрет (6 цифр)").performTextInput("012345")
        composeRule.onNodeWithText("Подтвердить").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithTag("game-digit-9").fetchSemanticsNodes().isNotEmpty()
        }

        listOf('9', '8', '7', '6', '5', '4').forEach { digit ->
            composeRule.onNodeWithTag("game-digit-$digit").performClick()
        }
        composeRule.onNodeWithText("Подтвердить").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(2_500)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Ход игрока").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Ход игрока").assertIsDisplayed()
        composeRule.onNodeWithText("Дуэль").assertIsDisplayed()
    }

    @Test
    fun duelFirstGuessWinShowsResultInsteadOfSilentlyClosingTheGame() {
        var inspectedSecret: String? = null
        setContent {
            var screenState by remember { mutableStateOf(HomeScreenState.ROOT) }
            HomeRootScreen(
                screenState = screenState,
                onScreenStateChange = { screenState = it },
                onDebugSecretChange = { inspectedSecret = it },
            )
        }

        composeRule.onNodeWithText("Дуэль").performClick()
        composeRule.onNodeWithText("Секрет (6 цифр)").performTextInput("012345")
        composeRule.onNodeWithText("Подтвердить").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(6_000)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitUntil(timeoutMillis = 8_000) {
            composeRule.onAllNodesWithTag("game-digit-0").fetchSemanticsNodes().isNotEmpty()
        }

        val opponentSecret = composeRule.runOnIdle {
            checkNotNull(inspectedSecret)
        }
        opponentSecret.forEach { digit ->
            composeRule.onNodeWithTag("game-digit-$digit").performClick()
        }
        composeRule.onNodeWithText("Подтвердить").performClick()

        composeRule.onNodeWithText("Результат дуэли").assertIsDisplayed()
        composeRule.onNodeWithText("Вы первыми угадали секрет соперника.").assertIsDisplayed()
    }

    @Test
    fun raceLossShowsAResultAndExplicitNavigationChoices() {
        var returnedHome = false
        setContent {
            RaceResultDialog(
                won = false,
                attemptsUsed = 12,
                attemptLimit = 12,
                elapsedSeconds = 125,
                onRetry = {},
                onHome = { returnedHome = true },
            )
        }

        composeRule.onNodeWithText("Ходы закончились").assertIsDisplayed()
        composeRule.onNodeWithText("Попытки: 12 из 12").assertIsDisplayed()
        composeRule.onNodeWithText("Время: 02:05").assertIsDisplayed()
        composeRule.onNodeWithText("Ещё раз").assertIsDisplayed()
        composeRule.onNodeWithText("На главную").performClick()
        composeRule.runOnIdle { assertTrue(returnedHome) }
    }

    @Test
    fun raceWinShowsRewardAndOnlyRetriesAfterPlayerChoice() {
        var retried = false
        setContent {
            RaceResultDialog(
                won = true,
                attemptsUsed = 6,
                attemptLimit = 12,
                elapsedSeconds = 70,
                onRetry = { retried = true },
                onHome = {},
            )
        }

        composeRule.onNodeWithText("Гонка выиграна!").assertIsDisplayed()
        composeRule.onNodeWithText("Награда: +10 монет").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(!retried) }
        composeRule.onNodeWithText("Ещё раз").performClick()
        composeRule.runOnIdle { assertTrue(retried) }
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

    @Test
    fun companyGameKeepsTheToyRoomBackground() {
        setContent {
            CompanyRootScreen(
                progressState = progress(),
                campaignProgress = emptyList(),
                activeLevelNumber = 1,
                onActiveLevelNumberChange = {},
            )
        }

        composeRule.onNodeWithTag("company-room-background").assertIsDisplayed()
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

    private fun progress(
        coins: Int = 100,
        temporaryProExpiresAtMs: Long = 0L,
    ): GameProgressState = GameProgressState(
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
        temporaryProExpiresAtMs = temporaryProExpiresAtMs,
    )
}
