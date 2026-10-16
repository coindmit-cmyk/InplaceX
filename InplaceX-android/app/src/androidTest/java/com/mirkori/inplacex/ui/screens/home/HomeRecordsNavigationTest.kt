package com.mirkori.inplacex.ui.screens.home

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.mirkori.inplacex.data.local.ModeStats
import com.mirkori.inplacex.platform.config.AppConfigCatalog
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class HomeRecordsNavigationTest {
    private val foregroundHandshake = TestRule { base, _: Description ->
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "A11_WAIT_FOREGROUND\\n")
                })
                val deadline = SystemClock.elapsedRealtime() + 25_000L
                var mainResumed = false
                while (SystemClock.elapsedRealtime() < deadline && !mainResumed) {
                    instrumentation.runOnMainSync {
                        mainResumed = ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED)
                            .any { it is com.mirkori.inplacex.MainActivity }
                    }
                    if (!mainResumed) Thread.sleep(100)
                }
                assertTrue("Host must foreground ordinary MainActivity", mainResumed)
                base.evaluate()
            }
        }
    }

    private val composeActivityRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules = RuleChain.outerRule(foregroundHandshake).around(composeActivityRule)

    private val composeRule get() = composeActivityRule

    @Test
    fun russianEmpty() = verify(AppLanguage.RU, empty = true)

    @Test
    fun englishEmpty() = verify(AppLanguage.EN, empty = true)

    @Test
    fun russianPopulated() = verify(AppLanguage.RU, empty = false)

    @Test
    fun englishPopulated() = verify(AppLanguage.EN, empty = false)

    private fun verify(language: AppLanguage, empty: Boolean) {
        val state = mutableStateOf(HomeScreenState.ROOT)
        val exitRequest = mutableStateOf(false)
        var exitAcknowledgements = 0
        val started = mutableListOf<String>()
        val pveResults = mutableListOf<Boolean>()
        val pvpResults = mutableListOf<Boolean>()
        val pve = if (empty) ModeStats(0, 0) else ModeStats(7, 2)
        val pvp = if (empty) ModeStats(0, 0) else ModeStats(3, 5)
        val strings = StaticLocalizationProvider.forLanguage(language)
        val resourceDensity = composeRule.activity.resources.displayMetrics.density
        val raceScope = if (language == AppLanguage.RU) {
            "Только гонки-тренировки против бота на этом устройстве"
        } else "Bot-training races on this device only"
        val duelScope = if (language == AppLanguage.RU) {
            "Дуэли с ботом и все онлайн-матчи, включая гонки, на этом устройстве"
        } else "Bot duels and all online matches, including races, on this device"
        val unavailable = if (language == AppLanguage.RU) {
            "Лучшее время, записи о попытках и глобальная таблица лидеров недоступны."
        } else "Best times, attempt records, and the global leaderboard are unavailable."
        val emptyMessage = if (language == AppLanguage.RU) {
            "Нет сохранённых побед или поражений."
        } else "No saved wins or losses."
        val wins = if (language == AppLanguage.RU) listOf("Победы: 0", "Победы: 7", "Победы: 3")
            else listOf("Wins: 0", "Wins: 7", "Wins: 3")
        val losses = if (language == AppLanguage.RU) listOf("Поражения: 0", "Поражения: 2", "Поражения: 5")
            else listOf("Losses: 0", "Losses: 2", "Losses: 5")

        composeRule.setContent {
            val originalFontScale = LocalDensity.current.fontScale
            CompositionLocalProvider(
                LocalAppStrings provides strings,
                LocalDensity provides Density(resourceDensity * 0.88f, originalFontScale),
            ) {
                InplaceXTheme {
                    HomeRootScreen(
                        screenState = state.value,
                        onScreenStateChange = { state.value = it },
                        requestExitGame = exitRequest.value,
                        onExitGameConsumed = {
                            exitAcknowledgements++
                            exitRequest.value = false
                        },
                        onMatchStarted = { started += "match" },
                        onRecordPveResult = { pveResults += it },
                        onRecordPvpResult = { pvpResults += it },
                        pveStats = pve,
                        pvpStats = pvp,
                    )
                }
            }
        }

        fun clickTag(tag: String) {
            val node = composeRule.onNodeWithTag(tag)
            node.performScrollTo()
            node.assertIsDisplayed()
            node.assertIsEnabled()
            node.performTouchInput { click() }
            composeRule.waitForIdle()
        }

        fun checkTaggedText(tag: String, expected: String) {
            val node = composeRule.onNodeWithTag(tag)
            node.performScrollTo()
            node.assertIsDisplayed()
            node.assertTextEquals(expected)
        }

        fun checkCodeLength(expected: Int) {
            val node = composeRule.onNodeWithText(strings.homeCodeLength(expected))
            node.performScrollTo()
            node.assertIsDisplayed()
        }

        fun checkRecords(scope: String, expectedWins: String, expectedLosses: String, shouldBeEmpty: Boolean) {
            composeRule.onNodeWithTag("home-records-screen").assertIsDisplayed()
            val backNode = composeRule.onNodeWithTag("home-records-back")
            backNode.performScrollTo()
            backNode.assertIsDisplayed()
            assertTrue(
                "Back target must be at least 48 physical dp high",
                backNode.fetchSemanticsNode().boundsInRoot.height >= 48f * resourceDensity,
            )
            checkTaggedText("home-records-scope", scope)
            checkTaggedText("home-records-wins", expectedWins)
            checkTaggedText("home-records-losses", expectedLosses)
            checkTaggedText("home-records-unavailable", unavailable)
            if (shouldBeEmpty) checkTaggedText("home-records-empty", emptyMessage)
            else composeRule.onNodeWithTag("home-records-empty").assertDoesNotExist()
        }

        fun changeCodeLength(initial: Int): Int {
            val expected = if (initial < 10) initial + 1 else initial - 1
            clickTag(if (expected > initial) "reference-code-plus" else "reference-code-minus")
            checkCodeLength(expected)
            return expected
        }

        fun systemBack() {
            composeRule.runOnUiThread {
                composeActivityRule.activity.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitForIdle()
        }

        fun requestHudBack(expectedState: HomeScreenState) {
            val acknowledgementsBefore = exitAcknowledgements
            composeRule.runOnIdle { exitRequest.value = true }
            composeRule.waitForIdle()
            assertEquals(expectedState, state.value)
            assertEquals(false, exitRequest.value)
            assertEquals(acknowledgementsBefore + 1, exitAcknowledgements)
        }

        fun checkNoGameCallbacks() {
            assertTrue("Navigation/reading must not start matches", started.isEmpty())
            assertTrue("Navigation/reading must not record PvE results", pveResults.isEmpty())
            assertTrue("Navigation/reading must not record PvP results", pvpResults.isEmpty())
        }

        val raceInitial = AppConfigCatalog.gameModes.first { it.id == "pve_race" }.config.codeLength
        clickTag("reference-home-race")
        assertEquals(HomeScreenState.RACE_MODES, state.value)
        val raceLength = changeCodeLength(raceInitial)
        clickTag("reference-race-records")
        assertEquals(HomeScreenState.RACE_RECORDS, state.value)
        checkRecords(raceScope, wins[if (empty) 0 else 1], losses[if (empty) 0 else 1], empty)
        clickTag("home-records-back")
        assertEquals(HomeScreenState.RACE_MODES, state.value)
        checkCodeLength(raceLength)
        clickTag("reference-race-records")
        checkRecords(raceScope, wins[if (empty) 0 else 1], losses[if (empty) 0 else 1], empty)
        systemBack()
        assertEquals(HomeScreenState.RACE_MODES, state.value)
        checkCodeLength(raceLength)
        clickTag("reference-race-records")
        requestHudBack(HomeScreenState.RACE_MODES)
        checkCodeLength(raceLength)
        assertEquals(1, exitAcknowledgements)
        systemBack()
        assertEquals(HomeScreenState.ROOT, state.value)

        val duelInitial = AppConfigCatalog.gameModes.first { it.id == "pvp_bot_duel" }.config.codeLength
        clickTag("reference-home-duel")
        assertEquals(HomeScreenState.PVP_MODES, state.value)
        val duelLength = changeCodeLength(duelInitial)
        clickTag("reference-duel-records")
        assertEquals(HomeScreenState.PVP_RECORDS, state.value)
        checkRecords(duelScope, wins[if (empty) 0 else 2], losses[if (empty) 0 else 2], empty)
        clickTag("home-records-back")
        assertEquals(HomeScreenState.PVP_MODES, state.value)
        checkCodeLength(duelLength)
        clickTag("reference-duel-records")
        checkRecords(duelScope, wins[if (empty) 0 else 2], losses[if (empty) 0 else 2], empty)
        systemBack()
        assertEquals(HomeScreenState.PVP_MODES, state.value)
        checkCodeLength(duelLength)
        clickTag("reference-duel-records")
        requestHudBack(HomeScreenState.PVP_MODES)
        checkCodeLength(duelLength)
        assertEquals(2, exitAcknowledgements)
        systemBack()
        assertEquals(HomeScreenState.ROOT, state.value)
        checkNoGameCallbacks()
    }
}
