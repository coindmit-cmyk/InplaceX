package com.mirkori.inplacex.ui.screens.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.data.local.CampaignLevelProgress
import com.mirkori.inplacex.data.local.LocalPlayerProfile
import com.mirkori.inplacex.data.local.ModeStats
import com.mirkori.inplacex.data.local.RetentionRewardStatus
import com.mirkori.inplacex.core.retention.RetentionRewardType
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.platform.mirkori.MirkoriAccountState
import com.mirkori.inplacex.platform.mirkori.MirkoriAccountStateKind
import com.mirkori.inplacex.platform.online.OnlineRuntime
import com.mirkori.inplacex.ui.navigation.AppSection
import com.mirkori.inplacex.ui.screens.company.CompanyRootScreen
import com.mirkori.inplacex.ui.screens.home.HomeRootScreen
import com.mirkori.inplacex.ui.screens.home.HomeScreenState
import com.mirkori.inplacex.ui.screens.home.RaceResultDialog
import com.mirkori.inplacex.ui.screens.profile.ProfileRootScreen
import com.mirkori.inplacex.ui.screens.shop.ShopRootScreen
import com.mirkori.inplacex.ui.screens.social.SocialRootScreen
import com.mirkori.inplacex.ui.screens.settings.SettingsRootScreen
import com.mirkori.inplacex.ui.shell.AppShell
import com.mirkori.inplacex.ui.shell.AppBottomAd
import com.mirkori.inplacex.ui.shell.BottomLayerMode
import com.mirkori.inplacex.ui.shell.CenterLayerMode
import com.mirkori.inplacex.ui.shell.TopLayerMode
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShellSectionsSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun transparentCenterPlacesContentDirectlyOnTheScene() {
        setContent {
            AppShell(
                currentSection = AppSection.HOME,
                onSectionChange = {},
                bottomMode = BottomLayerMode.NONE,
                topMode = TopLayerMode.NONE,
                centerMode = CenterLayerMode.TRANSPARENT,
            ) {
                Text("Игровое поле")
            }
        }

        composeRule.onNodeWithTag("shell-center-transparent").assertIsDisplayed()
        composeRule.onAllNodesWithTag("shell-center-surface").assertCountEquals(0)
        composeRule.onNodeWithText("Игровое поле").assertIsDisplayed()
    }

    @Test
    fun gameBannerUsesTheDedicatedAdBlock() {
        setContent { AppBottomAd() }

        composeRule.onNodeWithTag("game-banner-slot").assertIsDisplayed()
        composeRule.onNodeWithText("AD").assertIsDisplayed()
        composeRule.onNodeWithText("Рекламный слот игрового экрана").assertIsDisplayed()
    }

    @Test
    fun debugBuildDoesNotExposeDeveloperModeInNormalSettings() {
        setContent {
            SettingsRootScreen(
                currentLanguage = AppLanguage.RU,
                onLanguageChange = {},
                onOpenInternalTools = {},
                onClose = {},
            )
        }

        composeRule.onAllNodesWithText("Режим разработчика").assertCountEquals(0)
    }

    @Test
    fun unconfiguredSocialSectionShowsTruthfulOfflineStateAndCurrentActions() {
        setContent { SocialRootScreen(showTestFriendBot = true) }

        composeRule.onNodeWithText("Онлайн готовится").assertIsDisplayed()
        composeRule.onNodeWithText("Друзья").performClick()
        composeRule.onNodeWithText("Mirkori Bot").assertIsDisplayed()
        composeRule.onNodeWithText("Тестовый друг · онлайн сейчас недоступен").assertIsDisplayed()
        composeRule.onNodeWithText("Играть").assertIsNotEnabled()
    }

    @Test
    fun testBotOpensSharedOnlineConfigurationBeforeSearching() {
        val runtime = requireNotNull(
            OnlineRuntime.createOrNull(
                context = composeRule.activity,
                profile = LocalPlayerProfile(
                    playerId = "instrumented-player",
                    installationId = "instrumented-installation",
                    displayName = "Instrumented",
                ),
                locale = "ru-RU",
                regionCode = "RU",
                baseUrl = "http://127.0.0.1:65535",
                allowCleartextLoopback = true,
            ),
        )
        try {
            setContent {
                SocialRootScreen(
                    onlineRuntime = runtime,
                    showTestFriendBot = true,
                )
            }

            composeRule.onNodeWithText("Онлайн доступен").assertIsDisplayed()
            composeRule.onNodeWithText("Друзья").performClick()
            composeRule.onNodeWithText("Mirkori Bot").assertIsDisplayed()
            composeRule.onNodeWithText("Тестовый друг · серверный бот").assertIsDisplayed()
            composeRule.onNodeWithText("Играть").performClick()
            composeRule.onNodeWithText("Онлайн-матч").assertIsDisplayed()
            composeRule.onNodeWithText("Настройки онлайн-матча").assertIsDisplayed()
            composeRule.onNodeWithText("4 цифры").assertIsDisplayed()
            composeRule.onNodeWithText("На время").assertIsDisplayed()
            composeRule.onNodeWithText(
                "Оба игрока разгадывают одновременно. Побеждает тот, кто первым найдёт код.",
            ).assertIsDisplayed()
            composeRule.onNodeWithText("По очереди").performClick()
            composeRule.onNodeWithText(
                "Игроки делают по одному ходу. После принятого хода очередь переходит сопернику.",
            ).assertIsDisplayed()
            composeRule.onNodeWithText("Найти матч").assertIsDisplayed()
            composeRule.onAllNodesWithText("Создать код").assertCountEquals(0)
            composeRule.onAllNodesWithText("Войти по коду").assertCountEquals(0)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun invitationsContainPrivateCodeActionsWithoutRandomMatchmaking() {
        val runtime = requireNotNull(
            OnlineRuntime.createOrNull(
                context = composeRule.activity,
                profile = LocalPlayerProfile(
                    playerId = "instrumented-player",
                    installationId = "instrumented-installation-invites",
                    displayName = "Instrumented",
                ),
                locale = "ru-RU",
                regionCode = "RU",
                baseUrl = "http://127.0.0.1:65535",
                allowCleartextLoopback = true,
            ),
        )
        try {
            setContent { SocialRootScreen(onlineRuntime = runtime) }

            composeRule.onNodeWithText("Приглашения").performClick()
            composeRule.onNodeWithText("Создать код").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Войти по коду").performScrollTo().assertIsDisplayed()
            composeRule.onAllNodesWithText("Найти матч").assertCountEquals(0)
        } finally {
            runtime.close()
        }
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
    fun profileDisplaysTheExternallyOwnedFailedSignInState() {
        setContent {
            ProfileRootScreen(
                progressState = progress(),
                authResultKey = "profile.auth.unavailable",
            )
        }

        composeRule.onNodeWithText(
            "Вход сейчас недоступен. Вы остаётесь в гостевом режиме, прогресс не потерян.",
        ).assertIsDisplayed()
    }

    @Test
    fun profileMakesMirkoriGamesThePrimaryAccountEntry() {
        var signInRequested = false
        setContent {
            ProfileRootScreen(
                progressState = progress(),
                mirkoriAccountState = MirkoriAccountState(
                    kind = MirkoriAccountStateKind.GUEST,
                    gamePlayerId = "00000000-0000-4000-8000-000000000802",
                ),
                onMirkoriSignIn = { signInRequested = true },
            )
        }

        composeRule.onNodeWithText("Гостевой профиль Mirkori Games").assertIsDisplayed()
        composeRule.onNodeWithText("Войти в Mirkori Games").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(signInRequested) }
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
        composeRule.onNodeWithText("С ботом").performClick()
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
        composeRule.onNodeWithText("С ботом").performClick()
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
    fun duelModeChoiceRoutesOnlineMatchToSocialRuntime() {
        var openedOnline = false
        setContent {
            var screenState by remember { mutableStateOf(HomeScreenState.ROOT) }
            HomeRootScreen(
                screenState = screenState,
                onScreenStateChange = { screenState = it },
                onOpenOnlineDuel = { openedOnline = true },
                onlineAvailable = true,
            )
        }

        composeRule.onNodeWithText("Дуэль").performClick()
        composeRule.onNodeWithText("Онлайн-матч").performClick()

        composeRule.runOnIdle { assertTrue(openedOnline) }
    }

    @Test
    fun duelModeChoiceDisablesOnlineWithoutConfiguredRuntime() {
        setContent {
            var screenState by remember { mutableStateOf(HomeScreenState.ROOT) }
            HomeRootScreen(
                screenState = screenState,
                onScreenStateChange = { screenState = it },
                onlineAvailable = false,
            )
        }

        composeRule.onNodeWithText("Дуэль").performClick()
        composeRule.onNodeWithText("Онлайн-матч").assertIsNotEnabled()
    }

    @Test
    fun unlimitedRaceResultDoesNotShowAFakeMoveCap() {
        setContent {
            RaceResultDialog(
                won = true,
                attemptsUsed = 18,
                attemptLimit = null,
                elapsedSeconds = 70,
                onRetry = {},
                onHome = {},
            )
        }

        composeRule.onNodeWithText("Попытки: 18 • без лимита").assertIsDisplayed()
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
        composeRule.onNodeWithText("0 из 20 звёзд").assertIsDisplayed()
        composeRule.onNodeWithText("Следующая глава уже открыта.").assertDoesNotExist()
    }

    @Test
    fun companySeparatesTenLevelChapters() {
        setContent {
            CompanyRootScreen(
                progressState = progress(),
                campaignProgress = emptyList(),
                activeLevelNumber = null,
                onActiveLevelNumberChange = {},
            )
        }

        composeRule.onNodeWithTag("company-level-3").assertIsDisplayed()
        composeRule.onNodeWithTag("company-next-chapter").performClick()
        composeRule.onNodeWithTag("company-level-11")
            .assertIsDisplayed()
            .assertIsSelected()
        composeRule.onNodeWithTag("company-level-10").assertDoesNotExist()
    }

    @Test
    fun companyHistoryHasAnExplicitBackAction() {
        setContent {
            CompanyRootScreen(
                progressState = progress(),
                campaignProgress = listOf(CampaignLevelProgress(1, 10)),
                activeLevelNumber = null,
                onActiveLevelNumberChange = {},
            )
        }

        composeRule.onNodeWithTag("company-history").performClick()
        composeRule.onNodeWithText("История").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Назад").performClick()
        composeRule.onNodeWithText("Компания").assertIsDisplayed()
    }

    @Test
    fun campaignBonusDialogClaimsDailyAndWeeklyRewardsIndependently() {
        var dailyClaims = 0
        var weeklyClaims = 0
        var refreshes = 0
        setContent {
            var status by remember {
                mutableStateOf(RetentionRewardStatus(dailyAvailable = true, weeklyAvailable = true))
            }
            CompanyRootScreen(
                progressState = progress(),
                campaignProgress = emptyList(),
                activeLevelNumber = null,
                onActiveLevelNumberChange = {},
                retentionRewardStatus = status,
                onRefreshRetentionRewards = { refreshes += 1 },
                onClaimRetentionReward = { type ->
                    when (type) {
                        RetentionRewardType.DAILY -> {
                            dailyClaims += 1
                            status = status.copy(dailyAvailable = false)
                        }
                        RetentionRewardType.WEEKLY -> {
                            weeklyClaims += 1
                            status = status.copy(weeklyAvailable = false)
                        }
                    }
                    true
                },
            )
        }

        composeRule.onNodeWithTag("company-retention-rewards").performClick()
        composeRule.onNodeWithText("Ежедневные бонусы").assertIsDisplayed()
        composeRule.onNodeWithTag("company-retention-daily-claim").performClick()
        composeRule.onNodeWithTag("company-retention-daily-claim").assertIsNotEnabled()
        composeRule.onNodeWithTag("company-retention-weekly-claim").performClick()
        composeRule.onNodeWithTag("company-retention-weekly-claim").assertIsNotEnabled()
        composeRule.runOnIdle {
            assertTrue(dailyClaims == 1)
            assertTrue(weeklyClaims == 1)
            assertTrue(refreshes == 1)
        }
    }

    @Test
    fun lockedChapterRewardExplainsHowToUnlockIt() {
        setContent {
            CompanyRootScreen(
                progressState = progress(),
                campaignProgress = emptyList(),
                activeLevelNumber = null,
                onActiveLevelNumberChange = {},
            )
        }

        composeRule.onNodeWithTag("company-chapter-reward").performClick()
        composeRule.onNodeWithText(
            "Пройдите все 10 уровней этой главы, чтобы получить награду.",
        ).assertIsDisplayed()
    }

    @Test
    fun landscapeCompanyKeepsChapterRewardAccessible() {
        setContent {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            ) {
                CompanyRootScreen(
                    progressState = progress(),
                    campaignProgress = emptyList(),
                    activeLevelNumber = null,
                    onActiveLevelNumberChange = {},
                )
            }
        }

        composeRule.onNodeWithTag("company-chapter-reward")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText(
            "Пройдите все 10 уровней этой главы, чтобы получить награду.",
        ).assertIsDisplayed()
    }

    @Test
    fun firstCampaignLevelExplainsTheGameBeforeStartingTheMatch() {
        var tutorialCompleted = false
        var matchStarted = false

        setContent {
            var currentProgress by remember { mutableStateOf(progress()) }
            CompanyRootScreen(
                progressState = currentProgress,
                campaignProgress = emptyList(),
                activeLevelNumber = 1,
                onActiveLevelNumberChange = {},
                onCampaignTutorialCompleted = {
                    tutorialCompleted = true
                    currentProgress = currentProgress.copy(campaignTutorialCompleted = true)
                },
                onMatchStarted = { matchStarted = true },
            )
        }

        composeRule.onNodeWithText("Как играть").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(!matchStarted) }

        composeRule.onNodeWithTag("company-tutorial-next").performClick()
        composeRule.onNodeWithText("Читайте результат хода").assertIsDisplayed()
        composeRule.onNodeWithTag("company-tutorial-next").performClick()
        composeRule.onNodeWithText("Подсказки и усилители").assertIsDisplayed()
        composeRule.onNodeWithTag("company-tutorial-start").performClick()

        composeRule.runOnIdle {
            assertTrue(tutorialCompleted)
            assertTrue(matchStarted)
        }
    }

    @Test
    fun companyGameDoesNotDrawASecondRoomBackgroundInsideTheShell() {
        setContent {
            CompanyRootScreen(
                progressState = progress(),
                campaignProgress = emptyList(),
                activeLevelNumber = 1,
                onActiveLevelNumberChange = {},
            )
        }

        composeRule.onAllNodesWithTag("company-room-background").assertCountEquals(0)
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
