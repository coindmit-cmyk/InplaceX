package com.mirkori.inplacex.ui.screens.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RaceDifficultySelectorTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun easyIsSelectedAndTheChosenLevelIsUsedWhenStarting() {
        var startedDifficulty: BotDifficulty? = null
        composeRule.setContent {
            var difficulty by remember { mutableStateOf(BotDifficulty.EASY) }
            CompositionLocalProvider(LocalAppStrings provides StaticLocalizationProvider.forLanguage(AppLanguage.RU)) {
                InplaceXTheme {
                    PvpModesScreen(
                        codeLength = 6, onCodeLengthChange = {},
                        onPlayWithBot = { startedDifficulty = difficulty },
                        onPlayOnline = {}, onlineAvailable = true, onBack = {},
                        raceDifficulty = difficulty, onRaceDifficultyChange = { difficulty = it },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Лёгкий").assertIsSelected()
        composeRule.onNodeWithText("Сложный").performScrollTo().performClick().assertIsSelected()
        composeRule.onNodeWithText("С ботом").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(BotDifficulty.HARD, startedDifficulty) }
    }

    @Test
    fun duelDoesNotShowRaceDifficultyControls() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppStrings provides StaticLocalizationProvider.forLanguage(AppLanguage.RU)) {
                InplaceXTheme {
                    PvpModesScreen(6, {}, {}, {}, true, {})
                }
            }
        }
        composeRule.onNodeWithText("Сложность бота").assertDoesNotExist()
    }
}
