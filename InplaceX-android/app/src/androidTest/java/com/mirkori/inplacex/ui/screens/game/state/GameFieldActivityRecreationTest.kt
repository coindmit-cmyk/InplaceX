package com.mirkori.inplacex.ui.screens.game.state

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mirkori.inplacex.debug.GameFieldRecreationTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameFieldActivityRecreationTest {
    @get:Rule val composeRule = createAndroidComposeRule<GameFieldRecreationTestActivity>()

    @Test
    fun activityRecreationPreservesTheDisplayedGameAndNewSessionStartsFresh() {
        composeRule.waitForIdle()
        composeRule.activityRule.scenario.onActivity {
            assertEquals("1234", it.observedSecret)
            assertEquals(1, it.matchStartCount)
        }

        listOf(5, 6, 7, 8).forEach { digit ->
            composeRule.onNodeWithTag("game-digit-$digit").performClick()
        }
        composeRule.onNodeWithTag("game-confirm").performClick()
        composeRule.onNodeWithTag("game-attempt-1").assertExists()
        assertCounter(1)
        composeRule.onNodeWithTag("game-digit-9").performClick()
        guessValue(1).assertTextEquals("9")
        (2..4).forEach { guessValue(it).assertTextEquals(" ") }

        val cell = composeRule.onNodeWithTag("game-analysis-9-1", useUnmergedTree = true)
        val unmarkedDescription = cell.fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        composeRule.onNodeWithTag("game-tool-maybe").performClick()
        cell.performClick()
        val markedDescription = cell.fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertNotEquals(unmarkedDescription, markedDescription)

        // Observe an actual timer tick first, so resetting to 02:00 cannot pass.
        composeRule.waitUntil(timeoutMillis = 5_000) { remainingSeconds() < 120 }
        val remainingBefore = remainingSeconds()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("game-attempt-1").assertExists()
        assertCounter(1)
        guessValue(1).assertTextEquals("9")
        (2..4).forEach { guessValue(it).assertTextEquals(" ") }
        assertEquals(
            markedDescription,
            composeRule.onNodeWithTag("game-analysis-9-1", useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
        assertTrue("Remaining timer increased after recreation", remainingSeconds() <= remainingBefore)
        composeRule.activityRule.scenario.onActivity {
            assertEquals("1234", it.observedSecret)
            assertEquals(1, it.matchStartCount)
        }

        composeRule.onNodeWithTag("game-fixture-new-session").performClick()
        composeRule.onNodeWithTag("game-attempt-1").assertDoesNotExist()
        assertCounter(0)
        (1..4).forEach { guessValue(it).assertTextEquals(" ") }
        composeRule.activityRule.scenario.onActivity {
            assertEquals("1234", it.observedSecret)
            assertEquals(2, it.matchStartCount)
        }
    }

    private fun guessValue(position: Int) =
        composeRule.onNodeWithTag("game-guess-value-$position", useUnmergedTree = true)

    private fun assertCounter(attempts: Int) {
        val expected = Regex("""\b$attempts\s*/\s*12\b""")
        assertTrue("Expected displayed counter $attempts/12", composeRule.onAllNodes(textMatching(expected)).fetchSemanticsNodes().isNotEmpty())
    }

    private fun remainingSeconds(): Int {
        val timer = Regex("""\b(\d{2}):(\d{2})\b""")
        val displayed = composeRule.onAllNodes(textMatching(timer)).fetchSemanticsNodes()
            .asSequence()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().asSequence() }
            .mapNotNull { timer.find(it.text) }
            .first()
        return displayed.groupValues[1].toInt() * 60 + displayed.groupValues[2].toInt()
    }

    private fun textMatching(pattern: Regex) = SemanticsMatcher("text matching $pattern") { node ->
        node.config.getOrNull(SemanticsProperties.Text)
            ?.any { pattern.containsMatchIn(it.text) } == true
    }
}
