package com.mirkori.inplacex.ui.screens.social

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.platform.online.OnlineDuelAttemptState
import com.mirkori.inplacex.platform.online.OnlineDuelSnapshotState
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OnlineDuelInputTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun confirmedMiddleDigitsDoNotConsumeKeypadInput() {
        var submitted: String? = null
        val snapshot = OnlineDuelSnapshotState(
            sessionId = "00000000-0000-4000-8000-000000000001",
            revision = 4,
            phase = "active",
            currentTurn = "player",
            winner = null,
            codeLength = 7,
            attemptLimit = null,
            allowDuplicates = true,
            maxConsecutiveDuplicateDigits = 3,
            attempts = listOf(
                OnlineDuelAttemptState("player", 2, 1, "0125501"),
                OnlineDuelAttemptState("player", 0, 2, "0123401"),
            ),
        )
        composeRule.setContent {
            InplaceXTheme {
                CompositionLocalProvider(
                    LocalAppStrings provides StaticLocalizationProvider.forLanguage(AppLanguage.RU),
                ) {
                    OnlineDuelGameField(snapshot, snapshot.knownPlayerGuesses(), false, { submitted = it }, {})
                }
            }
        }
        composeRule.onNodeWithTag("game-guess-value-4", useUnmergedTree = true).assertTextEquals("5")
        composeRule.onNodeWithTag("game-guess-value-5", useUnmergedTree = true).assertTextEquals("5")
        repeat(5) { composeRule.onNodeWithTag("game-digit-1").performClick() }
        "1115511".forEachIndexed { index, digit ->
            composeRule.onNodeWithTag("game-guess-value-${index + 1}", useUnmergedTree = true)
                .assertTextEquals(digit.toString())
        }
        val capture = composeRule.onRoot().captureToImage().asAndroidBitmap()
        File(composeRule.activity.getExternalFilesDir(null), "online-confirmed-input.png").outputStream().use {
            capture.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        composeRule.onNodeWithTag("game-confirm").performClick()
        composeRule.runOnIdle { assertEquals("1115511", submitted) }
    }
}
