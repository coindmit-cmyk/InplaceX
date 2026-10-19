package com.mirkori.inplacex.ui.screens.social

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.platform.online.GuestSession
import com.mirkori.inplacex.platform.online.LegacyMembershipMigrationAttempt
import com.mirkori.inplacex.platform.online.LegacyMembershipMigrationAttemptStore
import com.mirkori.inplacex.platform.online.LegacyOnlineSessionRecovery
import com.mirkori.inplacex.platform.online.OnlineDuelClient
import com.mirkori.inplacex.platform.online.OnlineRuntime
import com.mirkori.inplacex.platform.online.OnlineSessionOpenResult
import com.mirkori.inplacex.platform.online.RemoteCallResult
import com.mirkori.inplacex.platform.online.RemoteHttpMethod
import com.mirkori.inplacex.platform.online.RemoteRequestSpec
import com.mirkori.inplacex.platform.online.RemoteResponse
import com.mirkori.inplacex.platform.online.RemoteWebSocketSpec
import com.mirkori.inplacex.platform.online.SecureGuestSessionStore
import com.mirkori.inplacex.platform.online.TransportBoundary
import com.mirkori.inplacex.platform.online.createOnlineHttpClient
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.Test

class SocialGameShellTest {
    private val foregroundHandshake = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "A10_WAIT_FOREGROUND\\n") })
                val deadline = SystemClock.elapsedRealtime() + 25_000L
                com.mirkori.inplacex.testsupport.foregroundMainActivityForCiEmulator()
                var mainResumed = false
                while (SystemClock.elapsedRealtime() < deadline && !mainResumed) {
                    instrumentation.runOnMainSync {
                        mainResumed = ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED).any { it is com.mirkori.inplacex.MainActivity }
                    }
                    if (!mainResumed) Thread.sleep(100)
                }
                assertTrue("Host must foreground ordinary MainActivity", mainResumed)
                base.evaluate()
            }
        }
    }

    private val composeActivityRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule val rules = RuleChain.outerRule(foregroundHandshake).around(composeActivityRule)

    private val composeRule get() = composeActivityRule

    @Test fun privateInviteMovesThroughWaitingSetupActiveAndFinished() {
        val fixture = Fixture()
        try {
            fixture.mount()
            fixture.await { fixture.transport.inviteReads.get() > 0 && fixture.nested.get() == true && fixture.firstEvents.lastOrNull() == false }
            assertNull(fixture.session.value)
            fixture.transport.matched.set(true)
            fixture.await { fixture.observedRevision.get() == 1L && fixture.firstEvents.lastOrNull() == true && fixture.session.value == SessionId }
            fixture.transport.phase.set(Phase(2, "active"))
            fixture.await { fixture.observedRevision.get() == 2L }
            composeRule.waitForIdle()
            assertEquals(true, fixture.firstEvents.lastOrNull())
            fixture.callbackVersion.value = 1
            fixture.await { fixture.secondEvents.lastOrNull() == true }
            fixture.transport.phase.set(Phase(3, "finished"))
            fixture.await { fixture.observedRevision.get() == 3L && fixture.secondEvents.lastOrNull() == false && fixture.session.value == null }
            assertEquals(true, fixture.firstEvents.lastOrNull())
            assertEquals(true, fixture.nested.get())
            fixture.assertOnlyExpectedRequests()
        } finally {
            fixture.close()
        }
    }

    @Test fun privateActiveHudRequestReturnsToNestedSetupOnce() {
        val fixture = Fixture()
        try {
            fixture.mount()
            fixture.enterActive()
            fixture.exitRequest.value = true
            fixture.await { fixture.consumed.get() == 1 && fixture.session.value == null && fixture.firstEvents.lastOrNull() == false }
            assertEquals(true, fixture.nested.get())
            assertFalse(fixture.exitRequest.value)
            composeRule.waitForIdle()
            assertEquals(1, fixture.consumed.get())
            fixture.assertOnlyExpectedRequests()
        } finally {
            fixture.close()
        }
    }

    @Test fun privateActiveSystemBackReturnsToNestedSetup() {
        val fixture = Fixture()
        try {
            fixture.mount()
            fixture.enterActive()
            composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
            fixture.await { fixture.session.value == null && fixture.firstEvents.lastOrNull() == false }
            assertEquals(true, fixture.nested.get())
            assertEquals(0, fixture.consumed.get())
            fixture.assertOnlyExpectedRequests()
        } finally {
            fixture.close()
        }
    }

    @Test fun disposingActiveChildSendsFalseToLatestCallback() {
        val fixture = Fixture()
        try {
            fixture.mount()
            fixture.enterActive()
            fixture.callbackVersion.value = 1
            fixture.await { fixture.secondEvents.lastOrNull() == true }
            fixture.dispose()
            fixture.await { fixture.secondEvents.lastOrNull() == false && fixture.nested.get() == false }
            assertEquals(true, fixture.firstEvents.lastOrNull())
            fixture.assertOnlyExpectedRequests()
        } finally {
            fixture.close()
        }
    }

    @Test fun restoredQuickMatchReportsGameShellUntilFinished() {
        val fixture = Fixture()
        try {
            fixture.transport.phase.set(Phase(2, "active"))
            composeRule.runOnUiThread {
                fixture.session.value = SessionId
                fixture.pending.value = null
            }
            fixture.mount(pendingCode = null)
            fixture.await { fixture.observedRevision.get() == 2L && fixture.firstEvents.lastOrNull() == true }
            composeRule.runOnUiThread { fixture.transport.phase.set(Phase(3, "finished")) }
            fixture.await {
                fixture.observedRevision.get() == 3L &&
                    fixture.firstEvents.lastOrNull() == false && fixture.session.value == null
            }
            assertEquals(0, fixture.transport.inviteReads.get())
            assertEquals(0, fixture.transport.accepts.get())
            fixture.assertOnlyExpectedRequests()
        } finally {
            fixture.close()
        }
    }

    @Test fun directFriendEntryAcceptsInviteAndReportsGameShellThroughFinished() {
        val fixture = Fixture()
        try {
            composeRule.runOnUiThread { fixture.pending.value = null }
            fixture.mountFriend()
            fixture.await {
                fixture.transport.accepts.get() == 1 && fixture.observedRevision.get() == 1L &&
                    fixture.firstEvents.lastOrNull() == true && fixture.session.value == SessionId
            }
            composeRule.runOnUiThread { fixture.transport.phase.set(Phase(2, "active")) }
            fixture.await {
                fixture.observedRevision.get() == 2L && fixture.firstEvents.lastOrNull() == true
            }
            composeRule.runOnUiThread { fixture.transport.phase.set(Phase(3, "finished")) }
            fixture.await {
                fixture.observedRevision.get() == 3L &&
                    fixture.firstEvents.lastOrNull() == false && fixture.session.value == null
            }
            assertEquals(1, fixture.transport.accepts.get())
            assertEquals(0, fixture.transport.unexpected.get())
            fixture.assertOnlyExpectedRequests()
        } finally {
            fixture.close()
        }
    }

    @Test fun unchangedPollsDoNotRepeatSnapshotObservation() {
        val fixture = Fixture()
        try {
            fixture.mount()
            fixture.enterActive()
            val activeReads = fixture.transport.sessionReads.get()
            fixture.await { fixture.transport.sessionReads.get() >= activeReads + 3 }
            assertEquals(listOf(1L, 2L), fixture.observedRevisions.toList())

            fixture.transport.phase.set(Phase(3, "active"))
            fixture.await { fixture.observedRevision.get() == 3L }
            val revisedReads = fixture.transport.sessionReads.get()
            fixture.await { fixture.transport.sessionReads.get() >= revisedReads + 3 }
            assertEquals(listOf(1L, 2L, 3L), fixture.observedRevisions.toList())

            fixture.transport.phase.set(Phase(4, "finished"))
            fixture.await { fixture.observedRevision.get() == 4L && fixture.session.value == null }
            assertEquals(listOf(1L, 2L, 3L, 4L), fixture.observedRevisions.toList())
            fixture.assertOnlyExpectedRequests()
        } finally {
            fixture.close()
        }
    }

    @Test fun menuWithoutRuntimeReportsNotInGame() {
        val fixture = Fixture()
        try {
            fixture.mount(onlineRuntime = null, pendingCode = null)
            fixture.await { fixture.firstEvents.lastOrNull() == false && fixture.nested.get() == false }
            fixture.assertOnlyExpectedRequests()
        } finally {
            fixture.close()
        }
    }

    private inner class Fixture {
        val transport = FakeTransport()
        private val duel = OnlineDuelClient(transport)
        private val guestStore = object : SecureGuestSessionStore {
            private var session: GuestSession? = null
            override fun read(): GuestSession? = session
            override fun write(session: GuestSession) { this.session = session }
            override fun clear() { session = null }
        }
        private val attemptStore = object : LegacyMembershipMigrationAttemptStore {
            private var attempt: LegacyMembershipMigrationAttempt? = null
            override fun read(): LegacyMembershipMigrationAttempt? = attempt
            override fun write(attempt: LegacyMembershipMigrationAttempt) { this.attempt = attempt }
            override fun clear() { attempt = null }
        }
        val runtime = OnlineRuntime(
            createOnlineHttpClient(), duel,
            LegacyOnlineSessionRecovery(duel, guestStore, attemptStore),
        )
        val visible = mutableStateOf(true)
        val session = mutableStateOf<String?>(null)
        val pending = mutableStateOf<String?>(InviteCode)
        val exitRequest = mutableStateOf(false)
        val callbackVersion = mutableStateOf(0)
        val nested = AtomicReference<Boolean?>(null)
        val observedRevision = AtomicLong(-1)
        val observedRevisions = CopyOnWriteArrayList<Long>()
        val consumed = AtomicInteger()
        val firstEvents = CopyOnWriteArrayList<Boolean>()
        val secondEvents = CopyOnWriteArrayList<Boolean>()
        private val firstCallback: (Boolean) -> Unit = { firstEvents.add(it) }
        private val secondCallback: (Boolean) -> Unit = { secondEvents.add(it) }
        private val strings = StaticLocalizationProvider.forLanguage(AppLanguage.RU)
        private var disposed = false

        fun mount(onlineRuntime: OnlineRuntime? = runtime, pendingCode: String? = InviteCode) {
            pending.value = pendingCode
            composeRule.setContent {
                InplaceXTheme {
                    CompositionLocalProvider(LocalAppStrings provides strings) {
                        if (visible.value) SocialRootScreen(
                            onlineRuntime = onlineRuntime,
                            initialActiveSessionId = session.value,
                            onActiveSessionChange = { session.value = it },
                            initialPendingInviteCode = pending.value,
                            onPendingInviteChange = { pending.value = it },
                            requestExitGame = exitRequest.value,
                            onExitGameConsumed = {
                                consumed.incrementAndGet()
                                exitRequest.value = false
                            },
                            onInGameChange = if (callbackVersion.value == 0) firstCallback else secondCallback,
                            onNestedScreenChange = { nested.set(it) },
                            onSnapshotObserved = {
                                observedRevisions.add(it.revision)
                                observedRevision.set(it.revision)
                            },
                        )
                    }
                }
            }
        }

        fun mountFriend() {
            composeRule.setContent {
                InplaceXTheme {
                    CompositionLocalProvider(LocalAppStrings provides strings) {
                        if (visible.value) OnlineDuelScreen(
                            runtime = runtime,
                            onActiveSessionChange = { session.value = it },
                            entryPoint = OnlineDuelEntryPoint.FRIEND,
                            autoAcceptInviteCode = InviteCode,
                            onInGameChange = firstCallback,
                            onSnapshotObserved = { observedRevision.set(it.revision) },
                        )
                    }
                }
            }
        }

        fun await(condition: () -> Boolean) {
            composeRule.waitUntil(timeoutMillis = 15_000, condition = condition)
        }

        fun enterActive() {
            await { transport.inviteReads.get() > 0 && firstEvents.lastOrNull() == false }
            transport.matched.set(true)
            await { observedRevision.get() == 1L && firstEvents.lastOrNull() == true && session.value == SessionId }
            transport.phase.set(Phase(2, "active"))
            await { observedRevision.get() == 2L }
            composeRule.waitForIdle()
            assertEquals(true, firstEvents.lastOrNull())
        }

        fun assertOnlyExpectedRequests() {
            assertEquals(0, transport.unexpected.get())
            assertTrue(transport.inviteReads.get() > 0 || pending.value == null)
        }

        fun dispose() {
            if (!disposed) {
                composeRule.runOnUiThread { visible.value = false }
                composeRule.waitForIdle()
                disposed = true
            }
        }

        fun close() {
            try { dispose() } finally { runtime.close() }
        }
    }

    private data class Phase(val revision: Int, val name: String)

    private class FakeTransport : TransportBoundary {
        val matched = AtomicBoolean(false)
        val phase = AtomicReference(Phase(1, "setup"))
        val inviteReads = AtomicInteger()
        val sessionReads = AtomicInteger()
        val accepts = AtomicInteger()
        val unexpected = AtomicInteger()

        override suspend fun execute(request: RemoteRequestSpec): RemoteCallResult {
            val body = when {
                request.method == RemoteHttpMethod.GET && request.path == "/api/v1/friends/invites/$InviteCode" -> {
                    inviteReads.incrementAndGet()
                    inviteJson(matched.get())
                }
                request.method == RemoteHttpMethod.GET && request.path == "/api/v1/sessions/$SessionId" -> {
                    sessionReads.incrementAndGet()
                    snapshotJson(phase.get())
                }
                request.method == RemoteHttpMethod.POST && request.path == "/api/v1/friends/invites/$InviteCode/accept" -> {
                    accepts.incrementAndGet()
                    inviteJson(true)
                }
                else -> {
                    unexpected.incrementAndGet()
                    return RemoteCallResult.HttpFailure(RemoteResponse(404, emptyMap(), "{}"))
                }
            }
            return RemoteCallResult.Success(RemoteResponse(200, emptyMap(), body))
        }

        override suspend fun openSession(request: RemoteWebSocketSpec): OnlineSessionOpenResult {
            unexpected.incrementAndGet()
            return OnlineSessionOpenResult.Offline
        }
    }

    private companion object {
        const val InviteCode = "ABCD2345"
        const val SessionId = "00000000-0000-4000-8000-000000000777"

        fun inviteJson(matched: Boolean): String {
            val status = if (matched) "matched" else "waiting"
            val session = if (matched) "\"$SessionId\"" else "null"
            return """{"inviteCode":"$InviteCode","status":"$status","sessionId":$session,"createdAtEpochMs":1000,"expiresAtEpochMs":9999999999999,"playStyle":"turn_based","codeLength":4,"allowDuplicates":true,"maxConsecutiveDuplicateDigits":3,"matchDurationSeconds":600}"""
        }

        fun snapshotJson(phase: Phase): String {
            val configured = phase.name != "setup"
            val turn = if (phase.name == "active") "\"player\"" else "null"
            val winner = if (phase.name == "finished") "\"player\"" else "null"
            return """{"sessionId":"$SessionId","revision":${phase.revision},"phase":"${phase.name}","currentTurn":$turn,"winner":$winner,"finishReason":null,"playStyle":"turn_based","codeLength":4,"attemptLimit":9,"allowDuplicates":false,"maxConsecutiveDuplicateDigits":null,"startedAtEpochMs":1000,"deadlineAtEpochMs":9999999999999,"serverTimeEpochMs":2000,"attempts":[],"participants":[{"actor":"player","secretConfigured":$configured,"attemptsUsed":0,"attemptsLeft":9},{"actor":"opponent","secretConfigured":$configured,"attemptsUsed":0,"attemptsLeft":9}]}"""
        }
    }
}
