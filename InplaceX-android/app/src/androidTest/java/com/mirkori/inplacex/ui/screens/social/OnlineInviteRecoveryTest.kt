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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnlineInviteRecoveryTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun temporaryFailureAndScreenRecreationKeepTheSamePendingInvitation() {
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
        val pending = mutableStateOf<String?>("ABCD2345")
        val strings = StaticLocalizationProvider.forLanguage(AppLanguage.RU)
        try {
            composeRule.setContent {
                InplaceXTheme {
                    CompositionLocalProvider(LocalAppStrings provides strings) {
                        if (visible.value) OnlineDuelScreen(
                            runtime = runtime,
                            initialPendingInviteCode = pending.value,
                            onPendingInviteChange = { pending.value = it },
                            entryPoint = OnlineDuelEntryPoint.INVITES,
                        )
                    }
                }
            }
            composeRule.waitUntil(10_000) { reads.get() > 0 }
            composeRule.onNodeWithText(strings.text("social.online.error.unavailable")).assertIsDisplayed()
            composeRule.runOnIdle { assertEquals("ABCD2345", pending.value) }
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

    private companion object {
        const val InviteJson = """{"inviteCode":"ABCD2345","status":"waiting","sessionId":null,"createdAtEpochMs":1000,"expiresAtEpochMs":9999999999999,"playStyle":"turn_based","codeLength":7,"allowDuplicates":true,"maxConsecutiveDuplicateDigits":3,"matchDurationSeconds":600}"""
    }
}
