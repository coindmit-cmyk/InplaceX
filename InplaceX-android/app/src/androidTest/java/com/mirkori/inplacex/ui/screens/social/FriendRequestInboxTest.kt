package com.mirkori.inplacex.ui.screens.social

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import com.mirkori.inplacex.platform.mirkori.MirkoriFriendOperationResult
import com.mirkori.inplacex.platform.mirkori.MirkoriFriendRequest
import com.mirkori.inplacex.platform.mirkori.MirkoriPublicPlayerProfile
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FriendRequestInboxTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun noRequestsDoesNotShowEntry() {
        show(emptyList())
        composeRule.onNodeWithTag("friend-requests-open").assertDoesNotExist()
    }

    @Test
    fun singleRequestOpensOneDialogAndDismissDoesNotAccept() {
        var calls = 0
        show(listOf(request(1))) { calls++; MirkoriFriendOperationResult.Success(it) }
        composeRule.onNodeWithText("Заявка в друзья").assertIsDisplayed()
        capture("friend-request-single-summary")
        composeRule.onNodeWithTag("friend-requests-open").performClick()
        composeRule.onNodeWithTag("friend-request-accept-1").assertIsDisplayed()
        capture("friend-request-single-dialog")
        composeRule.onNodeWithText("Закрыть").performClick()
        composeRule.runOnIdle { assertEquals(0, calls) }
    }

    @Test
    fun manyRequestsUseOneEntryAndEveryRequestCanBeReached() {
        show((1..50).map(::request)) { MirkoriFriendOperationResult.Success(it) }
        composeRule.onNodeWithText("50").assertIsDisplayed()
        composeRule.onNodeWithText("Игрок 1").assertDoesNotExist()
        capture("friend-requests-many-summary")
        composeRule.onNodeWithTag("friend-requests-open").performClick()
        capture("friend-requests-many-dialog")
        composeRule.onNodeWithTag("friend-requests-list").performScrollToIndex(49)
        composeRule.onNodeWithTag("friend-request-accept-50").performClick()
        composeRule.onNodeWithText("Заявки в друзья · 49").assertIsDisplayed()
        composeRule.onNodeWithTag("friend-request-accept-50").assertDoesNotExist()
    }

    @Test
    fun failedAcceptanceCanRetryAndSuccessDoesNotResurrectFromStaleList() {
        var calls = 0
        val result = CompletableDeferred<MirkoriFriendOperationResult>()
        show(listOf(request(1))) {
            calls++
            if (calls == 1) result.await() else MirkoriFriendOperationResult.Success(it)
        }
        composeRule.onNodeWithTag("friend-requests-open").performClick()
        composeRule.onNodeWithTag("friend-request-accept-1").performClick()
        composeRule.onNodeWithTag("friend-request-accept-1").assertIsNotEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(1, calls)
            result.complete(MirkoriFriendOperationResult.Unavailable)
        }
        composeRule.onNodeWithText("Сервис друзей сейчас недоступен.").assertIsDisplayed()
        composeRule.onNodeWithTag("friend-request-accept-1").performClick()
        composeRule.onNodeWithText("Новых заявок нет").assertIsDisplayed()
        composeRule.onNodeWithText("Закрыть").performClick()
        composeRule.onNodeWithTag("friend-requests-open").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(2, calls) }
    }

    @Test
    fun serverRejectionKeepsRequestVisible() {
        show(listOf(request(1))) { MirkoriFriendOperationResult.Rejected }
        composeRule.onNodeWithTag("friend-requests-open").performClick()
        composeRule.onNodeWithTag("friend-request-accept-1").performClick()
        composeRule.onNodeWithText("Заявку выполнить не удалось.").assertIsDisplayed()
        composeRule.onNodeWithTag("friend-request-accept-1").assertIsDisplayed()
    }

    @Test
    fun incomingUpdatesDoNotDismissOpenInbox() {
        val requests = mutableStateOf(listOf(request(1), request(2)))
        show(requests)
        composeRule.onNodeWithTag("friend-requests-open").performClick()
        composeRule.runOnIdle { requests.value = listOf(request(2)) }
        composeRule.onNodeWithTag("friend-request-accept-2").assertIsDisplayed()
        composeRule.runOnIdle { requests.value = emptyList() }
        composeRule.onNodeWithText("Новых заявок нет").assertIsDisplayed()
    }

    @Test
    fun longNameAndLargeFontKeepAcceptanceAndCloseReachable() {
        val longRequest = request(1).let { it.copy(player = it.player.copy(displayName = "Очень длинное имя нового друга для проверки переноса строк")) }
        show(mutableStateOf(listOf(longRequest)), fontScale = 1.5f)
        composeRule.onNodeWithTag("friend-requests-open").performClick()
        composeRule.onNodeWithTag("friend-request-accept-1").assertIsDisplayed()
        composeRule.onNodeWithText("Закрыть").assertIsDisplayed()
        capture("friend-request-large-font")
    }

    private fun show(
        requests: List<MirkoriFriendRequest>,
        onAccept: suspend (MirkoriFriendRequest) -> MirkoriFriendOperationResult = { MirkoriFriendOperationResult.Unavailable },
    ) = show(mutableStateOf(requests), onAccept = onAccept)

    private fun show(
        requests: MutableState<List<MirkoriFriendRequest>>,
        fontScale: Float = 1f,
        onAccept: suspend (MirkoriFriendRequest) -> MirkoriFriendOperationResult = { MirkoriFriendOperationResult.Unavailable },
    ) {
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalAppStrings provides StaticLocalizationProvider.forLanguage(AppLanguage.RU),
                LocalDensity provides Density(density, fontScale),
            ) {
                InplaceXTheme {
                    Box(Modifier.safeDrawingPadding()) { FriendRequestInbox(requests.value, onAccept) }
                }
            }
        }
    }

    private fun request(index: Int) = MirkoriFriendRequest(
        requestId = index.toString(),
        player = MirkoriPublicPlayerProfile("test-player-$index", "player_$index", "Игрок $index", null),
    )

    private fun capture(name: String) {
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
