package com.mirkori.inplacex.ui.screens.game.presentation

import androidx.lifecycle.SavedStateHandle
import com.mirkori.inplacex.core.engine.GuessValidationReason
import com.mirkori.inplacex.core.match.MatchFeedback
import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.ui.screens.game.state.GameFieldEvent
import com.mirkori.inplacex.ui.screens.game.state.GameFieldManualMark
import com.mirkori.inplacex.ui.screens.game.state.GameFieldManualMarkType
import com.mirkori.inplacex.ui.screens.game.state.GameFieldMatchParameters
import com.mirkori.inplacex.ui.screens.game.state.GameFieldStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePresentationComponentsTest {
    @Test
    fun `analysis uses the latest mark for a matching cell only`() {
        val marks = listOf(
            GameFieldManualMark(0, '1', GameFieldManualMarkType.NO),
            GameFieldManualMark(0, '1', GameFieldManualMarkType.YES),
            GameFieldManualMark(1, '2', GameFieldManualMarkType.MAYBE),
        )

        assertEquals(GameFieldManualMarkType.YES, analysisMarkFor(marks, '1', 0))
        assertEquals(GameFieldManualMarkType.MAYBE, analysisMarkFor(marks, '2', 1))
        assertNull(analysisMarkFor(marks, '1', 1))
    }

    @Test
    fun `input is enabled only while the match is active`() {
        assertTrue(isInputEnabled(MatchPhase.ACTIVE))
        assertFalse(isInputEnabled(MatchPhase.WON))
        assertFalse(isInputEnabled(MatchPhase.LOST))
    }

    @Test
    fun `all same rejection keeps its localized catalog key`() {
        val status = feedbackText(
            MatchFeedback.ValidationRejected(GuessValidationReason.ALL_SAME_DIGITS),
            text = { it },
        )

        assertEquals("game.validation.all_same_digits", status)
    }

    @Test
    fun `active presentation fills exact matches inferred from hints and attempt`() {
        val stateHolder = GameFieldStateHolder(
            savedStateHandle = SavedStateHandle(),
            parameters = GameFieldMatchParameters(codeLength = 4, attemptLimit = 12),
            initialSecret = "4167",
        )
        stateHolder.dispatch(GameFieldEvent.DigitHintRequested(0))
        stateHolder.dispatch(GameFieldEvent.OpenPositionHintRequested(0))
        "060".forEach { stateHolder.dispatch(GameFieldEvent.DigitEntered(it)) }
        stateHolder.dispatch(GameFieldEvent.GuessSubmitted)

        assertEquals(
            listOf('4', null, '6', null),
            displayedGuessSlots(stateHolder.state.value),
        )
    }

    @Test
    fun `manual yes remains a hypothesis and immediately appears in the guess slot`() {
        val stateHolder = GameFieldStateHolder(
            savedStateHandle = SavedStateHandle(),
            parameters = GameFieldMatchParameters(codeLength = 4),
            initialSecret = "1234",
        )
        stateHolder.dispatch(
            GameFieldEvent.ManualMarkChanged(
                position = 0,
                symbol = '9',
                type = GameFieldManualMarkType.YES,
            ),
        )

        assertEquals(listOf('9', null, null, null), displayedGuessSlots(stateHolder.state.value))
        assertTrue(stateHolder.state.value.evidence.provenFacts.isEmpty())
    }
}
