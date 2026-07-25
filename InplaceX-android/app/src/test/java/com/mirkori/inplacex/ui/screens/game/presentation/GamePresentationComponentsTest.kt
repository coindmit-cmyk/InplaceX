package com.mirkori.inplacex.ui.screens.game.presentation

import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.ui.screens.game.state.GameFieldManualMark
import com.mirkori.inplacex.ui.screens.game.state.GameFieldManualMarkType
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
}
