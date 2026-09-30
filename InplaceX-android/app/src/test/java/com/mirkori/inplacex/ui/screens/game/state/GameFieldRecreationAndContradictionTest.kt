package com.mirkori.inplacex.ui.screens.game.state

import androidx.lifecycle.SavedStateHandle
import com.mirkori.inplacex.core.analysis.EvidenceDeductionEngine
import com.mirkori.inplacex.core.analysis.ProvenFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameFieldRecreationAndContradictionTest {
    private val parameters = GameFieldMatchParameters(
        codeLength = 4,
        attemptLimit = 12,
        totalTimeLimitSeconds = 60,
        turnTimeLimitSeconds = 30,
    )

    @Test
    fun `detached saved state restores secret attempts partial input and analysis`() {
        val savedState = SavedStateHandle()
        val source = GameFieldStateHolder(savedState, parameters, initialSecret = "1234")

        source.submitRawGuess("5678")
        source.dispatch(GameFieldEvent.DigitEntered('9'))
        source.dispatch(
            GameFieldEvent.ManualMarkChanged(
                position = 1,
                symbol = '7',
                type = GameFieldManualMarkType.MAYBE,
            ),
        )
        source.dispatch(GameFieldEvent.OpenPositionHintRequested(position = 2))

        val expected = source.state.value
        val restored = GameFieldStateHolder(
            savedStateHandle = savedState.detachedCopy(),
            parameters = parameters,
            initialSecret = "9999",
        ).state.value

        assertEquals("1234", restored.match.debugSecret)
        assertEquals(expected.match.attempts, restored.match.attempts)
        assertEquals(expected.input, restored.input)
        assertEquals(expected.manualMarks, restored.manualMarks)
        assertEquals(expected.evidence.acceptedAttempts, restored.evidence.acceptedAttempts)
        assertEquals(expected.evidence.hypotheses, restored.evidence.hypotheses)
        assertEquals(expected.evidence.provenFacts, restored.evidence.provenFacts)
        assertEquals(expected.evidence.deduction, restored.evidence.deduction)
    }

    @Test
    fun `legacy saved state migrates with canonical rule defaults`() {
        val savedState = SavedStateHandle()
        GameFieldStateHolder(savedState, parameters, initialSecret = "1234")
        savedState["game_field_state_version"] = 1
        savedState["game_field_parameters"] = arrayListOf(
            GameFieldMode.RACE.name,
            "4",
            "12",
            "true",
            "60",
            "30",
            "true",
            "false",
            "true",
        )

        val restored = GameFieldSavedStateStore(savedState.detachedCopy()).restore(parameters)

        assertTrue(restored != null)
        assertEquals("1234", restored?.checkpoint?.secret)
    }

    @Test
    fun `changed rule contract refuses an incompatible saved match`() {
        val savedState = SavedStateHandle()
        GameFieldStateHolder(savedState, parameters, initialSecret = "1234")
        val changedRules = parameters.copy(maxConsecutiveDuplicateDigits = 3)

        val restored = GameFieldSavedStateStore(savedState.detachedCopy()).restore(changedRules)

        assertEquals(null, restored)
    }

    @Test
    fun `state holder enforces the configured maximum digit run`() {
        val strictParameters = parameters.copy(maxConsecutiveDuplicateDigits = 3)

        val state = GameFieldStateHolder(
            SavedStateHandle(),
            strictParameters,
            initialSecret = "1111",
        ).state.value

        assertFalse(state.match.debugSecret == "1111")
        assertEquals(4, state.match.debugSecret.length)
    }

    @Test
    fun `manual contradiction is reported without becoming an authoritative fact`() {
        val source = GameFieldStateHolder(
            SavedStateHandle(),
            parameters,
            initialSecret = "1234",
        )
        val manualRejection = ProvenFact.notAtPosition(position = 0, symbol = '1')
        val confirmedMatch = ProvenFact.exactMatch(position = 0, symbol = '1')

        source.dispatch(
            GameFieldEvent.ManualMarkChanged(
                position = 0,
                symbol = '1',
                type = GameFieldManualMarkType.NO,
            ),
        )
        source.dispatch(GameFieldEvent.OpenPositionHintRequested(position = 0))

        val state = source.state.value
        assertFalse(state.evidence.deduction.isConsistent)
        assertTrue(state.evidence.deduction.contradictions.isNotEmpty())
        assertFalse(state.evidence.provenFacts.contains(manualRejection))
        assertFalse(state.evidence.deduction.provenFacts.contains(manualRejection))
        assertTrue(state.evidence.provenFacts.contains(confirmedMatch))

        val authoritative = EvidenceDeductionEngine(parameters.codeLength).infer(
            acceptedAttempts = state.evidence.acceptedAttempts,
            provenFacts = state.evidence.provenFacts,
        )
        assertTrue(authoritative.isConsistent)
        assertTrue(authoritative.provenFacts.contains(confirmedMatch))
    }

    @Test
    fun `confirmed hints keep the authoritative analysis board consistent`() {
        val source = GameFieldStateHolder(
            SavedStateHandle(),
            parameters,
            initialSecret = "1234",
        )

        source.dispatch(GameFieldEvent.PositionHintRequested(digit = 9, position = 0))
        source.dispatch(GameFieldEvent.OpenPositionHintRequested(position = 0))
        source.dispatch(GameFieldEvent.DigitHintRequested(digit = 8))
        source.dispatch(GameFieldEvent.PositionHintRequested(digit = 1, position = 0))

        val state = source.state.value
        val authoritative = EvidenceDeductionEngine(parameters.codeLength).infer(
            acceptedAttempts = state.evidence.acceptedAttempts,
            provenFacts = state.evidence.provenFacts,
        )

        assertTrue(authoritative.isConsistent)
        assertTrue(state.evidence.provenFacts.contains(ProvenFact.notAtPosition(0, '9')))
        assertTrue(state.evidence.provenFacts.contains(ProvenFact.exactMatch(0, '1')))
        assertTrue(
            (0 until parameters.codeLength).all { position ->
                state.evidence.provenFacts.contains(ProvenFact.notAtPosition(position, '8'))
            },
        )
    }

    private fun SavedStateHandle.detachedCopy(): SavedStateHandle {
        val snapshot = keys().associateWith { key ->
            when (val value = get<Any?>(key)) {
                is ArrayList<*> -> ArrayList(value)
                else -> value
            }
        }
        return SavedStateHandle(snapshot)
    }
}
