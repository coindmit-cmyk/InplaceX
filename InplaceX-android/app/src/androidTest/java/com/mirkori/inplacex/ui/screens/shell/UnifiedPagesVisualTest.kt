package com.mirkori.inplacex.ui.screens.shell

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.data.local.ModeStats
import com.mirkori.inplacex.data.local.LocalSocialRelationship
import com.mirkori.inplacex.data.local.LocalRelationshipType
import com.mirkori.inplacex.data.local.LocalRelationshipStatus
import com.mirkori.inplacex.platform.localization.*
import com.mirkori.inplacex.platform.mirkori.*
import com.mirkori.inplacex.ui.navigation.AppSection
import com.mirkori.inplacex.ui.screens.company.CompanyRootScreen
import com.mirkori.inplacex.ui.screens.home.HomeRootScreen
import com.mirkori.inplacex.ui.screens.home.HomeScreenState
import com.mirkori.inplacex.ui.screens.profile.ProfileRootScreen
import com.mirkori.inplacex.ui.screens.shop.ShopRootScreen
import com.mirkori.inplacex.ui.screens.social.SocialRootScreen
import com.mirkori.inplacex.ui.shell.*
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import com.mirkori.inplacex.R
import com.mirkori.inplacex.ui.background.ScreenBackgroundStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Isolated UI fixtures only: no real account, requests, payments or saved progress. */
class UnifiedPagesVisualTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun russianFivePages() = exercise(AppLanguage.RU, 1f)
    @Test fun englishFivePagesLargeFont() = exercise(AppLanguage.EN, 1.5f)

    private fun exercise(language: AppLanguage, fontScale: Float) {
        val section = mutableStateOf(AppSection.HOME)
        val strings = StaticLocalizationProvider.forLanguage(language)
        val progress = fixture()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalAppStrings provides strings,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                InplaceXTheme {
                    AppShell(
                        currentSection = section.value,
                        onSectionChange = { section.value = it },
                        centerMode = CenterLayerMode.TRANSPARENT,
                        backgroundStyle = ScreenBackgroundStyle.DrawableResource(R.drawable.toy_room_bg_v6, Color(0xFF9C632C)),
                        topMode = TopLayerMode.OVERLAY,
                        topContent = {
                            AppTopBar(5, 5, 10146, false, true, {}, {}, {})
                        },
                    ) {
                        when (section.value) {
                            AppSection.HOME -> HomeRootScreen(
                                screenState = HomeScreenState.ROOT,
                                onScreenStateChange = {},
                            )
                            AppSection.SOCIAL -> SocialRootScreen(
                                friends = listOf("Mirik", "Lina", "Alexey", "Kate").mapIndexed { i, name ->
                                    LocalSocialRelationship(playerId = "visual-player", targetPlayerId = "fixture-$i",
                                        targetDisplayName = name, relationshipType = LocalRelationshipType.FRIEND,
                                        status = LocalRelationshipStatus.ACTIVE)
                                },
                                incomingFriendRequests = listOf(MirkoriFriendRequest(
                                    "visual-request",
                                    MirkoriPublicPlayerProfile("visual-player", "friend", "Friendly Player", null),
                                )),
                            )
                            AppSection.COMPANY -> CompanyRootScreen(
                                progressState = progress, campaignProgress = emptyList(),
                                activeLevelNumber = null, onActiveLevelNumberChange = {},
                            )
                            AppSection.SHOP -> ShopRootScreen(
                                progressState = progress,
                                onWatchRewardedCoins = { it(false) },
                                onBuyOpenPositionHint = { false }, onBuyCheckDigitHint = { false },
                                onBuyCheckPositionHint = { false }, onBuyExtraMovesBoost = { false },
                                onBuyExtraTimeBoost = { false }, onBuyEnergy = { false },
                                onBuyRemoveAds = {}, onBuyPro = {}, onBuyProPlus = {},
                            )
                            AppSection.PROFILE -> ProfileRootScreen(
                                progressState = progress,
                                mirkoriAccountState = MirkoriAccountState(MirkoriAccountStateKind.LINKED, "visual-player"),
                                publicPlayerProfile = MirkoriPublicPlayerProfile("visual-player", "test3", "Player_7065", null),
                                showGooglePlayCard = true,
                            )
                        }
                    }
                }
            }
        }
        for (page in AppSection.entries) {
            composeRule.runOnIdle { section.value = page }
            capture("v7-${language.name.lowercase()}-$fontScale-${page.name.lowercase()}")
            when (page) {
                AppSection.HOME -> composeRule.onNode(hasText(strings.text("home.company.continue")) and hasText(strings.text("home.company.teaser")))
                    .performScrollTo().assertIsDisplayed()
                AppSection.SOCIAL -> {
                    composeRule.onNodeWithTag("friend-requests-open").performScrollTo().performClick()
                    composeRule.onNodeWithTag("friend-request-accept-visual-request").assertIsDisplayed()
                    capture("v7-${language.name.lowercase()}-$fontScale-request")
                    composeRule.onNodeWithText(strings.text("social.friend.search.close")).performClick()
                    composeRule.onNodeWithText(strings.text("social.online.title")).performScrollTo().assertIsDisplayed()
                }
                AppSection.COMPANY -> {
                    composeRule.onNodeWithTag("company-play").assertIsDisplayed()
                    composeRule.onNodeWithTag("company-mission-list").performScrollToNode(hasTestTag("company-level-2"))
                    composeRule.onNodeWithTag("company-level-2").assertIsSelected()
                    composeRule.onNodeWithTag("company-play").assertIsDisplayed()
                }
                AppSection.SHOP -> {
                    composeRule.onNodeWithText(strings.text("shop.item.energy")).performScrollTo().assertIsDisplayed()
                    composeRule.onNodeWithText(strings.text("shop.tab.premium")).performScrollTo().performClick()
                    composeRule.onNodeWithText(strings.text("shop.product.temporary_pro")).performScrollTo().assertIsDisplayed()
                    capture("v7-${language.name.lowercase()}-$fontScale-premium")
                }
                AppSection.PROFILE -> {
                    composeRule.onNodeWithText(strings.text("profile.overview")).performScrollTo().assertIsDisplayed()
                    val left = composeRule.onNodeWithText("2").fetchSemanticsNode().boundsInRoot
                    val right = composeRule.onNodeWithText("6").fetchSemanticsNode().boundsInRoot
                    assertEquals("Stat values must share a baseline", left.top, right.top, 1f)
                    capture("v7-${language.name.lowercase()}-$fontScale-stats")
                    composeRule.onNodeWithText(strings.text("profile.membership")).performScrollTo().assertIsDisplayed()
                }
            }
        }
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun fixture() = GameProgressState(
        playerDisplayName = "Player_7065", googlePlaySignedIn = false,
        openPositionHints = 1, checkDigitHints = 2, checkPositionHints = 3,
        extraMovesBoosts = 1, extraTimeBoosts = 1, coins = 10146,
        campaignEnergy = 5, campaignEnergyMax = 5, campaignEnergyRefillMinutes = 30,
        matchesPlayed = 9, matchesWon = 4, highestUnlockedCampaignLevel = 2, totalCampaignRating = 6,
        pveStats = ModeStats(2, 2), pvpStats = ModeStats(2, 2), companyStats = ModeStats(0, 1),
        adFreePurchased = false, proSubscriptionActive = false, proPlusSubscriptionActive = false,
        temporaryProExpiresAtMs = 0,
    )
}
