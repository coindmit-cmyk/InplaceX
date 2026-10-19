package com.mirkori.inplacex.debug

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.mirkori.inplacex.MainActivity
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoTableAccessUiTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test
    fun foregroundHandoffCoversEveryRouteAndAccessWithoutResettingEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun status(message: String) {
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
        }
        status("A05_WAIT_FOREGROUND")
        val deadline = SystemClock.elapsedRealtime() + 25_000L
        var mainResumed = false
        while (SystemClock.elapsedRealtime() < deadline && !mainResumed) {
            instrumentation.runOnMainSync {
                mainResumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).any { it is MainActivity }
            }
            if (!mainResumed) Thread.sleep(100)
        }
        assertTrue("Host must foreground ordinary exported MainActivity", mainResumed)

        val intent = Intent(context, AutoTableAccessTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<AutoTableAccessTestActivity>(intent).use {
            val modes = listOf("LocalRace", "LocalDuel", "Company", "OnlineDuel", "OnlineRace")
            val accesses = listOf("Free", "Pro", "ProPlus", "Temporary", "Expired", "AdFree", "ServerPro")
            val allowed = setOf("Pro", "ProPlus", "Temporary", "ServerPro")
            modes.forEachIndexed { modeIndex, mode ->
                if (modeIndex > 0) rule.onNodeWithTag("a05-mode").performClick()
                rule.onNodeWithTag("a05-case").assertTextContains("Mode: $mode", substring = true)
                rule.onNodeWithTag("a05-revoke").performClick()
                rule.onNodeWithTag("game-digit-4").performClick()
                rule.onNodeWithTag("game-guess-value-1", useUnmergedTree = true).assertTextEquals("4")
                val mark = rule.onNodeWithTag("game-analysis-6-4", useUnmergedTree = true)
                val beforeMark = mark.fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
                rule.onNodeWithTag("game-tool-yes").performClick()
                mark.performClick()
                val afterMark = mark.fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
                assertNotEquals(beforeMark, afterMark)
                accesses.forEachIndexed { accessIndex, access ->
                    rule.onNodeWithTag("a05-revoke").performClick()
                    repeat(accessIndex) { rule.onNodeWithTag("a05-access").performClick() }
                    rule.onNodeWithTag("a05-case").assertTextContains("Mode: $mode Access: $access", substring = true)
                    val auto = rule.onNodeWithTag("game-auto-mode")
                    if (access in allowed) auto.assertIsEnabled()
                    else {
                        auto.assertIsNotEnabled()
                        auto.assertTextContains("PRO", substring = true)
                    }
                    rule.onNodeWithTag("game-guess-value-1", useUnmergedTree = true).assertTextEquals("4")
                    assertEquals(afterMark, mark.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
                    if (mode.startsWith("Online")) {
                        rule.onNodeWithTag("game-attempt-2").assertExists()
                        if (access in allowed) {
                            rule.onNodeWithTag("game-guess-value-2", useUnmergedTree = true).assertTextEquals("5")
                            rule.onNodeWithTag("game-guess-value-3", useUnmergedTree = true).assertTextEquals("5")
                        } else {
                            rule.onNodeWithTag("game-guess-value-2", useUnmergedTree = true).assertTextEquals(" ")
                            rule.onNodeWithTag("game-guess-value-3", useUnmergedTree = true).assertTextEquals(" ")
                        }
                    }
                    if (access == "Free" || access == "Pro") screenshot(mode, access)
                    status("A05_CASE $mode/$access PASS")
                }
                rule.onNodeWithTag("a05-revoke").performClick()
                repeat(3) { rule.onNodeWithTag("a05-access").performClick() }
                rule.onNodeWithTag("a05-case").assertTextContains("Access: Temporary", substring = true)
                rule.onNodeWithTag("game-auto-mode").assertIsEnabled()
                rule.onNodeWithTag("a05-expire").performClick()
                rule.onNodeWithTag("game-auto-mode").assertIsNotEnabled()
                rule.onNodeWithTag("game-guess-value-1", useUnmergedTree = true).assertTextEquals("4")
                rule.onNodeWithTag("a05-revoke").performClick()
                repeat(1) { rule.onNodeWithTag("a05-access").performClick() }
                rule.onNodeWithTag("game-auto-mode").performClick()
                rule.onNodeWithTag("game-auto-mode").assertTextContains("Ручной", substring = true)
                rule.onNodeWithTag("a05-revoke").performClick()
                rule.onNodeWithTag("game-auto-mode").assertIsNotEnabled()
                screenshot(mode, "revoked")
                rule.onNodeWithTag("a05-access").performClick()
                rule.onNodeWithTag("game-auto-mode").assertTextContains("Ручной", substring = true)
                rule.onNodeWithTag("game-guess-value-1", useUnmergedTree = true).assertTextEquals("4")
                assertEquals(afterMark, mark.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
                if (mode.startsWith("Online")) rule.onNodeWithTag("game-attempt-2").assertExists()
                status("A05_CASE $mode/revoked-regained PASS")
            }
        }
    }

    private fun screenshot(mode: String, access: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = InstrumentationRegistry.getArguments().getString("a05EvidenceRun")
            ?: error("Missing evidence run label")
        require(Regex("[A-Za-z0-9_-]+").matches(label)) { "Invalid evidence run label" }
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "a05-$label")
        check(directory.mkdirs() || directory.isDirectory)
        val file = File(directory, "a05-$mode-$access.png")
        FileOutputStream(file).use { stream ->
            assertTrue(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        assertTrue(file.length() > 0)
    }
}
