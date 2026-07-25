package com.mirkori.inplacex.ui.screens.game.state

import androidx.lifecycle.SavedStateHandle
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
}
