package com.mirkori.inplacex.ui.screens.game.presentation

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.mirkori.inplacex.MainActivity
import com.mirkori.inplacex.debug.GameTypographyTestActivity
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.json.JSONObject
import org.junit.Assert.assertTrue
import kotlin.math.ceil
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameTypographyTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val textMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

    @Test
    fun allRoutesAndLengthsRemainReadableAtHostFontScale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val expectedScale = arguments.getString("a07FontScale")?.toFloatOrNull()
            ?: error("Missing valid a07FontScale argument")
        assertTrue("Expected font scale must be 1.0, 1.3, 1.35, or 2.0", expectedScale in listOf(1f, 1.3f, 1.35f, 2f))
        assertTrue(
            "Actual system font scale ${context.resources.configuration.fontScale} differs from requested $expectedScale",
            kotlin.math.abs(context.resources.configuration.fontScale - expectedScale) < 0.02f,
        )
        val run = arguments.getString("a07EvidenceRun") ?: "default"
        require(Regex("[A-Za-z0-9_-]+").matches(run)) { "Invalid evidence run label" }
        fun optionalBooleanArgument(name: String, default: Boolean): Boolean {
            val value = arguments.getString(name) ?: return default
            require(value == "true" || value == "false") { "$name must be true or false" }
            return value.toBoolean()
        }
        val compact = optionalBooleanArgument("a07Compact", true)
        val recreate = optionalBooleanArgument("a07Recreate", false)

        fun status(message: String) {
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
        }
        fun awaitOrdinaryMain(boundary: String) {
            var mainResumed = false
            instrumentation.runOnMainSync {
                mainResumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).any { it is MainActivity }
            }
            if (mainResumed) return

            status("A07_WAIT_FOREGROUND")
            val deadline = SystemClock.elapsedRealtime() + 25_000L
            while (SystemClock.elapsedRealtime() < deadline && !mainResumed) {
                instrumentation.runOnMainSync {
                    mainResumed = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).any { it is MainActivity }
                }
                if (!mainResumed) Thread.sleep(100)
            }
            assertTrue("Host must foreground ordinary exported MainActivity at $boundary", mainResumed)
        }

        val routes = listOf("LocalRace", "LocalDuel", "Company", "OnlineDuel", "OnlineRace")
        for (mode in routes) for (length in listOf(4, 6, 10)) {
            val intent = Intent(context, GameTypographyTestActivity::class.java)
                .putExtra("mode", mode)
                .putExtra("codeLength", length)
                .putExtra("compact", compact)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            var stage = "before-launch"
            status("A07_STAGE $mode/$length before-launch")
            awaitOrdinaryMain("before-launch $mode/$length")
            stage = "launch"
            status("A07_STAGE $mode/$length before-launch-call")
            val scenario = try {
                ActivityScenario.launch<GameTypographyTestActivity>(intent)
            } catch (failure: Throwable) {
                status("A07_CASE $mode/$length/$expectedScale FAIL route=$mode/$length stage=$stage: $failure")
                throw AssertionError("A07 $mode/$length/$expectedScale failed at $stage").apply {
                    initCause(failure)
                }
            }
            status("A07_STAGE $mode/$length after-launch")
            var caseFailure: Throwable? = null
            try {
                scenario.use { scenario ->
                fun bring(tag: String) = rule.onNodeWithTag(tag).let { interaction ->
                    val node = interaction.fetchSemanticsNode()
                    val bounds = node.boundsInRoot
                    val rootBounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
                    val clippedWidth = minOf(bounds.right, rootBounds.right) - maxOf(bounds.left, rootBounds.left)
                    val clippedHeight = minOf(bounds.bottom, rootBounds.bottom) - maxOf(bounds.top, rootBounds.top)
                    val fullyVisible = bounds.width > 0f && bounds.height > 0f &&
                        node.size.width > 0 && node.size.height > 0 &&
                        kotlin.math.abs(bounds.width - node.size.width) <= 1f &&
                        kotlin.math.abs(bounds.height - node.size.height) <= 1f &&
                        clippedWidth > 0f && clippedHeight > 0f &&
                        kotlin.math.abs(clippedWidth - node.size.width) <= 1f &&
                        kotlin.math.abs(clippedHeight - node.size.height) <= 1f
                    if (fullyVisible) interaction.assertIsDisplayed()
                    else interaction.performScrollTo().assertIsDisplayed()
                }
                fun bringAttempt(tag: String) {
                    bring("game-attempts-panel")
                    bring(tag)
                }
                fun touch(tag: String) {
                    bring(tag).performTouchInput { click(center) }
                }
                fun capture(name: String) {
                    stage = name
                    checkVisibleTextHasNoOverflow()
                    screenshot(run, mode, length, name)
                }
                var submittedAttemptDescription: String? = null
                fun description(tag: String): String = rule.onNodeWithTag(tag)
                    .fetchSemanticsNode().config
                    .getOrNull(SemanticsProperties.ContentDescription)
                    ?.joinToString(" ") ?: error("Missing content description for $tag")
                fun assertFirstSlot(value: String) {
                    rule.onNodeWithTag("game-guess-value-1", useUnmergedTree = true)
                        .assertTextEquals(value)
                }
                try {
                    stage = "font"
                    rule.waitForIdle()
                    val fontText = rule.onNodeWithTag("a07-font-scale")
                        .fetchSemanticsNode().config
                        .getOrNull(SemanticsProperties.Text)
                        ?.joinToString("") { it.text }
                        ?: error("Missing fixture LocalDensity font scale")
                    val actualScale = fontText.removePrefix("fontScale=").toFloatOrNull()
                        ?: error("Invalid fixture LocalDensity font scale: $fontText")
                    assertTrue(
                        "Fixture LocalDensity font scale $actualScale differs from $expectedScale",
                        kotlin.math.abs(actualScale - expectedScale) < 0.02f,
                    )
                    saveTopBoundsEvidence(run, mode, length, fullHeight = !compact)
                    capture("top")

                    val local = mode == "LocalRace" || mode == "LocalDuel" || mode == "Company"
                    if (mode == "LocalRace") bring("game-opponent-results-panel")
                    if (local) {
                        stage = "secret"
                        var ready = false
                        val readyDeadline = SystemClock.elapsedRealtime() + 10_000L
                        while (!ready && SystemClock.elapsedRealtime() < readyDeadline) {
                            scenario.onActivity { ready = it.isSecretReady() }
                            if (!ready) Thread.sleep(50)
                        }
                        assertTrue("Fixture secret did not become ready", ready)
                        var safeGuess = ""
                        scenario.onActivity { safeGuess = it.safeNonWinningGuess() }
                        assertEquals("Safe guess length", length, safeGuess.length)

                        stage = "submit"
                        bring("game-input-panel")
                        safeGuess.forEach { touch("game-digit-$it") }
                        bring("game-confirm").assertIsEnabled()
                        touch("game-confirm")
                        rule.waitForIdle()
                        bringAttempt("game-attempt-1")
                        submittedAttemptDescription = description("game-attempt-1")
                        assertTrue(
                            "Attempt 1 does not describe submitted guess $safeGuess",
                            submittedAttemptDescription!!.contains(safeGuess),
                        )
                    } else {
                        stage = "online-history"
                        for (row in 1..2) {
                            val seeded = if (row == 1) "0123456789".take(length)
                                else "1234567890".take(length)
                            bringAttempt("game-attempt-$row")
                            assertTrue(
                                "Online attempt $row does not describe $seeded",
                                description("game-attempt-$row").contains(seeded),
                            )
                        }
                    }
                    capture("attempts")

                    stage = "matrix-first"
                    bring("game-analysis-panel")
                    bring("game-matrix-title")
                    rule.onNodeWithTag("game-analysis-rows").performScrollToIndex(0)
                    bring("game-analysis-0-1")
                    capture("matrix-first")

                    stage = "keypad"
                    bring("game-input-panel")
                    bring("game-reset")
                    bring("game-confirm")
                    for (chunk in (0..9).map { it.toString() }.chunked(length)) {
                        chunk.forEach { touch("game-digit-$it") }
                        repeat(chunk.size) { touch("game-backspace") }
                    }
                    touch("game-digit-4")
                    assertFirstSlot("4")
                    touch("game-backspace")
                    touch("game-digit-4")
                    assertFirstSlot("4")
                    bring("game-confirm").assertIsNotEnabled()
                    capture("partial-input")

                    if (local) {
                        stage = "helpers"
                        bring("game-helpers-scroll")
                        bring("game-hint-open-position")
                        bring("game-boost-extra-time")
                        assertFirstSlot("4")
                        capture("helpers")
                    }

                    stage = "tools"
                    bring("game-tools-panel")
                    bring("game-tool-no")
                    bring("game-auto-mode").assertIsNotEnabled()
                    assertFirstSlot("4")
                    capture("tools")

                    if (local) {
                        stage = "mark"
                        touch("game-tool-no")
                        bring("game-analysis-panel")
                        rule.onNodeWithTag("game-analysis-rows").performScrollToIndex(8)
                        bring("game-analysis-8-1").assertIsEnabled()
                        val before = description("game-analysis-8-1")
                        touch("game-analysis-8-1")
                        val after = description("game-analysis-8-1")
                        assertNotEquals("Matrix interaction did not update cell description", before, after)
                        bring("game-input-panel")
                        assertFirstSlot("4")
                        capture("marked")

                        if (recreate && mode == "LocalRace" && length == 6) {
                            val submitted = requireNotNull(submittedAttemptDescription)
                            val marked = after
                            assertTrue("Manual NO8/1 mark description is missing", marked.isNotBlank())
                            assertFirstSlot("4")
                            scenario.recreate()
                            rule.waitForIdle()
                            bring("game-input-panel")
                            assertFirstSlot("4")
                            bringAttempt("game-attempt-1")
                            assertEquals("Attempt 1 changed after recreation", submitted, description("game-attempt-1"))
                            bring("game-analysis-panel")
                            rule.onNodeWithTag("game-analysis-rows").performScrollToIndex(8)
                            bring("game-analysis-8-1")
                            assertEquals("Manual NO8/1 mark changed after recreation", marked, description("game-analysis-8-1"))
                            capture("recreated")
                            status("A07_RECREATION LocalRace/6 PASS")
                        }
                    }

                    stage = "matrix-last"
                    bring("game-analysis-panel")
                    rule.onNodeWithTag("game-analysis-rows").performScrollToIndex(9)
                    rule.onNodeWithTag("game-analysis-scroll").performTouchInput { swipeLeft() }
                    bring("game-analysis-9-$length")
                    capture("matrix-last")

                    stage = "input-final"
                    bring("game-input-panel")
                    bring("game-reset")
                    bring("game-backspace")
                    bring("game-confirm").assertIsNotEnabled()
                    assertFirstSlot("4")
                    capture("input")
                    // PASS is emitted only after the fixture has closed and ordinary Main is resumed.
                } catch (failure: Throwable) {
                    caseFailure = failure
                    status("A07_CASE $mode/$length/$expectedScale FAIL route=$mode/$length stage=$stage: $failure")
                    try {
                        screenshot(run, mode, length, "failure-$stage")
                    } catch (_: Throwable) {
                        // Preserve the original assertion or action failure.
                    }
                } finally {
                    status("A07_STAGE $mode/$length before-close")
                    try {
                        awaitOrdinaryMain("before-close $mode/$length")
                    } catch (foregroundFailure: Throwable) {
                        if (caseFailure == null) caseFailure = foregroundFailure
                        status("A07_CASE $mode/$length/$expectedScale FAIL route=$mode/$length stage=foreground-before-close: $foregroundFailure")
                    }
                }
                }
            } catch (closeFailure: Throwable) {
                if (caseFailure == null) caseFailure = closeFailure
                status("A07_CASE $mode/$length/$expectedScale FAIL route=$mode/$length stage=close: $closeFailure")
            }
            status("A07_STAGE $mode/$length after-close")
            try {
                awaitOrdinaryMain("after-close $mode/$length")
                status("A07_STAGE $mode/$length after-cleanup")
            } catch (foregroundFailure: Throwable) {
                if (caseFailure == null) caseFailure = foregroundFailure
                status("A07_CASE $mode/$length/$expectedScale FAIL route=$mode/$length stage=foreground-after-cleanup: $foregroundFailure")
            }
            if (caseFailure != null) {
                throw AssertionError("A07 $mode/$length/$expectedScale failed at cleanup or fixture stage").apply {
                    initCause(caseFailure)
                }
            }
            status("A07_CASE $mode/$length/$expectedScale PASS")
        }
    }

    private fun checkVisibleTextHasNoOverflow() {
        val nodes = rule.onAllNodes(textMatcher, useUnmergedTree = true).fetchSemanticsNodes()
        val targetPhysicalDensity = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        val candidates = nodes.mapNotNull { node ->
            val testTag = node.config.getOrNull(SemanticsProperties.TestTag)
            if (testTag == "a07-case" || testTag == "a07-font-scale") return@mapNotNull null
            if (node.boundsInRoot.width <= 0f || node.boundsInRoot.height <= 0f) return@mapNotNull null
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: return@mapNotNull null
            var ancestor = node
            var insideGamePresentation = false
            while (true) {
                if (ancestor.config.getOrNull(SemanticsProperties.TestTag) == "game-presentation-layout") {
                    insideGamePresentation = true
                    break
                }
                ancestor = ancestor.parent ?: break
            }
            Candidate(node, testTag, action, insideGamePresentation)
        }
        val collected = rule.runOnIdle {
            candidates.map { candidate ->
                val results = mutableListOf<TextLayoutResult>()
                val obtained = candidate.action.action?.invoke(results) == true
                Collected(candidate, obtained, results)
            }
        }
        var measuredGameTextCount = 0
        collected.forEach { (candidate, obtained, results) ->
            val node = candidate.node
            val testTag = candidate.testTag
            assertTrue("Unable to obtain text layout for visible text node: ${node.config}", obtained)
            assertTrue("No text layouts returned for visible text node: ${node.config}", results.isNotEmpty())
            results.forEach { result ->
                if (candidate.insideGamePresentation) {
                    measuredGameTextCount++
                    assertEquals("Game text must use standard font scale", 1f, result.layoutInput.density.fontScale, 0.001f)
                    assertEquals(
                        "Game text must retain target-context physical density",
                        targetPhysicalDensity,
                        result.layoutInput.density.density,
                        0.02f,
                    )
                }
                val text = node.config.getOrNull(SemanticsProperties.Text)
                    ?.joinToString("") { it.text }.orEmpty()
                val paragraph = result.multiParagraph
                val lineWidths = (0 until result.lineCount).map { line ->
                    result.getLineRight(line) - result.getLineLeft(line)
                }
                val contentWidth = lineWidths.maxOrNull() ?: 0f
                val requiredContentWidth = ceil(contentWidth).toInt()
                val requiredContentHeight = ceil(paragraph.height).toInt()
                val lastLine = result.lineCount - 1
                val lastLineBottom = if (result.lineCount > 0) result.getLineBottom(lastLine) else null
                val lastLineVisibleEnd = if (result.lineCount > 0) {
                    result.getLineEnd(lastLine, visibleEnd = true)
                } else {
                    0
                }
                val expectedTextEnd = text.trimEnd().length
                val textCoverage = JSONObject()
                    .put("lastLineVisibleEnd", lastLineVisibleEnd)
                    .put("requiredTextEnd", expectedTextEnd)
                    .put("complete", lastLineVisibleEnd >= expectedTextEnd)
                val widthDoesNotFit = requiredContentWidth > result.size.width
                val heightDoesNotFit = requiredContentHeight > result.size.height
                val textIsTruncated = paragraph.didExceedMaxLines || lastLineVisibleEnd < expectedTextEnd
                if (widthDoesNotFit || heightDoesNotFit || textIsTruncated) {
                    val metrics = JSONObject()
                        .put("text", text)
                        .put("tag", testTag)
                        .put("boundsInRoot", JSONObject()
                            .put("left", node.boundsInRoot.left)
                            .put("top", node.boundsInRoot.top)
                            .put("right", node.boundsInRoot.right)
                            .put("bottom", node.boundsInRoot.bottom)
                            .put("width", node.boundsInRoot.width)
                            .put("height", node.boundsInRoot.height))
                        .put("resultSize", JSONObject().put("width", result.size.width).put("height", result.size.height))
                        .put("contentLineWidths", org.json.JSONArray(lineWidths))
                        .put("contentWidth", contentWidth)
                        .put("requiredContentWidth", requiredContentWidth)
                        .put("multiParagraphWidth", paragraph.width)
                        .put("multiParagraphHeight", paragraph.height)
                        .put("requiredContentHeight", requiredContentHeight)
                        .put("didOverflowWidth", result.didOverflowWidth)
                        .put("didOverflowHeight", result.didOverflowHeight)
                        .put("hasVisualOverflow", result.hasVisualOverflow)
                        .put("didExceedMaxLines", paragraph.didExceedMaxLines)
                        .put("lineCount", result.lineCount)
                        .put("textCoverage", textCoverage)
                        .put("constraints", result.layoutInput.constraints.toString())
                        .put("fontSize", result.layoutInput.style.fontSize.toString())
                        .put("lineHeight", result.layoutInput.style.lineHeight.toString())
                        .put("lastLineBottom", lastLineBottom ?: JSONObject.NULL)
                        .put("lastLineVisibleEnd", lastLineVisibleEnd)
                        .put("widthDoesNotFit", widthDoesNotFit)
                        .put("heightDoesNotFit", heightDoesNotFit)
                        .put("textIsTruncated", textIsTruncated)
                    val elapsed = SystemClock.elapsedRealtime()
                    val run = InstrumentationRegistry.getArguments().getString("a07EvidenceRun") ?: "default"
                    val directory = File(
                        InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                        "a07-typography/$run",
                    )
                    check(directory.mkdirs() || directory.isDirectory)
                    val file = File(directory, "overflow-metrics-$elapsed.json")
                    file.writeText(metrics.toString(2))
                    throw AssertionError("Visible text content does not fit: $metrics; evidence=${file.absolutePath}")
                }
            }
        }
        assertTrue("No measured game text was found", measuredGameTextCount > 0)
    }

    private data class Candidate(
        val node: androidx.compose.ui.semantics.SemanticsNode,
        val testTag: String?,
        val action: androidx.compose.ui.semantics.AccessibilityAction<(MutableList<TextLayoutResult>) -> Boolean>,
        val insideGamePresentation: Boolean,
    )

    private data class Collected(
        val candidate: Candidate,
        val obtained: Boolean,
        val results: List<TextLayoutResult>,
    )
    private fun saveTopBoundsEvidence(run: String, mode: String, length: Int, fullHeight: Boolean) {
        val tags = listOf(
            "game-input-panel", "game-confirm", "game-backspace", "game-digit-0", "game-digit-9",
            "game-reference-layout",
        )
        val criticalTags = setOf(
            "game-input-panel", "game-confirm", "game-backspace", "game-digit-0", "game-digit-9",
        )
        val root = rule.onRoot().fetchSemanticsNode()
        val rootBounds = root.boundsInRoot
        val evidence = JSONObject().put("mode", mode).put("length", length)
            .put("root", JSONObject().put("exists", true).put("boundsInRoot", rootBounds.toString()).put("size", root.size.toString()))
        val nodes = JSONObject()
        val issues = mutableListOf<String>()
        for (tag in tags) {
            val matches = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true).fetchSemanticsNodes()
            val nodeEvidence = JSONObject().put("exists", matches.isNotEmpty()).put("count", matches.size)
            if (matches.size == 1) {
                val match = matches.single()
                val bounds = match.boundsInRoot
                val left = maxOf(bounds.left, rootBounds.left)
                val top = maxOf(bounds.top, rootBounds.top)
                val right = minOf(bounds.right, rootBounds.right)
                val bottom = minOf(bounds.bottom, rootBounds.bottom)
                val clippedWidth = maxOf(0f, right - left)
                val clippedHeight = maxOf(0f, bottom - top)
                val unclippedWidth = bounds.width
                val unclippedHeight = bounds.height
                val fullyVisible = unclippedWidth > 0f && unclippedHeight > 0f &&
                    match.size.width > 0 && match.size.height > 0 &&
                    kotlin.math.abs(unclippedWidth - match.size.width) <= 1f &&
                    kotlin.math.abs(unclippedHeight - match.size.height) <= 1f &&
                    clippedWidth > 0f && clippedHeight > 0f &&
                    kotlin.math.abs(clippedWidth - match.size.width) <= 1f &&
                    kotlin.math.abs(clippedHeight - match.size.height) <= 1f
                nodeEvidence.put("clippedBoundsInRoot", "[$left,$top,$right,$bottom]")
                    .put("size", match.size.toString())
                    .put("clippedWidth", clippedWidth)
                    .put("clippedHeight", clippedHeight)
                    .put("unclippedWidth", match.size.width)
                    .put("unclippedHeight", match.size.height)
                    .put("fullyVisible", fullyVisible)
                if (fullHeight && tag in criticalTags) {
                    if (match.size.width <= 0 || match.size.height <= 0) issues += "$tag has non-positive size ${match.size}"
                    if (clippedWidth <= 0f || clippedHeight <= 0f) issues += "$tag has non-positive clipped dimensions $clippedWidth x $clippedHeight"
                    if (!fullyVisible) issues += "$tag is not fully visible within root bounds"
                }
            } else if (fullHeight && tag in criticalTags) {
                issues += "$tag expected exactly one node, found ${matches.size}"
            }
            nodes.put(tag, nodeEvidence)
        }
        evidence.put("nodes", nodes)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "a07-typography/$run")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$mode-$length-top-bounds.json").writeText(evidence.toString(2))
        if (fullHeight) assertTrue("Invalid initial full-height critical-control bounds: ${issues.joinToString("; ")}", issues.isEmpty())
    }

    private fun screenshot(run: String, mode: String, length: Int, stage: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "a07-typography/$run")
        check(directory.mkdirs() || directory.isDirectory)
        val file = File(directory, "$mode-$length-$stage.png")
        FileOutputStream(file).use { stream ->
            assertTrue(rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        assertTrue(file.length() > 0)
    }
}
