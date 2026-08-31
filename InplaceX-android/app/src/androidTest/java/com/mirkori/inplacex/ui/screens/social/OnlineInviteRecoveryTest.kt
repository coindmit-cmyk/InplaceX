package com.mirkori.inplacex.ui.screens.social

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.platform.online.AndroidKeystoreGuestSessionStore
import com.mirkori.inplacex.platform.online.LegacyOnlineSessionRecovery
import com.mirkori.inplacex.platform.online.OnlineDuelClient
import com.mirkori.inplacex.platform.online.OnlineRuntime
import com.mirkori.inplacex.platform.online.OnlineSessionOpenResult
import com.mirkori.inplacex.platform.online.RemoteCallResult
import com.mirkori.inplacex.platform.online.RemoteFriendPlayStyle
import com.mirkori.inplacex.platform.online.RemoteHttpMethod
import com.mirkori.inplacex.platform.online.RemoteRequestSpec
import com.mirkori.inplacex.platform.online.RemoteResponse
import com.mirkori.inplacex.platform.online.RemoteWebSocketSpec
import com.mirkori.inplacex.platform.online.SharedPreferencesLegacyMembershipMigrationAttemptStore
import com.mirkori.inplacex.platform.online.TransportBoundary
import com.mirkori.inplacex.platform.online.createOnlineHttpClient
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnlineInviteRecoveryTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun lateHydrationTemporaryFailureAndRecreationKeepTheSamePendingInvitation() {
        val unavailable = AtomicBoolean(true)
        val reads = AtomicInteger()
        val unexpectedRequests = AtomicInteger()
        val duel = OnlineDuelClient(object : TransportBoundary {
            override suspend fun execute(request: RemoteRequestSpec): RemoteCallResult {
                if (request.method == RemoteHttpMethod.GET && request.path == "/api/v1/friends/invites/ABCD2345") {
                    reads.incrementAndGet()
                } else {
                    unexpectedRequests.incrementAndGet()
                }
                return if (unavailable.get()) {
                    RemoteCallResult.HttpFailure(RemoteResponse(503, emptyMap(), "{}"))
                } else {
                    RemoteCallResult.Success(RemoteResponse(200, emptyMap(), InviteJson))
                }
            }
            override suspend fun openSession(request: RemoteWebSocketSpec): OnlineSessionOpenResult =
                error("No WebSocket expected while waiting for invite")
        })
        val runtime = OnlineRuntime(
            createOnlineHttpClient(),
            duel,
            LegacyOnlineSessionRecovery(
                duel,
                AndroidKeystoreGuestSessionStore(composeRule.activity),
                SharedPreferencesLegacyMembershipMigrationAttemptStore(composeRule.activity),
            ),
        )
        val visible = mutableStateOf(true)
        val pending = mutableStateOf<String?>(null)
        val strings = StaticLocalizationProvider.forLanguage(AppLanguage.RU)
        try {
            composeRule.setContent {
                InplaceXTheme {
                    CompositionLocalProvider(LocalAppStrings provides strings) {
                        if (visible.value) SocialRootScreen(
                            onlineRuntime = runtime,
                            initialPendingInviteCode = pending.value,
                            onPendingInviteChange = { pending.value = it },
                        )
                    }
                }
            }
            composeRule.runOnIdle {
                assertEquals(0, reads.get())
                pending.value = "ABCD2345"
            }
            composeRule.waitUntil(10_000) { reads.get() > 0 }
            composeRule.onNodeWithText(strings.text("social.online.error.unavailable")).assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals("ABCD2345", pending.value)
                assertEquals(1, reads.get())
            }
            unavailable.set(false)
            composeRule.onNodeWithText(strings.text("social.online.retry")).performClick()
            composeRule.waitUntil(10_000) { reads.get() > 1 }
            composeRule.onNodeWithText("ABCD2345").assertIsDisplayed()
            composeRule.runOnIdle { visible.value = false }
            composeRule.waitForIdle()
            val previousReads = reads.get()
            composeRule.runOnIdle { visible.value = true }
            composeRule.waitUntil(10_000) { reads.get() > previousReads }
            composeRule.onNodeWithText("ABCD2345").assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals("ABCD2345", pending.value)
                assertEquals(0, unexpectedRequests.get())
                assertTrue(reads.get() >= 3)
                visible.value = false
            }
            composeRule.waitForIdle()
        } finally {
            runtime.close()
        }
    }

    @Test
    fun failedIncomingInviteRetryRepeatsAcceptInsteadOfRestoringStalePointers() {
        val accepts = AtomicInteger()
        val sessionReads = AtomicInteger()
        val unexpectedRequests = AtomicInteger()
        val duel = OnlineDuelClient(object : TransportBoundary {
            override suspend fun execute(request: RemoteRequestSpec): RemoteCallResult = when {
                request.method == RemoteHttpMethod.POST &&
                    request.path == "/api/v1/friends/invites/7KMQ3NWP/accept" -> {
                    if (accepts.incrementAndGet() == 1) {
                        RemoteCallResult.HttpFailure(RemoteResponse(503, emptyMap(), "{}"))
                    } else {
                        RemoteCallResult.Success(RemoteResponse(200, emptyMap(), MatchedInviteJson))
                    }
                }
                request.method == RemoteHttpMethod.GET &&
                    request.path == "/api/v1/sessions/$AcceptedSessionId" -> {
                    sessionReads.incrementAndGet()
                    RemoteCallResult.Success(RemoteResponse(200, emptyMap(), ActiveSnapshotJson))
                }
                else -> {
                    unexpectedRequests.incrementAndGet()
                    RemoteCallResult.HttpFailure(RemoteResponse(503, emptyMap(), "{}"))
                }
            }

            override suspend fun openSession(request: RemoteWebSocketSpec): OnlineSessionOpenResult =
                error("No WebSocket expected during invite acceptance")
        })
        val runtime = OnlineRuntime(
            createOnlineHttpClient(),
            duel,
            LegacyOnlineSessionRecovery(
                duel,
                AndroidKeystoreGuestSessionStore(composeRule.activity),
                SharedPreferencesLegacyMembershipMigrationAttemptStore(composeRule.activity),
            ),
        )
        val visible = mutableStateOf(true)
        val activeSession = mutableStateOf<String?>(OldSessionId)
        val strings = StaticLocalizationProvider.forLanguage(AppLanguage.RU)
        try {
            composeRule.setContent {
                InplaceXTheme {
                    CompositionLocalProvider(LocalAppStrings provides strings) {
                        if (visible.value) OnlineDuelScreen(
                            runtime = runtime,
                            initialSessionId = activeSession.value,
                            initialPendingInviteCode = "ABCD2345",
                            onActiveSessionChange = { activeSession.value = it },
                            entryPoint = OnlineDuelEntryPoint.FRIEND,
                            autoAcceptInviteCode = "7KMQ3NWP",
                        )
                    }
                }
            }
            composeRule.waitUntil(10_000) { accepts.get() == 1 }
            composeRule.onNodeWithText(strings.text("social.online.error.unavailable")).assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals(0, sessionReads.get())
                assertEquals(0, unexpectedRequests.get())
            }

            composeRule.onNodeWithText(strings.text("social.online.retry")).performClick()
            composeRule.waitUntil(10_000) { accepts.get() == 2 && sessionReads.get() > 0 }
            composeRule.runOnIdle {
                assertEquals(AcceptedSessionId, activeSession.value)
                assertEquals(0, unexpectedRequests.get())
                visible.value = false
            }
            composeRule.waitForIdle()
        } finally {
            runtime.close()
        }
    }

    @Test
    fun successfulIncomingInviteFinishesSessionReadAfterPointerRecomposition() {
        val sessionReadStarted = CompletableDeferred<Unit>()
        val allowSessionResponse = CompletableDeferred<Unit>()
        val sessionReads = AtomicInteger()
        val unexpectedRequests = AtomicInteger()
        val duel = OnlineDuelClient(object : TransportBoundary {
            override suspend fun execute(request: RemoteRequestSpec): RemoteCallResult = when {
                request.method == RemoteHttpMethod.POST &&
                    request.path == "/api/v1/friends/invites/7KMQ3NWP/accept" ->
                    RemoteCallResult.Success(RemoteResponse(200, emptyMap(), MatchedInviteJson))
                request.method == RemoteHttpMethod.GET &&
                    request.path == "/api/v1/sessions/$AcceptedSessionId" -> {
                    sessionReadStarted.complete(Unit)
                    allowSessionResponse.await()
                    sessionReads.incrementAndGet()
                    RemoteCallResult.Success(RemoteResponse(200, emptyMap(), ActiveSnapshotJson))
                }
                else -> {
                    unexpectedRequests.incrementAndGet()
                    RemoteCallResult.HttpFailure(RemoteResponse(503, emptyMap(), "{}"))
                }
            }

            override suspend fun openSession(request: RemoteWebSocketSpec): OnlineSessionOpenResult =
                error("No WebSocket expected during invite acceptance")
        })
        val runtime = OnlineRuntime(
            createOnlineHttpClient(),
            duel,
            LegacyOnlineSessionRecovery(
                duel,
                AndroidKeystoreGuestSessionStore(composeRule.activity),
                SharedPreferencesLegacyMembershipMigrationAttemptStore(composeRule.activity),
            ),
        )
        val visible = mutableStateOf(true)
        val activeSession = mutableStateOf<String?>(null)
        val pending = mutableStateOf<String?>("ABCD2345")
        val strings = StaticLocalizationProvider.forLanguage(AppLanguage.RU)
        try {
            composeRule.setContent {
                InplaceXTheme {
                    CompositionLocalProvider(LocalAppStrings provides strings) {
                        if (visible.value) OnlineDuelScreen(
                            runtime = runtime,
                            initialSessionId = activeSession.value,
                            initialPendingInviteCode = pending.value,
                            onActiveSessionChange = { activeSession.value = it },
                            onPendingInviteChange = { pending.value = it },
                            entryPoint = OnlineDuelEntryPoint.FRIEND,
                            autoAcceptInviteCode = "7KMQ3NWP",
                        )
                    }
                }
            }
            composeRule.waitUntil(10_000) { sessionReadStarted.isCompleted }
            composeRule.runOnIdle {
                assertEquals(AcceptedSessionId, activeSession.value)
                assertEquals(null, pending.value)
                allowSessionResponse.complete(Unit)
            }
            composeRule.waitUntil(10_000) { sessionReads.get() > 0 }
            composeRule.runOnIdle {
                assertEquals(0, unexpectedRequests.get())
                visible.value = false
            }
            composeRule.waitForIdle()
        } finally {
            allowSessionResponse.complete(Unit)
            runtime.close()
        }
    }

    @Test
    fun stalePendingInvitationDoesNotHijackQuickMatchEntry() {
        val requests = AtomicInteger()
        val attemptedOperation = AtomicReference<String?>()
        val duel = OnlineDuelClient(object : TransportBoundary {
            override suspend fun execute(request: RemoteRequestSpec): RemoteCallResult {
                requests.incrementAndGet()
                attemptedOperation.set(request.operation)
                return RemoteCallResult.HttpFailure(RemoteResponse(503, emptyMap(), "{}"))
            }

            override suspend fun openSession(request: RemoteWebSocketSpec): OnlineSessionOpenResult =
                error("No WebSocket expected on quick-match setup")
        })
        val runtime = OnlineRuntime(
            createOnlineHttpClient(),
            duel,
            LegacyOnlineSessionRecovery(
                duel,
                AndroidKeystoreGuestSessionStore(composeRule.activity),
                SharedPreferencesLegacyMembershipMigrationAttemptStore(composeRule.activity),
            ),
        )
        val visible = mutableStateOf(true)
        val strings = StaticLocalizationProvider.forLanguage(AppLanguage.RU)
        try {
            composeRule.setContent {
                InplaceXTheme {
                    CompositionLocalProvider(LocalAppStrings provides strings) {
                        if (visible.value) SocialRootScreen(
                            onlineRuntime = runtime,
                            initialPendingInviteCode = "ABCD2345",
                            requestedQuickMatchPlayStyle = RemoteFriendPlayStyle.TURN_BASED,
                            requestedQuickMatchCodeLength = 6,
                        )
                    }
                }
            }
            composeRule.onNodeWithText(strings.text("social.online.error.unavailable"))
                .assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals(1, requests.get())
                assertEquals("matchmaking.create", attemptedOperation.get())
                visible.value = false
            }
            composeRule.waitForIdle()
        } finally {
            runtime.close()
        }
    }

    private companion object {
        const val AcceptedSessionId = "00000000-0000-4000-8000-000000000777"
        const val OldSessionId = "00000000-0000-4000-8000-000000000666"
        const val InviteJson = """{"inviteCode":"ABCD2345","status":"waiting","sessionId":null,"createdAtEpochMs":1000,"expiresAtEpochMs":9999999999999,"playStyle":"turn_based","codeLength":7,"allowDuplicates":true,"maxConsecutiveDuplicateDigits":3,"matchDurationSeconds":600}"""
        const val MatchedInviteJson = """{"inviteCode":"7KMQ3NWP","status":"matched","sessionId":"$AcceptedSessionId","createdAtEpochMs":1000,"expiresAtEpochMs":9999999999999,"playStyle":"turn_based","codeLength":4,"allowDuplicates":true,"maxConsecutiveDuplicateDigits":3,"matchDurationSeconds":600}"""
        const val ActiveSnapshotJson = """{"sessionId":"$AcceptedSessionId","revision":2,"phase":"active","currentTurn":"player","winner":null,"finishReason":null,"playStyle":"turn_based","codeLength":4,"attemptLimit":9,"allowDuplicates":false,"maxConsecutiveDuplicateDigits":null,"startedAtEpochMs":1000,"deadlineAtEpochMs":9999999999999,"serverTimeEpochMs":2000,"attempts":[],"participants":[{"actor":"player","secretConfigured":true,"attemptsUsed":0,"attemptsLeft":9},{"actor":"opponent","secretConfigured":true,"attemptsUsed":0,"attemptsLeft":9}]}"""
    }
}
