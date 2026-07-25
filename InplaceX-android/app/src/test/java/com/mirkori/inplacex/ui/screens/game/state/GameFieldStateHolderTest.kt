package com.mirkori.inplacex.ui.screens.game.state

import androidx.lifecycle.SavedStateHandle
import com.mirkori.inplacex.core.analysis.AcceptedAttemptEvidence
import com.mirkori.inplacex.core.analysis.EvidenceDeductionEngine
import com.mirkori.inplacex.core.analysis.ProvenFact
import com.mirkori.inplacex.core.match.MatchPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameFieldStateHolderTest {
    private val parameters = GameFieldMatchParameters(
        codeLength = 4,
        attemptLimit = 3,
        totalTimeLimitSeconds = 60,
        turnTimeLimitSeconds = 30,
    )

    @Test
    fun `restores checkpoint and durable game field state`() {
        val savedState = SavedStateHandle()
        val source = GameFieldStateHolder(savedState, parameters, initialSecret = "1234")

        "1235".forEach { source.dispatch(GameFieldEvent.DigitEntered(it)) }
        source.dispatch(GameFieldEvent.GuessSubmitted)
        source.dispatch(GameFieldEvent.DigitEntered('8'))
        source.dispatch(
            GameFieldEvent.ManualMarkChanged(
                position = 1,
                symbol = '7',
                type = GameFieldManualMarkType.MAYBE,
            ),
        )
        source.dispatch(GameFieldEvent.ProvenFactRecorded(ProvenFact.notAtPosition(2, '9')))
        source.dispatch(GameFieldEvent.ToolSelected(GameFieldTool.MAYBE))
        source.dispatch(GameFieldEvent.HintSelected(GameFieldHintMode.CHECK_POSITION))
        source.dispatch(GameFieldEvent.HintConsumed(GameFieldHintMode.CHECK_POSITION))
        source.dispatch(GameFieldEvent.BoostConsumed(GameFieldBoostMode.EXTRA_MOVES, amount = 2))
        source.dispatch(GameFieldEvent.BoostConsumed(GameFieldBoostMode.EXTRA_TIME, amount = 15))
        source.dispatch(GameFieldEvent.TimerTicked(seconds = 8))

        val restored = GameFieldStateHolder(savedState, parameters, initialSecret = "9999")
        val state = restored.state.value

        assertEquals("1234", state.match.debugSecret)
        assertEquals(MatchPhase.ACTIVE, state.match.phase)
        assertEquals(listOf("1235"), state.match.attempts.map { it.guess })
        assertEquals('8', state.input.slots.first())
        assertEquals(
            listOf(GameFieldManualMark(1, '7', GameFieldManualMarkType.MAYBE)),
            state.manualMarks,
        )
        assertTrue(state.evidence.provenFacts.contains(ProvenFact.notAtPosition(2, '9')))
        assertEquals(GameFieldTool.MAYBE, state.tools.selectedTool)
        assertEquals(GameFieldHintMode.CHECK_POSITION, state.tools.selectedHint)
        assertEquals(8, state.timers.elapsedSeconds)
        assertEquals(15, state.timers.bonusTimeSeconds)
        assertEquals(1, state.counters.checkPositionHintUses)
        assertEquals(1, state.counters.extraMovesBoostUses)
        assertEquals(2, state.counters.bonusMoves)
        assertEquals(GameFieldStatus.Idle, state.status)
    }

    @Test
    fun `invalid saved checkpoint starts a new match`() {
        val savedState = SavedStateHandle()
        GameFieldStateHolder(savedState, parameters, initialSecret = "1234")
        savedState["game_field_checkpoint"] = arrayListOf("12x4", MatchPhase.ACTIVE.name, "0")

        val restored = GameFieldStateHolder(savedState, parameters, initialSecret = "4321")

        assertEquals("4321", restored.state.value.match.debugSecret)
        assertTrue(restored.state.value.match.attempts.isEmpty())
        assertTrue(restored.state.value.input.slots.all { it == null })
    }

    @Test
    fun `hints boosts and timers update through public events`() {
        val source = GameFieldStateHolder(
            SavedStateHandle(),
            parameters.copy(attemptLimit = 2, totalTimeLimitSeconds = 60),
            initialSecret = "1234",
        )

        source.dispatch(GameFieldEvent.HintSelected(GameFieldHintMode.OPEN_POSITION))
        source.dispatch(GameFieldEvent.HintConsumed(GameFieldHintMode.OPEN_POSITION))
        source.dispatch(GameFieldEvent.HintConsumed(GameFieldHintMode.CHECK_DIGIT))
        source.dispatch(GameFieldEvent.HintConsumed(GameFieldHintMode.CHECK_POSITION))
        source.dispatch(GameFieldEvent.BoostConsumed(GameFieldBoostMode.EXTRA_MOVES, amount = 2))
        source.dispatch(GameFieldEvent.BoostConsumed(GameFieldBoostMode.EXTRA_TIME, amount = 15))
        source.dispatch(GameFieldEvent.TimerTicked(seconds = 7))

        val state = source.state.value
        assertEquals(GameFieldHintMode.OPEN_POSITION, state.tools.selectedHint)
        assertEquals(1, state.counters.openPositionHintUses)
        assertEquals(1, state.counters.checkDigitHintUses)
        assertEquals(1, state.counters.checkPositionHintUses)
        assertEquals(1, state.counters.extraMovesBoostUses)
        assertEquals(1, state.counters.extraTimeBoostUses)
        assertEquals(2, state.counters.bonusMoves)
        assertEquals(7, state.timers.elapsedSeconds)
        assertEquals(7, state.timers.turnElapsedSeconds)
        assertEquals(15, state.timers.bonusTimeSeconds)
        assertEquals(4, state.match.attemptsLeft)
    }

    @Test
    fun `manual marks are replaceable and removable through public events`() {
        val source = GameFieldStateHolder(SavedStateHandle(), parameters, initialSecret = "1234")

        source.dispatch(
            GameFieldEvent.ManualMarkChanged(
                position = 2,
                symbol = '6',
                type = GameFieldManualMarkType.NO,
            ),
        )
        source.dispatch(
            GameFieldEvent.ManualMarkChanged(
                position = 2,
                symbol = '6',
                type = GameFieldManualMarkType.YES,
            ),
        )

        assertEquals(
            listOf(GameFieldManualMark(2, '6', GameFieldManualMarkType.YES)),
            source.state.value.manualMarks,
        )
        assertEquals(1, source.state.value.evidence.hypotheses.size)

        source.dispatch(
            GameFieldEvent.ManualMarkChanged(
                position = 2,
                symbol = '6',
                type = null,
            ),
        )

        assertTrue(source.state.value.manualMarks.isEmpty())
        assertTrue(source.state.value.evidence.hypotheses.isEmpty())
    }

    @Test
    fun `restart clears durable match and helper state`() {
        val source = GameFieldStateHolder(SavedStateHandle(), parameters, initialSecret = "1234")

        source.dispatch(GameFieldEvent.DigitEntered('9'))
        source.dispatch(GameFieldEvent.ManualMarkChanged(1, '8', GameFieldManualMarkType.MAYBE))
        source.dispatch(GameFieldEvent.HintConsumed(GameFieldHintMode.CHECK_POSITION))
        source.dispatch(GameFieldEvent.BoostConsumed(GameFieldBoostMode.EXTRA_TIME, amount = 10))
        source.dispatch(GameFieldEvent.TimerTicked(seconds = 4))
        source.dispatch(GameFieldEvent.MatchRestarted)

        val state = source.state.value
        assertEquals(MatchPhase.ACTIVE, state.match.phase)
        assertTrue(state.match.attempts.isEmpty())
        assertTrue(state.input.slots.all { it == null })
        assertTrue(state.manualMarks.isEmpty())
        assertEquals(0, state.timers.elapsedSeconds)
        assertEquals(0, state.timers.bonusTimeSeconds)
        assertEquals(0, state.counters.checkPositionHintUses)
        assertEquals(0, state.counters.extraTimeBoostUses)
    }

    @Test
    fun `public submission exposes both win and loss phases`() {
        val won = GameFieldStateHolder(SavedStateHandle(), parameters, initialSecret = "1234")
        won.submitRawGuess("1234")

        assertEquals(MatchPhase.WON, won.state.value.match.phase)
        assertTrue(won.state.value.match.attempts.single().isWin)

        val lost = GameFieldStateHolder(
            SavedStateHandle(),
            parameters.copy(attemptLimit = 1),
            initialSecret = "1234",
        )
        lost.submitRawGuess("1235")

        assertEquals(MatchPhase.LOST, lost.state.value.match.phase)
        assertEquals(0, lost.state.value.match.attemptsLeft)
        assertTrue(!lost.state.value.match.attempts.single().isWin)
    }

    @Test
    fun `0000 and 4060 evidence proves six at position three`() {
        val result = EvidenceDeductionEngine(codeLength = 4).infer(
            acceptedAttempts = listOf(
                AcceptedAttemptEvidence(guess = "0000", score = 1),
                AcceptedAttemptEvidence(guess = "4060", score = 3),
            ),
            provenFacts = listOf(ProvenFact.exactMatch(position = 0, symbol = '4')),
        )

        assertTrue(result.isConsistent)
        assertEquals(setOf('6'), result.candidates[2])
        assertEquals('6', result.exactMatches[2])
        assertTrue(result.provenFacts.contains(ProvenFact.exactMatch(2, '6')))
    }
}
