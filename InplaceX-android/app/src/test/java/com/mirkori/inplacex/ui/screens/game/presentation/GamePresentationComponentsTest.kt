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
import com.mirkori.inplacex.ui.theme.finalGameFieldMetrics
import com.mirkori.inplacex.ui.common.AnalysisCellVisualState
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
    fun `compact attempt line keeps the whole guess and score`() {
        assertEquals("1234" to 2, parseAttemptLine("1234 -> 2"))
        assertEquals("1234567890" to 10, parseAttemptLine("1234567890 -> 10"))
    }

    @Test
    fun `gameplay metrics preserve approved horizontal proportions through ten digits`() {
        assertEquals(0.40f, finalGameFieldMetrics(4, compactHeight = false).attemptsWeight)
        assertEquals(0.37f, finalGameFieldMetrics(6, compactHeight = false).attemptsWeight)
        assertEquals(0.32f, finalGameFieldMetrics(8, compactHeight = false).attemptsWeight)
        assertEquals(0.36f, finalGameFieldMetrics(10, compactHeight = false).attemptsWeight)
        assertEquals(0.64f, finalGameFieldMetrics(10, compactHeight = false).matrixWeight)
    }

    @Test
    fun `analysis accessibility states use localized catalog keys`() {
        assertEquals(
            "game.race.matrix.state.locked_no",
            analysisCellStateText(AnalysisCellVisualState.LOCKED_NO) { it },
        )
        assertEquals(
            "game.race.matrix.state.locked_yes",
            analysisCellStateText(AnalysisCellVisualState.LOCKED_EXACT) { it },
        )
        assertEquals(
            "game.race.matrix.state.disabled",
            analysisCellStateText(AnalysisCellVisualState.DISABLED) { it },
        )
    }

    @Test
    fun `compact height reduces vertical metrics without changing board proportions`() {
        val normal = finalGameFieldMetrics(8, compactHeight = false)
        val compact = finalGameFieldMetrics(8, compactHeight = true)

        assertEquals(normal.attemptsWeight, compact.attemptsWeight)
        assertEquals(normal.matrixWeight, compact.matrixWeight)
        assertTrue(compact.inputSlotHeight < normal.inputSlotHeight)
        assertTrue(compact.topPanelMinHeight < normal.topPanelMinHeight)
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
