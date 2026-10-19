package com.mirkori.inplacex.ui.screens.game

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.mirkori.inplacex.MainActivity
import org.junit.Rule
import org.junit.Test

class GameFieldActivityRecreationSmokeTest {
    @get:Rule
    val activityRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun acceptedGuessAndPartialInputSurviveActivityRecreation() {
        activityRule.onNodeWithTag("reference-home-race").performClick()
        activityRule.onNodeWithTag("reference-race-local").performScrollTo().performClick()

        "123456".forEach { digit ->
            activityRule.onNodeWithTag("game-digit-$digit").performClick()
        }
        activityRule.onNodeWithTag("game-confirm").performClick()
        activityRule.onNodeWithTag("game-attempt-1").assertIsDisplayed()
        activityRule.onNodeWithTag("game-attempt-2").assertDoesNotExist()
        activityRule.onNodeWithTag("game-digit-7").performClick()
        activityRule.onNodeWithTag("game-guess-value-1", useUnmergedTree = true).assertTextEquals("7")

        activityRule.activityRule.scenario.recreate()

        activityRule.onNodeWithTag("game-attempt-1").assertIsDisplayed()
        activityRule.onNodeWithTag("game-attempt-2").assertDoesNotExist()
        activityRule.onNodeWithTag("game-guess-value-1", useUnmergedTree = true).assertTextEquals("7")
        (2..6).forEach { position ->
            activityRule.onNodeWithTag("game-guess-value-$position", useUnmergedTree = true).assertTextEquals(" ")
        }
    }
}
