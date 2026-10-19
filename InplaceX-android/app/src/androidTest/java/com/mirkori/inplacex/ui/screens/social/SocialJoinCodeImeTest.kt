package com.mirkori.inplacex.ui.screens.social

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.mirkori.inplacex.MainActivity
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SocialJoinCodeImeTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test
    fun realImeKeepsCodeAndJoinTouchAboveKeyboardAndBusyBlocksRepeat() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "A06_WAIT_FOREGROUND\n") })
        val deadline = SystemClock.elapsedRealtime() + 25_000L
        com.mirkori.inplacex.testsupport.foregroundMainActivityForCiEmulator()
        var mainResumed = false
        while (SystemClock.elapsedRealtime() < deadline && !mainResumed) {
            instrumentation.runOnMainSync {
                mainResumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).any { it is MainActivity }
            }
            if (!mainResumed) Thread.sleep(100)
        }
        assertTrue("Host must foreground ordinary MainActivity", mainResumed)

        val intent = Intent(instrumentation.targetContext, ComponentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ComponentActivity>(intent).use { scenario ->
            var code by mutableStateOf("")
            var busy by mutableStateOf(false)
            var joins by mutableIntStateOf(0)
            var creates by mutableIntStateOf(0)
            scenario.onActivity { activity ->
                WindowCompat.setDecorFitsSystemWindows(activity.window, false)
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                activity.setContent {
                    InplaceXTheme {
                        CompositionLocalProvider(
                            LocalAppStrings provides StaticLocalizationProvider.forLanguage(AppLanguage.EN),
                        ) {
                            // Production reserves a HUD above and a navigation/footer slot below
                            // the social center. Keep both reservations while the real IME opens.
                            Column(Modifier.fillMaxSize()) {
                                Spacer(Modifier.fillMaxWidth().height(100.dp))
                                Box(Modifier.fillMaxWidth().weight(1f)) {
                                    SocialJoinCodeReferenceContent(
                                        inviteCode = code,
                                        busy = busy,
                                        onInviteCodeChange = { code = it },
                                        onJoin = { joins++; busy = true },
                                        onCreateOwnCode = { creates++ },
                                    )
                                }
                                Spacer(Modifier.fillMaxWidth().height(102.dp))
                            }
                        }
                    }
                }
            }
            fun imeTopOrNull(): Int? {
                var top: Int? = null
                scenario.onActivity { activity ->
                    val root = activity.window.decorView.rootView
                    val insets = ViewCompat.getRootWindowInsets(root)
                    if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                        top = root.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    }
                }
                return top
            }
            fun waitForIme(visible: Boolean) {
                rule.waitUntil(timeoutMillis = 10_000) { (imeTopOrNull() != null) == visible }
            }
            val field = rule.onNodeWithTag("social-invite-code-field")
            val join = rule.onNodeWithTag("social-invite-join")
            val createOwn = rule.onNodeWithTag("social-invite-create-own")
            field.performClick()
            waitForIme(true)
            field.performTextInput("ABCD")
            join.assertIsNotEnabled()
            rule.runOnIdle {
                assertTrue("Partial code was not retained", code == "ABCD")
                assertTrue("Partial code submitted", joins == 0)
            }
            createOwn.performScrollTo()
            val createImeTop = imeTopOrNull()
            assertTrue("Real IME must remain visible while revealing create-own", createImeTop != null)
            val createBounds = createOwn.fetchSemanticsNode().boundsInWindow
            assertTrue("Create-own tile is not physically above the IME", createBounds.bottom < createImeTop!!)
            assertTrue("Create-own tile has no touchable pixel area", createBounds.width > 0f && createBounds.height > 0f)
            createOwn.performTouchInput { click(center) }
            rule.runOnIdle {
                assertTrue("Create-own callback did not run exactly once", creates == 1)
                assertTrue("Create-own changed the code", code == "ABCD")
                assertTrue("Create-own submitted a join", joins == 0)
            }

            scenario.onActivity { activity ->
                val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            waitForIme(false)
            rule.runOnIdle { assertTrue("Code changed when IME closed", code == "ABCD") }
            field.performClick()
            waitForIme(true)
            rule.runOnIdle { assertTrue("Code changed when IME reopened", code == "ABCD") }
            field.performTextInput("EFGH")
            rule.runOnIdle { assertTrue("Complete normalized code was not retained", code == "ABCDEFGH") }

            rule.runOnIdle { busy = true }
            join.assertIsNotEnabled()
            join.performTouchInput { click(center) }
            rule.runOnIdle { assertTrue("Busy action submitted", joins == 0) }
            rule.runOnIdle { busy = false }
            field.performClick()
            waitForIme(true)
            val imeTop = imeTopOrNull()
            assertTrue("Real IME must still be visible", imeTop != null)
            val button = join.fetchSemanticsNode().boundsInWindow
            assertTrue("Join is not physically above the IME", button.bottom < imeTop!!)
            assertTrue("Join has no touchable pixel area", button.width > 0f && button.height > 0f)
            join.performTouchInput { click(center) }
            rule.runOnIdle {
                assertTrue("Join callback did not run exactly once", joins == 1)
                assertTrue("Code changed during join", code == "ABCDEFGH")
            }
            join.performTouchInput { click(center) }
            rule.runOnIdle { assertTrue("Repeated busy press submitted", joins == 1) }
        }
    }
}
