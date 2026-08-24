package com.mirkori.inplacex.ui.screens.game

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mirkori.inplacex.R
import com.mirkori.inplacex.core.analysis.ProvenFact
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.ui.GameScreen
import com.mirkori.inplacex.ui.background.ScreenBackground
import com.mirkori.inplacex.ui.background.ScreenBackgroundStyle
import com.mirkori.inplacex.ui.screens.game.presentation.GamePresentationCallbacks
import com.mirkori.inplacex.ui.screens.game.state.GameFieldEvent
import com.mirkori.inplacex.ui.screens.game.state.GameFieldHintMode
import com.mirkori.inplacex.ui.screens.game.state.GameFieldManualMark
import com.mirkori.inplacex.ui.screens.game.state.GameFieldManualMarkType
import com.mirkori.inplacex.ui.screens.game.state.GameFieldMatchParameters
import com.mirkori.inplacex.ui.screens.game.state.GameFieldOpponentAttempt
import com.mirkori.inplacex.ui.screens.game.state.GameFieldOpponentProgressState
import com.mirkori.inplacex.ui.screens.game.state.GameFieldRouteUiState
import com.mirkori.inplacex.ui.screens.game.state.GameFieldStateHolder
import com.mirkori.inplacex.ui.screens.game.state.GameFieldToolsState
import com.mirkori.inplacex.ui.screens.game.state.GameFieldUiState
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinalGameplayCaptureTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun captureApprovedGameplayState() {
        val arguments = InstrumentationRegistry.getArguments()
        val captureIdArgument = arguments.getString("captureId")
        assumeTrue("Reference capture runs only with explicit instrumentation arguments", captureIdArgument != null)
        val captureId = requireNotNull(captureIdArgument)
        val codeLength = requireNotNull(arguments.getString("codeLength")).toInt()
        val stateName = arguments.getString("state") ?: "filled"
        val language = if (arguments.getString("language") == "en") AppLanguage.EN else AppLanguage.RU
        val adLoaded = arguments.getString("ad") == "loaded"
        val uiState = captureState(codeLength, stateName)

        composeRule.setContent {
            CompositionLocalProvider(
                LocalAppStrings provides StaticLocalizationProvider.forLanguage(language),
            ) {
                InplaceXTheme {
                    ScreenBackground(
                        style = ScreenBackgroundStyle.DrawableResource(
                            resourceId = R.drawable.toy_room_bg_v6,
                            fallbackColor = Color(0xFFB66C31),
                        ),
                    ) {
                        GameScreen(
                            uiState = uiState,
                            callbacks = GamePresentationCallbacks(onEvent = {}),
                            modifier = Modifier.fillMaxSize(),
                            debugSlot = if (adLoaded) {
                                {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .background(Color(0xFFFFE4A8))
                                            .padding(horizontal = 8.dp)
                                            .testTag("game-banner-slot"),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("Яндекс Директ • TEST AD")
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        if (stateName == "input_disabled_waiting") {
            composeRule.onNodeWithTag("game-status").assertIsDisplayed()
        }
        if (stateName == "opponent_results") {
            composeRule.onNodeWithTag("game-opponent-results-panel").assertIsDisplayed()
        }
        composeRule.onNodeWithTag("game-analysis-9-$codeLength").assertIsDisplayed()
        composeRule.onNodeWithTag("game-guess-slot-$codeLength").assertIsDisplayed()
        composeRule.onNodeWithTag("game-matrix-title").assertIsDisplayed()
        composeRule.onNodeWithTag("game-confirm").assertIsDisplayed()
        if (adLoaded) {
            composeRule.onNodeWithTag("game-banner-panel").assertIsDisplayed()
        }
        val attemptsBounds = composeRule.onNodeWithTag("game-attempts-panel")
            .fetchSemanticsNode().boundsInRoot
        val matrixBounds = composeRule.onNodeWithTag("game-analysis-panel")
            .fetchSemanticsNode().boundsInRoot
        val toolsBounds = composeRule.onNodeWithTag("game-tools-panel")
            .fetchSemanticsNode().boundsInRoot
        val inputBounds = composeRule.onNodeWithTag("game-input-panel")
            .fetchSemanticsNode().boundsInRoot
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val firstCellBounds = composeRule.onNodeWithTag("game-analysis-0-1")
            .fetchSemanticsNode().boundsInRoot
        val firstKeyBounds = composeRule.onNodeWithTag("game-digit-1")
            .fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        assertTrue("Matrix must end before tools", matrixBounds.bottom <= toolsBounds.top)
        assertTrue("Input actions must remain inside the viewport", inputBounds.bottom <= rootBounds.bottom)
        assertTrue(
            "Matrix rows must remain compact instead of stretching with the viewport",
            firstCellBounds.height <= 27.dp.value * density,
        )
        assertTrue(
            "Compact keypad visuals must retain a 44dp vertical hit target",
            firstKeyBounds.height >= 44.dp.value * density,
        )
        if (codeLength > 6) {
            assertTrue("Attempts must be above the matrix", attemptsBounds.bottom <= matrixBounds.top)
            assertTrue(
                "Stacked panels must use the same available width",
                kotlin.math.abs(attemptsBounds.width - matrixBounds.width) <= 2f,
            )
        } else {
            assertTrue("Attempts must stay left of the matrix", attemptsBounds.right <= matrixBounds.left)
        }
        val output = File(
            requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
            "captures/$captureId.png",
        )
        output.parentFile?.mkdirs()
        output.outputStream().use { stream ->
            check(composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }

    private fun captureState(codeLength: Int, stateName: String): GameFieldUiState {
        val helpers = stateName == "hints_boosts_selected"
        val parameters = GameFieldMatchParameters(
            codeLength = codeLength,
            attemptLimit = 20,
            hintsEnabled = helpers,
            boostsEnabled = helpers,
            autoModeAvailable = true,
        )
        val secret = "0123456789".take(codeLength)
        val holder = GameFieldStateHolder(SavedStateHandle(), parameters, initialSecret = secret)
        if (stateName != "empty") {
            repeat(4) { offset ->
                val shift = (offset % (codeLength - 1)) + 1
                holder.submitRawGuess(secret.drop(shift) + secret.take(shift))
            }
        }
        var state = holder.state.value
        if (stateName == "filled_manual_locked") {
            state = state.copy(
                manualMarks = listOf(
                    GameFieldManualMark(1, '7', GameFieldManualMarkType.NO),
                    GameFieldManualMark(2, '6', GameFieldManualMarkType.MAYBE),
                    GameFieldManualMark(3.coerceAtMost(codeLength - 1), '5', GameFieldManualMarkType.YES),
                ),
                evidence = state.evidence.copy(
                    provenFacts = setOf(
                        ProvenFact.exactMatch(0, secret.first()),
                        ProvenFact.notAtPosition(1.coerceAtMost(codeLength - 1), '9'),
                    ),
                ),
            )
        }
        if (stateName == "input_disabled_waiting") {
            state = state.copy(route = state.route.copy(inputEnabled = false, secondaryStatusText = "Player 2"))
        }
        if (stateName == "opponent_results") {
            state = state.copy(
                route = state.route.copy(
                    opponentProgress = GameFieldOpponentProgressState(
                        attempts = listOf(
                            GameFieldOpponentAttempt(1, 0),
                            GameFieldOpponentAttempt(2, 1),
                            GameFieldOpponentAttempt(3, codeLength),
                        ),
                        completed = true,
                    ),
                ),
            )
        }
        if (helpers) {
            state = state.copy(
                route = GameFieldRouteUiState(
                    openPositionHints = 3,
                    checkDigitHints = 2,
                    checkPositionHints = 1,
                    extraMovesBoosts = 2,
                    extraTimeBoosts = 1,
                    extraMovesPerBoost = 3,
                    extraTimeSecondsPerBoost = 120,
                ),
                tools = GameFieldToolsState(selectedHint = GameFieldHintMode.CHECK_DIGIT),
            )
        }
        return state
    }
}
