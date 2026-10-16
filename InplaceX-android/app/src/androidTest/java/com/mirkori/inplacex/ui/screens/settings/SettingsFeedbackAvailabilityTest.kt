package com.mirkori.inplacex.ui.screens.settings

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.mirkori.inplacex.platform.ads.AdConsentDecision
import com.mirkori.inplacex.platform.feedback.AppFeedbackSettings
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.platform.web.MirkoriWebsitePage
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class SettingsFeedbackAvailabilityTest {
    private val foregroundHandshake = TestRule { base, _: Description ->
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "A09_WAIT_FOREGROUND\\n")
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
    fun russianMusicOffRemainsDisabledAndOtherFeedbackTogglesIndependently() {
        assertUnavailableMusic(AppLanguage.RU, savedMusic = false)
    }

    @Test
    fun russianMusicOnRemainsDisabledAndOtherFeedbackTogglesIndependently() {
        assertUnavailableMusic(AppLanguage.RU, savedMusic = true)
    }

    @Test
    fun englishMusicOffRemainsDisabledAndOtherFeedbackTogglesIndependently() {
        assertUnavailableMusic(AppLanguage.EN, savedMusic = false)
    }

    @Test
    fun englishMusicOnRemainsDisabledAndOtherFeedbackTogglesIndependently() {
        assertUnavailableMusic(AppLanguage.EN, savedMusic = true)
    }

    private fun assertUnavailableMusic(language: AppLanguage, savedMusic: Boolean) {
        val explanation = when (language) {
            AppLanguage.RU -> "Музыка пока недоступна."
            AppLanguage.EN -> "Music is not available yet."
        }
        val music = mutableStateOf(savedMusic)
        val sound = mutableStateOf(true)
        val vibration = mutableStateOf(true)
        val musicCalls = mutableListOf<Boolean>()
        val soundCalls = mutableListOf<Boolean>()
        val vibrationCalls = mutableListOf<Boolean>()

        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppStrings provides StaticLocalizationProvider.forLanguage(language)
            ) {
                InplaceXTheme {
                    SettingsRootScreen(
                        currentLanguage = language,
                        adConsentDecision = AdConsentDecision.UNDECIDED,
                        feedbackSettings = AppFeedbackSettings(
                            vibrationEnabled = vibration.value,
                            soundEnabled = sound.value,
                            musicEnabled = music.value,
                        ),
                        onLanguageChange = {},
                        onVibrationChange = {
                            vibrationCalls += it
                            vibration.value = it
                        },
                        onSoundChange = {
                            soundCalls += it
                            sound.value = it
                        },
                        onMusicChange = {
                            musicCalls += it
                            music.value = it
                        },
                        onOpenAdPrivacy = {},
                        onOpenWebsitePage = { _: MirkoriWebsitePage -> },
                        onOpenInternalTools = {},
                        onClose = {},
                    )
                }
            }
        }

        val row = composeRule.onNodeWithTag("settings.music.row")
        row.performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(explanation).assertExists()
        row.assertIsNotEnabled()
        val musicSwitch = composeRule.onNodeWithTag("settings.music.switch", useUnmergedTree = true)
        musicSwitch.assertIsNotEnabled()
        if (savedMusic) musicSwitch.assertIsOn() else musicSwitch.assertIsOff()

        row.performTouchInput { click() }
        musicSwitch.performTouchInput { click() }
        composeRule.waitForIdle()
        assertTrue("Disabled music controls must not invoke the callback", musicCalls.isEmpty())
        assertEquals("Saved music preference must remain unchanged", savedMusic, music.value)
        if (savedMusic) musicSwitch.assertIsOn() else musicSwitch.assertIsOff()

        val soundLabel = if (language == AppLanguage.RU) "Звуки" else "Sound effects"
        val vibrationLabel = if (language == AppLanguage.RU) "Вибрация" else "Vibration"
        composeRule.onNodeWithText(soundLabel).performScrollTo().performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(vibrationLabel).performScrollTo().performTouchInput { click() }
        composeRule.waitForIdle()

        assertEquals(listOf(false), soundCalls)
        assertEquals(listOf(false), vibrationCalls)
        assertEquals(false, sound.value)
        assertEquals(false, vibration.value)
        assertTrue(musicCalls.isEmpty())
        assertEquals(savedMusic, music.value)
    }
}
