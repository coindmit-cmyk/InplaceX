package com.mirkori.inplacex.ui.screens.shell

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
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
    fun illustratedHudBackKeepsA48DpTouchTarget() {
        val strings = StaticLocalizationProvider.forLanguage(AppLanguage.RU)
        var backClicked = false
        setContent {
            Box(Modifier.requiredWidth(374.dp).requiredHeight(78.dp)) {
                AppTopBar(
                    energy = 5,
                    energyMax = 5,
                    coins = 10146,
                    showBack = true,
                    showShop = true,
                    onBackClick = { backClicked = true },
                    onShopClick = {},
                    onSettingsClick = {},
                    illustratedReference = true,
                )
            }
        }

        val backNode = composeRule.onNodeWithContentDescription(strings.text("top.back"))
            .assertIsDisplayed()
            .assertHasClickAction()
        val bounds = backNode.fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        assertTrue(bounds.width >= 48.dp.value * density)
        assertTrue(bounds.height >= 48.dp.value * density)
        backNode.performClick()
        composeRule.runOnIdle { assertTrue(backClicked) }
    }

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
        composeRule.onNodeWithText("Mirkori Bot").performScrollTo().assertIsDisplayed()
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
        composeRule.onNodeWithTag("social-friends-reference-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("social-friends-add").assertIsDisplayed()
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
        composeRule.onNodeWithText("Friendly Player").performScrollTo().assertIsDisplayed()
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
        composeRule.onNodeWithText("Pending Player").performScrollTo().assertIsDisplayed()
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
        composeRule.onNodeWithTag("social-friends-add").performClick()
        composeRule.onNodeWithTag("social-friend-search-query").performTextInput("self_player")
        composeRule.onNodeWithTag("social-friend-search-submit").performClick()

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
            composeRule.onNodeWithText("Mirkori Bot").performScrollTo().assertIsDisplayed()
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
    fun illustratedShellKeepsQuickMatchOpenAndSystemBackReturnsToFriendsRoot() {
        val runtime = requireNotNull(
            OnlineRuntime.createOrNull(
                context = composeRule.activity,
                accessTokenProvider = InstrumentedAccessTokenProvider,
                baseUrl = "http://127.0.0.1:65535",
                allowCleartextLoopback = true,
            ),
        )
        val strings = StaticLocalizationProvider.forLanguage(AppLanguage.RU)
        val nestedScreen = mutableStateOf(false)
        val activeSessionChanges = mutableListOf<String?>()
        try {
            setContent {
                AppShell(
                    currentSection = AppSection.SOCIAL,
                    onSectionChange = {},
                    bottomMode = BottomLayerMode.MENU,
                    topMode = TopLayerMode.OVERLAY,
                    centerMode = CenterLayerMode.TRANSPARENT,
                    illustratedReference = !nestedScreen.value,
                    topContent = {
                        AppTopBar(
                            energy = 5,
                            energyMax = 5,
                            coins = 10146,
                            showBack = nestedScreen.value,
                            showShop = true,
                            onBackClick = {},
                            onShopClick = {},
                            onSettingsClick = {},
                            illustratedReference = !nestedScreen.value,
                        )
                    },
                ) {
                    SocialRootScreen(
                        onlineRuntime = runtime,
                        onActiveSessionChange = { activeSessionChanges += it },
                        onNestedScreenChange = { nestedScreen.value = it },
                    )
                }
            }

            composeRule.onNodeWithTag("friends-find-match").assertIsDisplayed().performClick()
            composeRule.onNodeWithText(strings.text("social.match.title")).assertIsDisplayed()
            composeRule.onNodeWithText(strings.text("social.online.find_opponent"))
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.runOnIdle { assertTrue(nestedScreen.value) }

            composeRule.runOnIdle {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.onNodeWithTag("friends-reference-screen").assertIsDisplayed()
            composeRule.onNodeWithTag("friends-find-match").assertIsDisplayed()
            composeRule.runOnIdle {
                assertFalse(nestedScreen.value)
                assertFalse(composeRule.activity.isFinishing)
                assertEquals(1, activeSessionChanges.size)
                assertEquals(null, activeSessionChanges.single())
            }
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
            composeRule.onNodeWithText("Создать код").assertIsDisplayed()
            composeRule.onNodeWithText("Войти по коду").assertIsDisplayed()
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
                        illustratedReference = true,
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
                                illustratedReference = true,
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
                illustratedReference = true,
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
                        illustratedReference = true,
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
        val inviteBounds = composeRule.onNodeWithTag("friends-invite").fetchSemanticsNode().boundsInRoot
        val matchBounds = composeRule.onNodeWithTag("friends-find-match").fetchSemanticsNode().boundsInRoot
        assertEquals(inviteBounds.width, matchBounds.width, 1.5f)
        assertEquals(inviteBounds.height, matchBounds.height, 0.5f)
        val output = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "visual-qa/friends-reference-v10.png",
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
    fun illustratedReferencePagesCaptureReferenceLayoutAt320Dp() {
        val (section) = setReferencePagesContent(
            fontScale = 1f,
            initialSection = AppSection.SOCIAL,
        )

        composeRule.onNodeWithTag("friends-reference-shell").assertIsDisplayed()
        composeRule.onNodeWithTag("friends-reference-screen").assertIsDisplayed()
        captureReferenceShell("reference-v10-friends-320dp.png")

        composeRule.runOnIdle { section.value = AppSection.COMPANY }
        composeRule.onNodeWithTag("company-reference-screen").assertIsDisplayed()
        captureReferenceShell("reference-v10-company-320dp.png")

        composeRule.runOnIdle { section.value = AppSection.SHOP }
        composeRule.onNodeWithTag("shop-reference-screen").assertIsDisplayed()
        captureReferenceShell("reference-v10-shop-320dp.png")

        composeRule.runOnIdle { section.value = AppSection.PROFILE }
        composeRule.onNodeWithTag("profile-reference-screen").assertIsDisplayed()
        captureReferenceShell("reference-v10-profile-320dp.png")
    }

    @Test
    fun illustratedReferencePagesCaptureFullDeviceShell() {
        val (section) = setReferencePagesContent(
            fontScale = 1f,
            constrainedTo320Dp = false,
            initialSection = AppSection.SOCIAL,
        )

        composeRule.onNodeWithTag("friends-reference-screen").assertIsDisplayed()
        captureReferenceShell("reference-v10-friends-device.png")
        composeRule.runOnIdle { section.value = AppSection.COMPANY }
        composeRule.onNodeWithTag("company-reference-screen").assertIsDisplayed()
        captureReferenceShell("reference-v10-company-device.png")
        composeRule.runOnIdle { section.value = AppSection.SHOP }
        composeRule.onNodeWithTag("shop-reference-screen").assertIsDisplayed()
        captureReferenceShell("reference-v10-shop-device.png")
        composeRule.runOnIdle { section.value = AppSection.PROFILE }
        composeRule.onNodeWithTag("profile-reference-screen").assertIsDisplayed()
        captureReferenceShell("reference-v10-profile-device.png")
    }

    @Test
    fun illustratedReferenceExactCanvasKeepsTaggedPageRootsAndCompanyOrder() {
        val (section) = setReferencePagesContent(
            fontScale = 1f,
            canvasWidthDp = 374,
            canvasHeightDp = 877,
            requireCanvasSize = true,
            initialSection = AppSection.SOCIAL,
        )

        composeRule.waitForIdle()
        val shellBounds = composeRule.onNodeWithTag("friends-reference-shell")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val deviceDensity = composeRule.activity.resources.displayMetrics.density
        assertEquals(374f, shellBounds.width / deviceDensity, 0.5f)
        assertEquals(877f, shellBounds.height / deviceDensity, 0.5f)
        assertEquals(
            "The exact reference canvas must keep the 374x877 aspect ratio",
            374f / 877f,
            shellBounds.width / shellBounds.height,
            0.002f,
        )
        val topSlotBounds = composeRule.onNodeWithTag("reference-top-slot")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val bottomBarBounds = composeRule.onNodeWithTag("reference-bottom-bar")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertEquals(shellBounds.top, topSlotBounds.top, 0.5f)
        assertEquals(shellBounds.width, topSlotBounds.width, 0.5f)
        assertEquals(78f / 877f, topSlotBounds.height / shellBounds.height, 0.002f)
        assertEquals(
            775f / 877f,
            (bottomBarBounds.top - shellBounds.top) / shellBounds.height,
            0.002f,
        )
        assertEquals(360f / 374f, bottomBarBounds.width / shellBounds.width, 0.002f)
        assertEquals(88f / 877f, bottomBarBounds.height / shellBounds.height, 0.002f)

        val friendsBounds = composeRule.onNodeWithTag("friends-reference-screen")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            4f / 877f,
            (friendsBounds.top - topSlotBounds.bottom) / shellBounds.height,
            0.002f,
        )
        assertEquals(
            4f / 877f,
            (bottomBarBounds.top - friendsBounds.bottom) / shellBounds.height,
            0.002f,
        )
        val inviteBounds = composeRule.onNodeWithTag("friends-invite")
            .assertIsDisplayed()
            .assertHasClickAction()
            .fetchSemanticsNode().boundsInRoot
        val matchBounds = composeRule.onNodeWithTag("friends-find-match")
            .assertIsDisplayed()
            .assertHasClickAction()
            .fetchSemanticsNode().boundsInRoot
        assertEquals(inviteBounds.width, matchBounds.width, 1.5f)
        assertEquals(inviteBounds.height, matchBounds.height, 0.5f)

        composeRule.runOnIdle { section.value = AppSection.COMPANY }
        composeRule.waitForIdle()

        val companyBounds = composeRule.onNodeWithTag("company-reference-screen")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val companyRewardBounds = composeRule.onNodeWithTag("company-retention-rewards")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val companyHeroBounds = composeRule.onNodeWithTag("company-hero")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val companyChapterBounds = composeRule.onNodeWithTag("company-chapter-card")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val companyChapterRewardBounds = composeRule.onNodeWithTag("company-chapter-reward")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val companyMapBounds = composeRule.onNodeWithTag("company-forest-map")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val companyLevelBounds = composeRule.onNodeWithTag("company-level-card")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val companyPlayBounds = composeRule.onNodeWithTag("company-play")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Company rewards must stay above the campaign map",
            companyRewardBounds.bottom <= companyMapBounds.top,
        )
        assertTrue(
            "The campaign map must stay above the primary play action",
            companyMapBounds.bottom <= companyPlayBounds.top,
        )
        assertTrue(
            "Company hero must stay above the chapter card",
            companyHeroBounds.bottom <= companyChapterBounds.top,
        )
        assertTrue(
            "The chapter card must stay above the campaign map",
            companyChapterBounds.bottom <= companyMapBounds.top,
        )
        assertTrue(
            "The chapter reward must visibly protrude below the chapter card",
            companyChapterRewardBounds.bottom > companyChapterBounds.bottom,
        )
        assertTrue(
            "The level card must retain the reference overlap with the campaign map",
            companyLevelBounds.top < companyMapBounds.bottom,
        )
        composeRule.onNodeWithTag("company-level-label-2", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("company-level-label-3", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("company-level-label-4", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("company-level-lock-5", useUnmergedTree = true).assertIsDisplayed()

        composeRule.runOnIdle { section.value = AppSection.SHOP }
        composeRule.waitForIdle()
        val shopBounds = composeRule.onNodeWithTag("shop-reference-screen")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertEquals(companyBounds.left, shopBounds.left, 0.5f)
        assertEquals(companyBounds.top, shopBounds.top, 0.5f)
        assertEquals(companyBounds.width, shopBounds.width, 0.5f)
        assertEquals(companyBounds.height, shopBounds.height, 0.5f)
        val shopHeroBounds = composeRule.onNodeWithTag("shop-hero")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val shopRewardBounds = composeRule.onNodeWithTag("shop-reward")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val shopTabsBounds = composeRule.onNodeWithTag("shop-tabs")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val shopSectionBounds = composeRule.onNodeWithTag("shop-section")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val shopItemBounds = (0..3).map { index ->
            composeRule.onNodeWithTag("shop-item-$index")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        assertTrue(shopHeroBounds.bottom <= shopRewardBounds.top)
        assertTrue(shopRewardBounds.bottom <= shopTabsBounds.top)
        assertTrue(shopTabsBounds.bottom <= shopSectionBounds.top)
        assertTrue(shopSectionBounds.bottom <= shopItemBounds[0].top)
        assertEquals(shopItemBounds[0].top, shopItemBounds[1].top, 0.5f)
        assertEquals(shopItemBounds[0].width, shopItemBounds[1].width, 1.1f)
        assertEquals(shopItemBounds[0].height, shopItemBounds[1].height, 0.5f)
        assertEquals(shopItemBounds[2].top, shopItemBounds[3].top, 0.5f)
        assertEquals(shopItemBounds[2].width, shopItemBounds[3].width, 1.1f)
        assertEquals(shopItemBounds[2].height, shopItemBounds[3].height, 0.5f)
        assertTrue(shopItemBounds[0].bottom < shopItemBounds[2].top)
        composeRule.onNodeWithTag("shop-item-4").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("shop-item-5").performScrollTo().assertIsDisplayed()

        composeRule.runOnIdle { section.value = AppSection.PROFILE }
        composeRule.waitForIdle()
        val profileBounds = composeRule.onNodeWithTag("profile-reference-screen")
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertEquals(companyBounds.left, profileBounds.left, 0.5f)
        assertEquals(companyBounds.top, profileBounds.top, 0.5f)
        assertEquals(companyBounds.width, profileBounds.width, 0.5f)
        assertEquals(companyBounds.height, profileBounds.height, 0.5f)
        val profileHeroBounds = composeRule.onNodeWithTag("profile-hero")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val profileConnectionsBounds = composeRule.onNodeWithTag("profile-connections")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val profileOverviewBounds = composeRule.onNodeWithTag("profile-overview")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val profileStatBounds = (0..3).map { index ->
            composeRule.onNodeWithTag("profile-stat-$index")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        val profilePurchasesBounds = composeRule.onNodeWithTag("profile-purchases")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(profileHeroBounds.bottom <= profileConnectionsBounds.top)
        assertTrue(profileConnectionsBounds.bottom <= profileOverviewBounds.top)
        assertTrue(profileOverviewBounds.bottom <= profileStatBounds[0].top)
        assertEquals(profileStatBounds[0].top, profileStatBounds[1].top, 0.5f)
        assertEquals(profileStatBounds[0].width, profileStatBounds[1].width, 1.1f)
        assertEquals(profileStatBounds[0].height, profileStatBounds[1].height, 0.5f)
        assertEquals(profileStatBounds[2].top, profileStatBounds[3].top, 0.5f)
        assertEquals(profileStatBounds[2].width, profileStatBounds[3].width, 1.1f)
        assertEquals(profileStatBounds[2].height, profileStatBounds[3].height, 0.5f)
        assertTrue(profileStatBounds[2].bottom <= profilePurchasesBounds.top)
        composeRule.onNodeWithTag("profile-premium")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithTag("shop-reference-screen").assertIsDisplayed()
    }

    @Test
    fun illustratedReferencePagesKeepPrimaryActionsReachableAt320DpLargeText() {
        val (section, actions) = setReferencePagesContent(
            fontScale = 1.5f,
            canvasHeightDp = 568,
        )
        val strings = StaticLocalizationProvider.forLanguage(AppLanguage.RU)

        composeRule.onNodeWithTag("friends-reference-shell").assertIsDisplayed()
        composeRule.onNodeWithTag("company-reference-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("company-play").assertIsDisplayed().performClick()

        composeRule.runOnIdle { section.value = AppSection.SHOP }
        composeRule.onNodeWithTag("shop-reference-screen").assertIsDisplayed()
        composeRule.onNodeWithText(strings.text("shop.rewarded.watch"))
            .performScrollTo().assertIsDisplayed().performClick()

        composeRule.runOnIdle { section.value = AppSection.PROFILE }
        composeRule.onNodeWithTag("profile-reference-screen").assertIsDisplayed()
        composeRule.onNodeWithText(strings.text("profile.mirkori.handle.change"))
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.onAllNodesWithText(strings.text("profile.mirkori.handle.change"))
            .assertCountEquals(2)
        composeRule.onNodeWithText(strings.text("profile.mirkori.handle.cancel")).performClick()

        composeRule.runOnIdle {
            assertTrue(actions.any { it.startsWith("company:") })
            assertTrue("rewarded" in actions)
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
        composeRule.onNodeWithTag("shop-premium-overview").assertIsDisplayed()
        composeRule.onNodeWithTag("shop-premium-products").assertIsDisplayed()
        composeRule.onNodeWithText("PRO на 1 час").assertExists()
        composeRule.onNodeWithText("Доступ на 01:00:00").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Купить за 60 монет")
            .performScrollTo().assertIsDisplayed().performClick()
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
    fun linkedGoogleAccountExposesDeviceSignOutInsideConnections() {
        var signOutRequested = false
        setContent {
            ProfileRootScreen(
                progressState = progress().copy(googlePlaySignedIn = true),
                mirkoriAccountState = MirkoriAccountState(
                    kind = MirkoriAccountStateKind.LINKED,
                    gamePlayerId = "00000000-0000-4000-8000-000000000813",
                    authMode = PlatformAuthMode.GOOGLE,
                ),
                showGooglePlayCard = true,
                onGooglePlaySignOut = { signOutRequested = true },
            )
        }

        composeRule.onNodeWithText("Подключения").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Выйти из Google Play").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(signOutRequested) }
    }

    @Test
    fun linkedMirkoriAccountConfirmsDeviceSignOut() {
        var signOutRequested = false
        setContent {
            ProfileRootScreen(
                progressState = progress(),
                mirkoriAccountState = MirkoriAccountState(
                    kind = MirkoriAccountStateKind.LINKED,
                    gamePlayerId = "00000000-0000-4000-8000-000000000815",
                    authMode = PlatformAuthMode.LOCAL,
                ),
                onMirkoriSignOut = { signOutRequested = true },
            )
        }

        composeRule.onNodeWithText("Выйти из Mirkori Games").performScrollTo().performClick()
        composeRule.onNodeWithText("Выйти из Mirkori Games?").assertIsDisplayed()
        composeRule.onNodeWithText("Выйти").performClick()
        composeRule.runOnIdle { assertTrue(signOutRequested) }
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
    fun linkedTelegramAccountOffersVerifiedGoogleConnection() {
        var signInRequested = false
        setContent {
            ProfileRootScreen(
                progressState = progress().copy(googlePlaySignedIn = false),
                mirkoriAccountState = MirkoriAccountState(
                    kind = MirkoriAccountStateKind.LINKED,
                    gamePlayerId = "00000000-0000-4000-8000-000000000804",
                    authMode = PlatformAuthMode.TELEGRAM,
                ),
                showGooglePlayCard = true,
                onGooglePlaySignIn = { signInRequested = true },
            )
        }

        composeRule.onNodeWithText("Войти через Google Play").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(signInRequested) }
    }

    @Test
    fun googlePlaySignInActionRemainsAvailableAfterMirkoriAccountLinking() {
        val accountState = mutableStateOf(
            MirkoriAccountState(
                kind = MirkoriAccountStateKind.GUEST,
                gamePlayerId = "00000000-0000-4000-8000-000000000806",
                authMode = PlatformAuthMode.GUEST,
            ),
        )
        var signInRequests = 0
        setContent {
            ProfileRootScreen(
                progressState = progress().copy(googlePlaySignedIn = false),
                mirkoriAccountState = accountState.value,
                showGooglePlayCard = true,
                onGooglePlaySignIn = { signInRequests += 1 },
            )
        }

        composeRule.onNodeWithText("Войти через Google Play")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, signInRequests)
            accountState.value = MirkoriAccountState(
                kind = MirkoriAccountStateKind.LINKED,
                gamePlayerId = "00000000-0000-4000-8000-000000000807",
                authMode = PlatformAuthMode.TELEGRAM,
            )
        }

        composeRule.onNodeWithText("Google Play").assertIsDisplayed()
        composeRule.onNodeWithText("Войти через Google Play").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(2, signInRequests) }
    }

    @Test
    fun profileAvatarDialogOffersIllustratedPresetsAndLocalPhotoUpload() {
        setContent {
            ProfileRootScreen(
                progressState = progress(),
                mirkoriAccountState = MirkoriAccountState(
                    kind = MirkoriAccountStateKind.LINKED,
                    gamePlayerId = "00000000-0000-4000-8000-000000000814",
                    authMode = PlatformAuthMode.LOCAL,
                ),
            )
        }

        composeRule.onNodeWithTag("profile-avatar").performClick()
        composeRule.onNodeWithTag("profile-avatar-preset-rocket").assertIsDisplayed()
        composeRule.onNodeWithTag("profile-avatar-preset-robot").assertIsDisplayed()
        composeRule.onNodeWithTag("profile-avatar-upload").assertIsDisplayed()
        composeRule.onNodeWithText("Своя фотография хранится только на этом устройстве.")
            .assertIsDisplayed()
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
        composeRule.onNodeWithText("Быстрая гонка").performClick()

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

    private fun setReferencePagesContent(
        fontScale: Float,
        constrainedTo320Dp: Boolean = true,
        canvasWidthDp: Int = 320,
        canvasHeightDp: Int = 600,
        requireCanvasSize: Boolean = false,
        initialSection: AppSection = AppSection.COMPANY,
    ): Pair<MutableState<AppSection>, MutableList<String>> {
        val section = mutableStateOf(initialSection)
        val actions = mutableListOf<String>()
        val referenceFriends = listOf(
            "Mirki", "Lina", "Alexey", "Kate",
            "Friend 5", "Friend 6", "Friend 7", "Friend 8",
        )
            .mapIndexed { index, name -> referenceFriend(index.toString(), name) }
        val referenceRequest = referenceFriendRequest()
        val referenceProgress = progress(coins = 10146).copy(
            openPositionHints = 1,
            checkDigitHints = 2,
            checkPositionHints = 1,
            matchesPlayed = 9,
            highestUnlockedCampaignLevel = 2,
            totalCampaignRating = 6,
        )
        setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                val containerModifier = if (constrainedTo320Dp) {
                    if (requireCanvasSize) {
                        Modifier.requiredWidth(canvasWidthDp.dp).requiredHeight(canvasHeightDp.dp)
                    } else {
                        Modifier.width(canvasWidthDp.dp).height(canvasHeightDp.dp)
                    }
                } else {
                    Modifier.fillMaxSize()
                }
                Box(containerModifier) {
                    AppShell(
                        currentSection = section.value,
                        onSectionChange = { section.value = it },
                        socialNotificationCount = 1,
                        bottomMode = BottomLayerMode.MENU,
                        topMode = TopLayerMode.OVERLAY,
                        centerMode = CenterLayerMode.TRANSPARENT,
                        illustratedReference = true,
                        topContent = {
                            AppTopBar(
                                energy = 5,
                                energyMax = 5,
                                coins = 10146,
                                showBack = false,
                                showShop = true,
                                onBackClick = {},
                                onShopClick = { section.value = AppSection.SHOP },
                                onSettingsClick = {},
                                illustratedReference = true,
                            )
                        },
                    ) {
                        when (section.value) {
                            AppSection.SOCIAL -> FriendsReferenceScreen(
                                friends = referenceFriends,
                                incomingFriendRequests = listOf(referenceRequest),
                                onlineConfigured = true,
                                onlineFriendIds = referenceFriends.map { it.targetPlayerId }.toSet(),
                                onOpenFriends = { actions += "friends" },
                                onInvite = { actions += "invite" },
                                onFindMatch = { actions += "match" },
                                onAcceptFriendRequest = { MirkoriFriendOperationResult.Success(it) },
                            )
                            AppSection.COMPANY -> CompanyRootScreen(
                                progressState = referenceProgress.copy(campaignTutorialCompleted = true),
                                campaignProgress = listOf(CampaignLevelProgress(1, 8)),
                                activeLevelNumber = null,
                                onActiveLevelNumberChange = { actions += "company:$it" },
                            )
                            AppSection.SHOP -> ShopRootScreen(
                                progressState = referenceProgress,
                                onWatchRewardedCoins = { completed ->
                                    actions += "rewarded"
                                    completed(true)
                                },
                                onBuyOpenPositionHint = { true },
                                onBuyCheckDigitHint = { true },
                                onBuyCheckPositionHint = { true },
                                onBuyExtraMovesBoost = { true },
                                onBuyExtraTimeBoost = { true },
                                onBuyEnergy = { true },
                                onBuyRemoveAds = {},
                                onBuyPro = {},
                                onBuyProPlus = {},
                            )
                            AppSection.PROFILE -> ProfileRootScreen(
                                progressState = referenceProgress,
                                mirkoriAccountState = MirkoriAccountState(
                                    kind = MirkoriAccountStateKind.LINKED,
                                    gamePlayerId = "00000000-0000-4000-8000-000000007065",
                                    authMode = PlatformAuthMode.GOOGLE,
                                ),
                                publicPlayerProfile = MirkoriPublicPlayerProfile(
                                    gamePlayerId = "00000000-0000-4000-8000-000000007065",
                                    handle = "test3",
                                    displayName = "Player_7065",
                                    avatarUrl = null,
                                ),
                                showGooglePlayCard = true,
                                onOpenShop = {
                                    actions += "profile-premium"
                                    section.value = AppSection.SHOP
                                },
                            )
                            else -> Text("unused")
                        }
                    }
                }
            }
        }
        return section to actions
    }

    private fun captureReferenceShell(fileName: String) {
        composeRule.waitForIdle()
        val output = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "visual-qa/$fileName",
        )
        output.parentFile?.mkdirs()
        output.outputStream().use { stream ->
            check(
                composeRule.onNodeWithTag("friends-reference-shell")
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, stream),
            )
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
