package com.mirkori.inplacex.ui.screens.game

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.model.AnalysisBoardState
import com.mirkori.inplacex.core.model.GameConfig
import com.mirkori.inplacex.core.model.MatchState
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.ui.screens.race.RaceGameScreen
import com.mirkori.inplacex.ui.screens.race_setup.RaceSetupScreen
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import org.junit.Rule
import org.junit.Test

class GameLocalizationSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun debugScreenRendersRussianCatalog() {
        setLocalizedContent(AppLanguage.RU) {
            GameFieldDebugScreen()
        }

        composeRule.onNodeWithText("Тестовый экран игры").assertExists()
        composeRule.onNodeWithText("Статус: Активна").assertExists()
    }

    @Test
    fun debugScreenRendersEnglishCatalog() {
        setLocalizedContent(AppLanguage.EN) {
            GameFieldDebugScreen()
        }

        composeRule.onNodeWithText("Game test screen").assertExists()
        composeRule.onNodeWithText("Status: Active").assertExists()
    }

    @Test
    fun raceSetupRendersRussianCatalog() {
        setLocalizedContent(AppLanguage.RU) {
            RaceSetupContent()
        }

        composeRule.onNodeWithText("Настройка гонки").assertExists()
        composeRule.onNodeWithText("Старт").assertExists()
        composeRule.onNodeWithContentDescription("Уменьшить: Длина кода").assertExists()
    }

    @Test
    fun raceSetupRendersEnglishCatalog() {
        setLocalizedContent(AppLanguage.EN) {
            RaceSetupContent()
        }

        composeRule.onNodeWithText("Race setup").assertExists()
        composeRule.onNodeWithText("Start").assertExists()
        composeRule.onNodeWithContentDescription("Decrease: Code length").assertExists()
    }

    @Test
    fun raceGameRendersRussianCatalogAndActionSemantics() {
        setLocalizedContent(AppLanguage.RU) {
            RaceGameContent()
        }

        composeRule.onNodeWithText("PvE • Гонка").assertExists()
        composeRule.onNodeWithContentDescription("Удалить последнюю цифру").assertExists()
        composeRule.onNodeWithContentDescription("Очистить попытку").assertExists()
    }

    @Test
    fun raceGameRendersEnglishCatalogAndActionSemantics() {
        setLocalizedContent(AppLanguage.EN) {
            RaceGameContent()
        }

        composeRule.onNodeWithText("PvE • Race").assertExists()
        composeRule.onNodeWithContentDescription("Remove last digit").assertExists()
        composeRule.onNodeWithContentDescription("Clear attempt").assertExists()
    }

    private fun setLocalizedContent(
        language: AppLanguage,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppStrings provides StaticLocalizationProvider.forLanguage(language),
            ) {
                InplaceXTheme(content = content)
            }
        }
    }

    @Composable
    private fun RaceSetupContent() {
        val config = GameConfig(codeLength = 4, attemptLimit = 8)
        RaceSetupScreen(
            paddingValues = PaddingValues(0.dp),
            config = config,
            onConfigChange = {},
            onBack = {},
            onStartRace = {},
        )
    }

    @Composable
    private fun RaceGameContent() {
        val config = GameConfig(codeLength = 4, attemptLimit = 8)
        RaceGameScreen(
            paddingValues = PaddingValues(0.dp),
            config = config,
            matchState = MatchState(config = config, secret = "1234"),
            currentGuess = "",
            analysisBoard = AnalysisBoardState.create(config.codeLength),
            elapsedSeconds = 0,
            onBack = {},
            onGuessChange = {},
            onAnalysisCellClick = { _, _ -> },
            onRemoveLastDigit = {},
            onClearGuess = {},
            onAppendDigit = {},
            onSubmitGuess = {},
            onTick = {},
            onRestart = {},
        )
    }
}
