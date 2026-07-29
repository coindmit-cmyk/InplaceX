package com.mirkori.inplacex.ui.screens.social

import com.mirkori.inplacex.platform.online.OnlineDuelAttemptState
import com.mirkori.inplacex.platform.online.OnlineDuelSnapshotState
import com.mirkori.inplacex.platform.online.RemoteFriendPlayStyle
import com.mirkori.inplacex.ui.screens.game.state.GameFieldMode
import com.mirkori.inplacex.ui.screens.game.state.GameFieldEvent
import com.mirkori.inplacex.ui.screens.game.state.GameFieldTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineDuelGameFieldTest {

    @Test
    fun manualYesPrefillsEveryFollowingOnlineAttempt() {
        val editor = OnlineDuelEditorState.empty(codeLength = 4)
            .reduce(GameFieldEvent.ToolSelected(GameFieldTool.YES))
            .changeManualMark(symbol = '6', position = 2)
            .reduce(GameFieldEvent.DigitEntered('4'))
            .afterAcceptedAttempt(count = 1)

        assertNull(editor.input.slots[0])
        assertEquals('6', editor.input.slots[2])
        assertEquals(1, editor.acceptedAttemptCount)
    }

    @Test
    fun authoritativeSnapshotUsesSharedGameFieldAttemptAndMoveState() {
        val snapshot = OnlineDuelSnapshotState(
            sessionId = "00000000-0000-0000-0000-000000000001",
            revision = 4,
            phase = "active",
            currentTurn = "player",
            winner = null,
            codeLength = 4,
            attemptLimit = 9,
            allowDuplicates = false,
            attempts = listOf(
                OnlineDuelAttemptState("player", exactMatches = 2, number = 1),
                OnlineDuelAttemptState("opponent", exactMatches = 1, number = 2),
            ),
        )

        val uiState = buildOnlineDuelGameFieldState(
            snapshot = snapshot,
            knownPlayerGuesses = mapOf(1 to "4060"),
            editor = OnlineDuelEditorState.empty(4),
            inputEnabled = true,
            modeLabel = "Online duel",
            turnLabel = "Your turn",
        )

        assertEquals(1, uiState.match.attempts.size)
        assertEquals("4060", uiState.match.attempts.single().guess)
        assertEquals(2, uiState.match.attempts.single().score)
        assertEquals(8, uiState.match.attemptsLeft)
        assertEquals(2, uiState.evidence.acceptedAttempts.single().score)
    }

    @Test
    fun raceSnapshotShowsUnlimitedMovesAndLiveTotalTimer() {
        val snapshot = OnlineDuelSnapshotState(
            sessionId = "00000000-0000-0000-0000-000000000001",
            revision = 2,
            phase = "active",
            currentTurn = null,
            winner = null,
            playStyle = RemoteFriendPlayStyle.RACE,
            codeLength = 6,
            attemptLimit = null,
            allowDuplicates = true,
            maxConsecutiveDuplicateDigits = 3,
            attempts = emptyList(),
        )

        val uiState = buildOnlineDuelGameFieldState(
            snapshot = snapshot,
            knownPlayerGuesses = emptyMap(),
            editor = OnlineDuelEditorState.empty(6),
            inputEnabled = true,
            modeLabel = "Online race",
            turnLabel = "Both play",
            elapsedSeconds = 12,
            totalTimeLimitSeconds = 600,
        )

        assertEquals(GameFieldMode.RACE, uiState.parameters.mode)
        assertEquals(600, uiState.parameters.totalTimeLimitSeconds)
        assertEquals(12, uiState.timers.elapsedSeconds)
        assertEquals(true, uiState.route.movesUnlimited)
        assertNull(uiState.route.configuredMoveLimit)
    }
}
