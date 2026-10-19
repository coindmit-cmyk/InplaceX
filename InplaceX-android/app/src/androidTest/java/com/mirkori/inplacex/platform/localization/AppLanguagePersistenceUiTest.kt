package com.mirkori.inplacex.platform.localization

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mirkori.inplacex.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLanguagePersistenceUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun selectedLanguageSurvivesActualActivityRecreation() {
        val store = AppLanguageSettingsStore(composeRule.activity)
        val originalLanguage = store.read()

        try {
            selectLanguage(AppLanguage.EN)
            assertVisibleLanguage(AppLanguage.EN, store)
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()
            assertVisibleLanguage(AppLanguage.EN, store)

            selectLanguage(AppLanguage.RU)
            assertVisibleLanguage(AppLanguage.RU, store)
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()
            assertVisibleLanguage(AppLanguage.RU, store)
        } finally {
            if (store.read() != originalLanguage) {
                selectLanguage(originalLanguage)
            }
            val strings = stringsFor(store.read())
            composeRule.onNodeWithText(strings.text("top.back"), useUnmergedTree = true).performClick()
        }
    }

    private fun assertVisibleLanguage(language: AppLanguage, store: AppLanguageSettingsStore) {
        assertEquals(language, store.read())
        val strings = StaticLocalizationProvider.forLanguage(language)
        composeRule.onNodeWithText(strings.text("settings.title"), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(strings.text("settings.language.${language.name.lowercase()}"), useUnmergedTree = true).assertIsDisplayed()
    }

    private fun selectLanguage(target: AppLanguage) {
        ensureSettingsDialogIsOpen()
        val current = AppLanguageSettingsStore(composeRule.activity).read()
        val currentStrings = StaticLocalizationProvider.forLanguage(current)
        val targetLabel = currentStrings.text("settings.language.${target.name.lowercase()}")

        if (current != target) {
            composeRule.onNodeWithText(currentStrings.text("settings.language.${current.name.lowercase()}"), useUnmergedTree = true).performClick()
            composeRule.onNodeWithText(targetLabel, useUnmergedTree = true).performClick()
            composeRule.waitForIdle()
        }
    }

    private fun ensureSettingsDialogIsOpen() {
        val current = AppLanguageSettingsStore(composeRule.activity).read()
        val strings = StaticLocalizationProvider.forLanguage(current)
        val title = strings.text("settings.title")
        if (composeRule.onAllNodesWithText(title, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithContentDescription(strings.text("top.settings")).performClick()
        }
        composeRule.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun stringsFor(language: AppLanguage) = StaticLocalizationProvider.forLanguage(language)
}
