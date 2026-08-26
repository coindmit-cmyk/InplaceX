package com.mirkori.inplacex.ui.screens.social

import com.mirkori.inplacex.core.analysis.ProvenFact
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
    fun fiveEnteredDigitsSkipTwoAutomaticallyConfirmedMiddlePositions() {
        val facts = setOf(ProvenFact.exactMatch(3, '5'), ProvenFact.exactMatch(4, '5'))
        var editor = OnlineDuelEditorState.empty(7)

        "11111".forEach { editor = editor.reduce(GameFieldEvent.DigitEntered(it), facts) }

        assertEquals("1115511", editor.input.guessOrNull(facts))
        assertNull(editor.input.slots[3])
        assertNull(editor.input.slots[4])
        assertEquals(editor, editor.reduce(GameFieldEvent.DigitEntered('9'), facts))
    }

    @Test
    fun inferredOnlineFactsAreUsedAgainAfterAnAcceptedAttempt() {
        val snapshot = OnlineDuelSnapshotState(
            sessionId = "00000000-0000-4000-8000-000000000001",
            revision = 4,
            phase = "active",
            currentTurn = "player",
            winner = null,
            codeLength = 7,
            attemptLimit = null,
            allowDuplicates = true,
            attempts = listOf(
                OnlineDuelAttemptState("player", 2, 1, "0125501"),
                OnlineDuelAttemptState("player", 0, 2, "0123401"),
            ),
        )
        var editor = OnlineDuelEditorState.empty(7).afterAcceptedAttempt(2)
        val facts = buildOnlineDuelGameFieldState(
            snapshot, snapshot.knownPlayerGuesses(), editor, true, "Online", "Your turn",
        ).evidence.deduction.provenFacts
        assertEquals(setOf(3, 4), facts.filter { it.isExactMatch }.map { it.position }.toSet())

        repeat(2) {
            "11111".forEach { digit -> editor = editor.reduce(GameFieldEvent.DigitEntered(digit), facts) }
            assertEquals("1115511", editor.input.guessOrNull(facts))
            editor = editor.afterAcceptedAttempt(editor.acceptedAttemptCount + 1)
        }
    }

    @Test
    fun backspaceSkipsBothInferredAndManuallyConfirmedPositions() {
        val facts = setOf(ProvenFact.exactMatch(0, '5'), ProvenFact.exactMatch(6, '5'))
        var editor = OnlineDuelEditorState.empty(7)
            .reduce(GameFieldEvent.ToolSelected(GameFieldTool.YES))
            .changeManualMark('5', 3)
        "1234".forEach { editor = editor.reduce(GameFieldEvent.DigitEntered(it), facts) }
        assertEquals("5125345", editor.input.guessOrNull(facts))

        repeat(4) { editor = editor.reduce(GameFieldEvent.BackspacePressed, facts) }
        assertEquals(listOf(null, null, null, '5', null, null, null), editor.input.slots)
        val cleared = editor
        assertEquals(cleared, editor.reduce(GameFieldEvent.BackspacePressed, facts))
        "4321".forEach { editor = editor.reduce(GameFieldEvent.DigitEntered(it), facts) }
        assertEquals("5435215", editor.input.guessOrNull(facts))
    }

    @Test
    fun disabledAutoTableDoesNotReserveInferredPositions() {
        val facts = setOf(ProvenFact.exactMatch(3, '5'), ProvenFact.exactMatch(4, '5'))
        var editor = OnlineDuelEditorState.empty(7)
            .reduce(GameFieldEvent.AutoExcludeChanged(false))
        "1234567".forEach { editor = editor.reduce(GameFieldEvent.DigitEntered(it), facts) }
        assertEquals("1234567", editor.input.guessOrNull(emptySet()))
    }

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
                OnlineDuelAttemptState("player", exactMatches = 2, number = 1, ownGuess = "4060"),
                OnlineDuelAttemptState("opponent", exactMatches = 1, number = 2),
            ),
        )

        val uiState = buildOnlineDuelGameFieldState(
            snapshot = snapshot,
            knownPlayerGuesses = snapshot.knownPlayerGuesses(),
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
    fun reconnectSnapshotRestoresOnlyViewerOwnedGuesses() {
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
                OnlineDuelAttemptState("player", exactMatches = 2, number = 1, ownGuess = "4060"),
                OnlineDuelAttemptState("opponent", exactMatches = 1, number = 2),
            ),
        )

        assertEquals(mapOf(1 to "4060"), snapshot.knownPlayerGuesses())
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
