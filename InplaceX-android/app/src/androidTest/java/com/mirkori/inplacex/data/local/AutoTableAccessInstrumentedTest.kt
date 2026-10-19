package com.mirkori.inplacex.data.local

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mirkori.inplacex.withServerPaidEntitlements
import com.mirkori.inplacex.platform.online.OnlineDuelAttemptState
import com.mirkori.inplacex.platform.online.OnlineDuelSnapshotState
import com.mirkori.inplacex.platform.online.RemoteFriendPlayStyle
import com.mirkori.inplacex.platform.services.MonetizationEntitlements
import com.mirkori.inplacex.ui.screens.game.state.GameFieldEvent
import com.mirkori.inplacex.ui.screens.game.state.GameFieldMatchParameters
import com.mirkori.inplacex.ui.screens.game.state.GameFieldMode
import com.mirkori.inplacex.ui.screens.game.state.GameFieldStateHolder
import com.mirkori.inplacex.ui.screens.social.OnlineDuelEditorState
import com.mirkori.inplacex.ui.screens.social.buildOnlineDuelGameFieldState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoTableAccessInstrumentedTest {
    @Test
    fun isolatedSqliteEntitlementsReachBothLocalAndOnlineModesWithoutInputLoss() {
        var now = 1_700_000_000_000L
        withIsolatedDatabase("a05_auto_table", nowMs = { now }) { context, config ->
            val repository = GameProgressRepository(context, config)
            fun verify(state: GameProgressState, expected: Boolean) {
                val allowed = state.autoTableAssistEnabledAt(now)
                assertEquals(expected, allowed)
                for (mode in GameFieldMode.entries) {
                    val holder = GameFieldStateHolder(
                        SavedStateHandle(),
                        GameFieldMatchParameters(mode = mode, codeLength = 4, autoModeAvailable = false),
                        "4556",
                    )
                    holder.updateAutoModeAvailability(allowed)
                    assertEquals(expected, holder.state.value.parameters.autoModeAvailable)
                    assertEquals(expected, holder.state.value.tools.autoExcludeEnabled)
                    holder.updateAutoModeAvailability(false)
                    assertFalse(holder.state.value.tools.autoExcludeEnabled)
                }
                for (style in RemoteFriendPlayStyle.entries) {
                    val snapshot = OnlineDuelSnapshotState(
                        sessionId = "a05-$style", revision = 2, phase = "active",
                        currentTurn = "player", winner = null, playStyle = style,
                        codeLength = 4, attemptLimit = 12, allowDuplicates = true,
                        attempts = listOf(
                            OnlineDuelAttemptState("player", 2, 1, "0550"),
                            OnlineDuelAttemptState("player", 0, 2, "0120"),
                        ),
                    )
                    val typed = OnlineDuelEditorState.empty(4).reduce(GameFieldEvent.DigitEntered('4'))
                    val projected = buildOnlineDuelGameFieldState(
                        snapshot, mapOf(1 to "0550", 2 to "0120"), typed,
                        inputEnabled = true, modeLabel = style.name, turnLabel = "A05",
                        autoModeAvailable = allowed,
                    )
                    assertEquals(expected, projected.parameters.autoModeAvailable)
                    assertEquals(expected, projected.tools.autoExcludeEnabled)
                    val revoked = typed.reduce(GameFieldEvent.AutoExcludeChanged(true), autoModeAvailable = false)
                    assertEquals(typed.input, revoked.input)
                    assertFalse(buildOnlineDuelGameFieldState(
                        snapshot, mapOf(1 to "0550", 2 to "0120"), revoked,
                        inputEnabled = true, modeLabel = style.name, turnLabel = "A05",
                        autoModeAvailable = false,
                    ).tools.autoExcludeEnabled)
                }
            }
            val free = repository.loadState(now)
            assertEquals(120, free.coins)
            verify(free, false)
            verify(repository.activateProduct(MonetizationProductType.REMOVE_ADS), false)
            repository.deactivateProduct(MonetizationProductType.REMOVE_ADS)
            verify(repository.activateProduct(MonetizationProductType.PRO_SUBSCRIPTION), true)
            verify(repository.deactivateProduct(MonetizationProductType.PRO_SUBSCRIPTION), false)
            verify(repository.activateProduct(MonetizationProductType.PRO_PLUS_SUBSCRIPTION), true)
            verify(repository.deactivateProduct(MonetizationProductType.PRO_PLUS_SUBSCRIPTION), false)
            assertTrue(repository.buyTemporaryPro(now))
            val temporary = repository.loadState(now)
            val expiry = temporary.temporaryProExpiresAtMs
            assertTrue(expiry > now)
            verify(temporary, true)
            now = expiry
            verify(repository.loadState(now), false)
            now = expiry + 1
            verify(repository.loadState(now), false)
            val server = repository.loadState(now).withServerPaidEntitlements(
                MonetizationEntitlements(false, true, false),
            )
            assertTrue(server.proSubscriptionActive)
            assertFalse(server.proPlusSubscriptionActive)
            verify(server, true)
        }
    }
}
