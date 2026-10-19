package com.mirkori.inplacex.ui.screens.game

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mirkori.inplacex.MainActivity
import org.junit.Rule
import org.junit.Test

class GameFieldActivityRecreationSmokeTest {
    @get:Rule
    val activityRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun acceptedGuessAndAttemptCountSurviveActivityRecreation() {
        activityRule.onNodeWithText("Гонка").performClick()

        "123456".forEach { digit ->
            activityRule.onNodeWithTag("game-digit-$digit").performClick()
        }
        activityRule.onNodeWithText("Подтвердить").performClick()
        activityRule.onNodeWithTag("game-attempt-1").assertIsDisplayed()
        activityRule.onNodeWithText("1/12").assertIsDisplayed()

        activityRule.activityRule.scenario.recreate()

        activityRule.onNodeWithTag("game-attempt-1").assertIsDisplayed()
        activityRule.onNodeWithText("1/12").assertIsDisplayed()
    }
}
