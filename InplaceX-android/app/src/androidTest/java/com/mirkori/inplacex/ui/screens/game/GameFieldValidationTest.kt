package com.mirkori.inplacex.ui.screens.game

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class GameFieldValidationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun allSameGuessShowsLocalizedValidationReason() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppStrings provides StaticLocalizationProvider.forLanguage(AppLanguage.RU)
            ) {
                InplaceXTheme {
                    GameFieldScreen(
                        params = GameFieldParams(
                            typeGame = TypeGame.RaceMatch,
                            useHints = false,
                            lenSecret = 4,
                        ),
                        title = "",
                        onBack = {},
                    )
                }
            }
        }

        repeat(4) {
            composeRule.onNodeWithTag("game-digit-1").performClick()
        }
        composeRule.onNodeWithText("Подтвердить").performClick()

        composeRule
            .onNodeWithTag("game-status")
            .assertTextEquals("Нельзя вводить комбинацию из одинаковых цифр")
    }

    @Test
    fun allSameGuessShowsEnglishLocalizedValidationReason() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppStrings provides StaticLocalizationProvider.forLanguage(AppLanguage.EN)
            ) {
                InplaceXTheme {
                    GameFieldScreen(
                        params = GameFieldParams(
                            typeGame = TypeGame.RaceMatch,
                            useHints = false,
                            lenSecret = 4,
                        ),
                        title = "",
                        onBack = {},
                    )
                }
            }
        }

        repeat(4) {
            composeRule.onNodeWithTag("game-digit-1").performClick()
        }
        composeRule.onNodeWithText("Confirm").performClick()

        composeRule
            .onNodeWithTag("game-status")
            .assertTextEquals("The combination cannot contain only one repeated digit")
    }

    @Test
    fun attemptListFollowsTheNewestResult() {
        composeRule.setContent {
            var attempts by remember { mutableStateOf(emptyList<String>()) }

            CompositionLocalProvider(
                LocalAppStrings provides StaticLocalizationProvider.forLanguage(AppLanguage.RU)
            ) {
                InplaceXTheme {
                    Column {
                        Button(
                            onClick = {
                                val number = attempts.size + 1
                                attempts = attempts + "Попытка $number"
                            },
                            modifier = Modifier.testTag("add-attempt"),
                        ) {
                            Text("Добавить")
                        }
                        AttemptsModule(
                            attempts = attempts,
                            modifier = Modifier.height(120.dp),
                        )
                    }
                }
            }
        }

        repeat(20) {
            composeRule.onNodeWithTag("add-attempt").performClick()
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("game-attempt-20").assertIsDisplayed()
    }

    @Test
    fun autoModeLocksTheOnlyRemainingPossibleMatch() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppStrings provides StaticLocalizationProvider.forLanguage(AppLanguage.RU)
            ) {
                InplaceXTheme {
                    GameFieldScreen(
                        params = GameFieldParams(
                            typeGame = TypeGame.RaceMatch,
                            useHints = true,
                            limitMoves = 12,
                            lenSecret = 4,
                        ),
                        title = "",
                        fixedSecret = "4167",
                        openPositionHints = 1,
                        checkDigitHints = 1,
                        autoModeAvailable = true,
                        onConsumeOpenPositionHint = { true },
                        onConsumeCheckDigitHint = { true },
                        autoRestartOnWin = false,
                        onBack = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Проверить цифру").performClick()
        composeRule.onNodeWithTag("game-digit-0").performClick()
        composeRule.onNodeWithText("Ок").performClick()

        composeRule.onNodeWithContentDescription("Открыть позицию").performClick()
        composeRule.onNodeWithTag("game-guess-slot-1").performClick()

        listOf('0', '6', '0').forEach { digit ->
            composeRule.onNodeWithTag("game-digit-$digit").performClick()
        }
        composeRule.onNodeWithText("Подтвердить").performClick()

        composeRule
            .onNodeWithTag("game-guess-value-3", useUnmergedTree = true)
            .assertTextEquals("6")
    }
}
