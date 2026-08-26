package com.mirkori.inplacex.ui.screens.shell

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.data.local.CampaignLevelProgress
import com.mirkori.inplacex.data.local.LocalRelationshipStatus
import com.mirkori.inplacex.data.local.LocalRelationshipType
import com.mirkori.inplacex.data.local.LocalSocialRelationship
import com.mirkori.inplacex.data.local.ModeStats
import com.mirkori.inplacex.data.local.RetentionRewardStatus
import com.mirkori.inplacex.core.retention.RetentionRewardType
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.ads.AdConsentDecision
import com.mirkori.inplacex.platform.feedback.AppFeedbackSettings
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.platform.mirkori.MirkoriAccountState
import com.mirkori.inplacex.platform.mirkori.MirkoriAccountStateKind
import com.mirkori.inplacex.platform.mirkori.MirkoriFriendOperationResult
import com.mirkori.inplacex.platform.mirkori.MirkoriFriendRequest
import com.mirkori.inplacex.platform.mirkori.MirkoriPlayerSearchResult
import com.mirkori.inplacex.platform.mirkori.MirkoriPublicPlayerProfile
import com.mirkori.inplacex.platform.online.OnlineRuntime
import com.mirkori.inplacex.platform.online.AccessToken
import com.mirkori.inplacex.platform.online.AccessTokenProvider
import com.mirkori.inplacex.platform.online.RemoteFriendPlayStyle
import com.mirkori.inplacex.ui.navigation.AppSection
import com.mirkori.inplacex.ui.screens.company.CompanyRootScreen
import com.mirkori.inplacex.ui.screens.home.HomeRootScreen
import com.mirkori.inplacex.ui.screens.home.HomeScreenState
import com.mirkori.inplacex.ui.screens.home.PvpModesScreen
import com.mirkori.inplacex.ui.screens.home.RaceResultDialog
import com.mirkori.inplacex.ui.screens.profile.ProfileRootScreen
import com.mirkori.inplacex.ui.screens.shop.ShopRootScreen
import com.mirkori.inplacex.ui.screens.social.SocialRootScreen
import com.mirkori.inplacex.ui.screens.social.FriendsReferenceScreen
import com.mirkori.inplacex.ui.screens.settings.SettingsRootScreen
import com.mirkori.inplacex.ui.shell.AppShell
import com.mirkori.inplacex.ui.shell.AppBottomAd
import com.mirkori.inplacex.ui.shell.AppTopBar
import com.mirkori.inplacex.ui.shell.BottomLayerMode
import com.mirkori.inplacex.ui.shell.CenterLayerMode
import com.mirkori.inplacex.ui.shell.TopLayerMode
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import com.mirkori.platform.sdk.PlatformAuthMode
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                adConsentDecision = AdConsentDecision.DECLINED,
                feedbackSettings = AppFeedbackSettings(),
                onLanguageChange = {},
                onVibrationChange = {},
                onSoundChange = {},
                onMusicChange = {},
                onOpenAdPrivacy = {},
                onOpenWebsitePage = {},
                onOpenInternalTools = {},
                onClose = {},
            )
        }

        composeRule.onAllNodesWithText("Режим разработчика").assertCountEquals(0)
        composeRule.onNodeWithText("Вибрация").assertIsDisplayed()
        composeRule.onNodeWithText("Звуки").assertIsDisplayed()
        composeRule.onNodeWithText("Связаться с нами").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Условия использования").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Политика конфиденциальности").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("О нас").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Лицензии открытого кода").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun unconfiguredSocialSectionShowsTruthfulOfflineStateAndCurrentActions() {
        setContent { SocialRootScreen(showTestFriendBot = true) }

        composeRule.onNodeWithText("Онлайн готовится").assertIsDisplayed()
        composeRule.onNodeWithTag("friends-open-all").performScrollTo().performClick()
        composeRule.onNodeWithText("Mirkori Bot").assertIsDisplayed()
        composeRule.onNodeWithText("Тестовый друг · онлайн сейчас недоступен").assertIsDisplayed()
        composeRule.onNodeWithText("Играть").assertIsNotEnabled()
    }

    @Test
    fun friendsBackReturnsToSocialRootWithoutStartingAGame() {
        var nested = false
        setContent {
            SocialRootScreen(onNestedScreenChange = { nested = it })
        }

        composeRule.onNodeWithTag("friends-open-all").performScrollTo().performClick()
        composeRule.onNodeWithText("Добавить друга").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(nested) }

        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("friends-reference-screen").assertIsDisplayed()
        composeRule.onNodeWithText("Друзья").assertIsDisplayed()
        composeRule.runOnIdle { assertFalse(nested) }
    }

    @Test
    fun incomingFriendRequestIsVisibleAtSocialRootAndInsideFriends() {
        val request = MirkoriFriendRequest(
            requestId = "00000000-0000-4000-8000-000000000902",
            player = MirkoriPublicPlayerProfile(
                gamePlayerId = "00000000-0000-4000-8000-000000000903",
                handle = "friendly_player",
                displayName = "Friendly Player",
                avatarUrl = null,
            ),
        )
        setContent { SocialRootScreen(incomingFriendRequests = listOf(request)) }

        composeRule.onNodeWithText("Friendly Player").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("friends-requests-open").performScrollTo().performClick()
        composeRule.onNodeWithText("Friendly Player").assertIsDisplayed()
        composeRule.onNodeWithText("Хочет добавить вас в друзья").assertIsDisplayed()
        composeRule.onNodeWithText("Принять").assertIsDisplayed()
    }

    @Test
    fun outgoingFriendRequestIsNotRenderedAsPlayableFriend() {
        val pendingRequest = LocalSocialRelationship(
            playerId = "00000000-0000-4000-8000-000000000904",
            targetPlayerId = "00000000-0000-4000-8000-000000000905",
            targetDisplayName = "Pending Player",
            relationshipType = LocalRelationshipType.INVITE_OUTGOING,
            status = LocalRelationshipStatus.PENDING,
            source = "platform_friend_request",
        )
        setContent { SocialRootScreen(pendingFriendRequests = listOf(pendingRequest)) }

        composeRule.onNodeWithTag("friends-open-all").performScrollTo().performClick()
        composeRule.onNodeWithText("Pending Player").assertIsDisplayed()
        composeRule.onNodeWithText("Заявка в друзья отправлена.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Играть").assertCountEquals(0)
    }

    @Test
    fun friendSearchDoesNotOfferTheCurrentPlayer() {
        val currentPlayerId = "00000000-0000-4000-8000-000000000901"
        setContent {
            SocialRootScreen(
                currentPlayerId = currentPlayerId,
                onSearchPlayers = {
                    MirkoriPlayerSearchResult.Success(
                        listOf(
                            MirkoriPublicPlayerProfile(
                                gamePlayerId = currentPlayerId,
                                handle = "self_player",
                                displayName = "Self Player",
                                avatarUrl = null,
                            ),
                        ),
                    )
                },
            )
        }

        composeRule.onNodeWithTag("friends-open-all").performScrollTo().performClick()
        composeRule.onNodeWithText("Добавить друга").performClick()
        composeRule.onNodeWithText("Имя или публичный ID").performTextInput("self_player")
        composeRule.onNodeWithText("Найти").performClick()

        composeRule.onNodeWithText("Игроки не найдены.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Self Player").assertCountEquals(0)
    }

    @Test
    fun testBotOpensSharedOnlineConfigurationBeforeSearching() {
        val runtime = requireNotNull(
            OnlineRuntime.createOrNull(
                context = composeRule.activity,
                accessTokenProvider = InstrumentedAccessTokenProvider,
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
            composeRule.onNodeWithTag("friends-open-all").performScrollTo().performClick()
            composeRule.onNodeWithText("Mirkori Bot").assertIsDisplayed()
            composeRule.onNodeWithText("Тестовый друг · серверный бот").assertIsDisplayed()
            composeRule.onNodeWithText("Играть").performClick()
            composeRule.onNodeWithText("Онлайн‑матч").assertIsDisplayed()
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
                accessTokenProvider = InstrumentedAccessTokenProvider,
                baseUrl = "http://127.0.0.1:65535",
                allowCleartextLoopback = true,
            ),
        )
        try {
            setContent { SocialRootScreen(onlineRuntime = runtime) }

            composeRule.onNodeWithTag("friends-invite").performScrollTo().performClick()
            composeRule.onNodeWithText("Создать код").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Войти по коду").performScrollTo().assertIsDisplayed()
            composeRule.onAllNodesWithText("Найти матч").assertCountEquals(0)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun friendsReferenceEmptyOfflineStateDoesNotInventSocialDataOrAllowNetworkActions() {
        val actions = mutableListOf<String>()
        showFriendsReference(
            onOpenFriends = { actions += "friends" },
            onInvite = { actions += "invite" },
            onFindMatch = { actions += "match" },
        )

        composeRule.onNodeWithText("Онлайн готовится").assertIsDisplayed()
        composeRule.onNodeWithTag("friends-request").assertDoesNotExist()
        composeRule.onAllNodesWithText("Онлайн:", substring = true).assertCountEquals(0)
        listOf("Mirki", "Lina", "Alexey", "Kate", "Player 8b73615b").forEach { name ->
            composeRule.onAllNodesWithText(name).assertCountEquals(0)
        }
        listOf("friends-invite", "friends-find-match", "friends-online-matches").forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled().performClick()
        }
        composeRule.runOnIdle { assertTrue(actions.isEmpty()) }
        composeRule.onNodeWithTag("friends-open-all").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(listOf("friends"), actions) }
    }

    @Test
    fun friendsReferenceDoesNotTreatConfiguredTransportAsFriendPresence() {
        showFriendsReference(
            friends = listOf(referenceFriend("0", "Actual Friend")),
            onlineConfigured = true,
        )

        composeRule.onNodeWithText("Actual Friend").assertIsDisplayed()
        composeRule.onNodeWithText("Онлайн доступен").assertIsDisplayed()
        composeRule.onAllNodesWithText("Онлайн:", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Онлайн друзья").assertCountEquals(0)
    }

    @Test
    fun friendsReferenceRequestWaitsRetriesAndDoesNotResurrectAnAcceptedRequest() {
        val request = referenceFriendRequest()
        val firstResult = CompletableDeferred<MirkoriFriendOperationResult>()
        var calls = 0
        setContent {
            SocialRootScreen(
                incomingFriendRequests = listOf(request),
                onAcceptFriendRequest = {
                    assertEquals(request.requestId, it.requestId)
                    calls++
                    if (calls == 1) firstResult.await() else MirkoriFriendOperationResult.Success(it)
                },
            )
        }

        composeRule.onNodeWithTag("friends-accept-request").performScrollTo().performClick()
        composeRule.onNodeWithTag("friends-accept-request").assertIsNotEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(1, calls)
            firstResult.complete(MirkoriFriendOperationResult.Unavailable)
        }
        composeRule.onNodeWithText("Сервис друзей сейчас недоступен.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("friends-accept-request").performScrollTo().performClick()
        composeRule.onNodeWithTag("friends-request").assertDoesNotExist()
        composeRule.onNodeWithTag("friends-accept-request").assertDoesNotExist()
        composeRule.onNodeWithTag("friends-invite").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(2, calls) }
    }

    @Test
    fun friendsReferenceLargeEnglishTextKeepsRequestsAndActionsReachable() {
        val actions = mutableListOf<String>()
        val request = referenceFriendRequest().let {
            it.copy(player = it.player.copy(displayName = "A friend with a very long display name to wrap"))
        }
        var acceptanceCalls = 0
        setContent(language = AppLanguage.EN) {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.5f)) {
                Box(Modifier.width(320.dp).height(600.dp)) {
                    FriendsReferenceScreen(
                        friends = listOf(referenceFriend("0", "A very long friend display name")),
                        incomingFriendRequests = listOf(request),
                        onlineConfigured = true,
                        onOpenFriends = { actions += "friends" },
                        onInvite = { actions += "invite" },
                        onFindMatch = { actions += "match" },
                        onAcceptFriendRequest = {
                            acceptanceCalls++
                            if (acceptanceCalls == 1) MirkoriFriendOperationResult.Rejected
                            else MirkoriFriendOperationResult.Success(it)
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Online is available").assertIsDisplayed()
        assertNoUntranslatedSocialKeys()
        composeRule.onNodeWithTag("friends-open-all").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("friends-accept-request").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText("The friend request could not be completed.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("friends-accept-request").performScrollTo().performClick()
        composeRule.onNodeWithTag("friends-request").assertDoesNotExist()
        listOf("friends-invite", "friends-find-match", "friends-online-matches").forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
        }
        assertNoUntranslatedSocialKeys()
        composeRule.runOnIdle {
            assertEquals(2, acceptanceCalls)
            assertEquals(listOf("friends", "invite", "match", "match"), actions)
        }
    }

    @Test
    fun friendsReferenceLargeHudShowsFullCountersAndKeepsActionsReachable() {
        val actions = mutableListOf<String>()
        setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.5f)) {
                Box(Modifier.width(320.dp).height(600.dp)) {
                    AppShell(
                        currentSection = AppSection.SOCIAL,
                        onSectionChange = {},
                        bottomMode = BottomLayerMode.MENU,
                        topMode = TopLayerMode.OVERLAY,
                        centerMode = CenterLayerMode.TRANSPARENT,
                        friendsReference = true,
                        topContent = {
                            AppTopBar(
                                energy = 5,
                                energyMax = 5,
                                coins = Int.MAX_VALUE,
                                showBack = false,
                                showShop = true,
                                onBackClick = {},
                                onShopClick = { actions += "shop" },
                                onSettingsClick = { actions += "settings" },
                                friendsReference = true,
                            )
                        },
                    ) {
                        Text("body")
                    }
                }
            }
        }

        val bodyTop = composeRule.onNodeWithText("body").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        listOf("5/5", Int.MAX_VALUE.toString()).forEach { value ->
            val layouts = mutableListOf<TextLayoutResult>()
            val counter = composeRule.onNodeWithText(value, useUnmergedTree = true).assertIsDisplayed()
            counter.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayouts ->
                assertTrue("The counter must expose its rendered text layout", getLayouts(layouts))
            }
            assertFalse("The complete counter $value must fit without clipping or ellipsis", layouts.single().hasVisualOverflow)
            assertTrue("The HUD counter must not overlap the page content", counter.fetchSemanticsNode().boundsInRoot.bottom <= bodyTop)
        }
        composeRule.onNodeWithContentDescription("Магазин").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Настройки").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(listOf("shop", "settings"), actions) }
    }

    @Test
    fun friendsReferenceCaptureAndActionsUseApprovedFixture() {
        val friends = listOf("Mirki", "Lina", "Alexey", "Kate", "Friend 5", "Friend 6", "Friend 7", "Friend 8")
            .mapIndexed { index, name -> referenceFriend(index.toString(), name) }
        val request = referenceFriendRequest()
        val actions = mutableListOf<String>()
        setContent {
            AppShell(
                currentSection = AppSection.SOCIAL,
                onSectionChange = {},
                socialNotificationCount = 1,
                bottomMode = BottomLayerMode.MENU,
                topMode = TopLayerMode.OVERLAY,
                centerMode = CenterLayerMode.TRANSPARENT,
                friendsReference = true,
                topContent = {
                    AppTopBar(
                        energy = 5,
                        energyMax = 5,
                        coins = 10146,
                        showBack = false,
                        showShop = true,
                        onBackClick = {},
                        onShopClick = {},
                        onSettingsClick = {},
                        friendsReference = true,
                    )
                },
            ) {
                FriendsReferenceScreen(
                    friends = friends,
                    incomingFriendRequests = listOf(request),
                    onlineConfigured = true,
                    onlineFriendIds = friends.map { it.targetPlayerId }.toSet(),
                    onOpenFriends = { actions += "friends" },
                    onInvite = { actions += "invite" },
                    onFindMatch = { actions += "match" },
                    onAcceptFriendRequest = { MirkoriFriendOperationResult.Success(it) },
                )
            }
        }

        composeRule.onNodeWithTag("friends-reference-shell").assertIsDisplayed()
        listOf("Mirki", "Lina", "Alexey", "Kate", "Player 8b73615b").forEach { name ->
            composeRule.onNodeWithText(name).assertIsDisplayed()
        }
        composeRule.waitForIdle()
        val output = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "visual-qa/friends-reference.png",
        )
        output.parentFile?.mkdirs()
        output.outputStream().use { stream ->
            check(composeRule.onNodeWithTag("friends-reference-shell")
                .captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }

        listOf("friends-open-all", "friends-requests-open", "friends-invite", "friends-find-match", "friends-online-matches")
            .forEach { tag -> composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick() }
        composeRule.runOnIdle {
            assertEquals(listOf("friends", "friends", "invite", "match", "match"), actions)
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
                showGooglePlayCard = true,
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
    fun linkedMirkoriAccountDoesNotExposeFalseGoogleSignOut() {
        setContent {
            ProfileRootScreen(
                progressState = progress().copy(googlePlaySignedIn = true),
                mirkoriAccountState = MirkoriAccountState(
                    kind = MirkoriAccountStateKind.LINKED,
                    gamePlayerId = "00000000-0000-4000-8000-000000000803",
                ),
            )
        }

        composeRule.onAllNodesWithText("Выйти из Google Play").assertCountEquals(0)
    }

    @Test
    fun initializingMirkoriAccountDoesNotExposeLegacyGoogleSignOut() {
        setContent {
            ProfileRootScreen(
                progressState = progress().copy(googlePlaySignedIn = true),
                mirkoriAccountState = MirkoriAccountState(MirkoriAccountStateKind.INITIALIZING),
            )
        }

        composeRule.onAllNodesWithText("Выйти из Google Play").assertCountEquals(0)
    }

    @Test
    fun linkedTelegramAccountDoesNotOfferUnsupportedGoogleLinking() {
        setContent {
            ProfileRootScreen(
                progressState = progress().copy(googlePlaySignedIn = false),
                mirkoriAccountState = MirkoriAccountState(
                    kind = MirkoriAccountStateKind.LINKED,
                    gamePlayerId = "00000000-0000-4000-8000-000000000804",
                    authMode = PlatformAuthMode.TELEGRAM,
                ),
            )
        }

        composeRule.onAllNodesWithText("Войти через Google Play").assertCountEquals(0)
    }

    @Test
    fun releaseLikeGuestProfileDoesNotOfferLegacyGoogleLogin() {
        setContent {
            ProfileRootScreen(
                progressState = progress().copy(googlePlaySignedIn = false),
                mirkoriAccountState = MirkoriAccountState(
                    kind = MirkoriAccountStateKind.GUEST,
                    gamePlayerId = "00000000-0000-4000-8000-000000000805",
                ),
                showGooglePlayCard = false,
            )
        }

        composeRule.onAllNodesWithText("Войти через Google Play").assertCountEquals(0)
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

        composeRule.onNodeWithText("Компания").performClick()
        composeRule.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun compactOpponentChoiceKeepsBackActionAccessible() {
        var wentBack = false
        setContent {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
            ) {
                PvpModesScreen(
                    codeLength = 6,
                    onCodeLengthChange = {},
                    onPlayWithBot = {},
                    onPlayOnline = {},
                    onlineAvailable = true,
                    onBack = { wentBack = true },
                )
            }
        }

        composeRule.onNodeWithText("Назад")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertTrue(wentBack) }
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
        var openedOnline: RemoteFriendPlayStyle? = null
        var openedCodeLength: Int? = null
        setContent {
            var screenState by remember { mutableStateOf(HomeScreenState.ROOT) }
            HomeRootScreen(
                screenState = screenState,
                onScreenStateChange = { screenState = it },
                onOpenOnlineMatch = { playStyle, codeLength ->
                    openedOnline = playStyle
                    openedCodeLength = codeLength
                },
                onlineAvailable = true,
            )
        }

        composeRule.onNodeWithText("Дуэль").performClick()
        composeRule.onNodeWithText("+").performClick()
        composeRule.onNodeWithText("Онлайн‑матч").performClick()

        composeRule.runOnIdle {
            assertEquals(RemoteFriendPlayStyle.TURN_BASED, openedOnline)
            assertEquals(7, openedCodeLength)
        }
    }

    @Test
    fun raceModeChoiceRoutesOnlineMatchWithRaceStyle() {
        var openedOnline: RemoteFriendPlayStyle? = null
        var openedCodeLength: Int? = null
        setContent {
            var screenState by remember { mutableStateOf(HomeScreenState.ROOT) }
            HomeRootScreen(
                screenState = screenState,
                onScreenStateChange = { screenState = it },
                onOpenOnlineMatch = { playStyle, codeLength ->
                    openedOnline = playStyle
                    openedCodeLength = codeLength
                },
                onlineAvailable = true,
            )
        }

        composeRule.onNodeWithText("Гонка").performClick()
        composeRule.onNodeWithText("+").performClick()
        composeRule.onNodeWithText("Онлайн‑матч").performClick()

        composeRule.runOnIdle {
            assertEquals(RemoteFriendPlayStyle.RACE, openedOnline)
            assertEquals(7, openedCodeLength)
        }
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
        composeRule.onNodeWithText("Онлайн‑матч").assertIsNotEnabled()
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
    fun compactHeightPortraitCompanyKeepsChapterRewardAccessible() {
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

    private fun showFriendsReference(
        friends: List<LocalSocialRelationship> = emptyList(),
        onlineConfigured: Boolean = false,
        onOpenFriends: () -> Unit = {},
        onInvite: () -> Unit = {},
        onFindMatch: () -> Unit = {},
    ) {
        setContent {
            FriendsReferenceScreen(
                friends = friends,
                incomingFriendRequests = emptyList(),
                onlineConfigured = onlineConfigured,
                onOpenFriends = onOpenFriends,
                onInvite = onInvite,
                onFindMatch = onFindMatch,
                onAcceptFriendRequest = { MirkoriFriendOperationResult.Unavailable },
            )
        }
    }

    private fun assertNoUntranslatedSocialKeys() {
        composeRule.onAllNodes(
            SemanticsMatcher("Untranslated social localization key") { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any {
                    it.text.startsWith("social.")
                }
            },
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    private fun referenceFriend(id: String, name: String) = LocalSocialRelationship(
        id = "reference-relationship-$id",
        playerId = "reference-owner",
        targetPlayerId = id,
        targetDisplayName = name,
        relationshipType = LocalRelationshipType.FRIEND,
        status = LocalRelationshipStatus.ACTIVE,
        source = "instrumented-reference-fixture",
    )

    private fun referenceFriendRequest() = MirkoriFriendRequest(
        requestId = "reference-request",
        player = MirkoriPublicPlayerProfile(
            gamePlayerId = "reference-request-player",
            handle = "reference_player",
            displayName = "Player 8b73615b",
            avatarUrl = null,
        ),
    )

    private fun setContent(
        language: AppLanguage = AppLanguage.RU,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppStrings provides StaticLocalizationProvider.forLanguage(language),
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

private object InstrumentedAccessTokenProvider : AccessTokenProvider {
    override suspend fun currentAccessToken(): AccessToken = AccessToken.from("instrumented-token")

    override suspend fun refreshAccessToken(rejectedToken: AccessToken): AccessToken? = null
}
