package com.mirkori.inplacex.ui.screens.game

import com.mirkori.inplacex.core.engine.GuessValidationReason
import com.mirkori.inplacex.core.match.MatchFeedback
import com.mirkori.inplacex.core.match.MatchPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugGameLocalizationPresentationTest {
    @Test
    fun `debug feedback selects localization by typed validation reason`() {
        val result = debugFeedbackText(
            feedback = MatchFeedback.ValidationRejected(GuessValidationReason.ALL_SAME_DIGITS),
            codeLength = 6,
            text = ::translated,
        )

        assertEquals("localized<game.validation.all_same_digits>", result)
    }

    @Test
    fun `debug invalid length feedback substitutes configured code length`() {
        val result = debugFeedbackText(
            feedback = MatchFeedback.ValidationRejected(GuessValidationReason.INVALID_LENGTH),
            codeLength = 7,
            text = { key ->
                when (key) {
                    "game.status.enter_digits" -> "localized<{count}>"
                    else -> "unexpected<$key>"
                }
            },
        )

        assertEquals("localized<7>", result)
    }

    @Test
    fun `debug phase selects localization without enum rendering`() {
        assertEquals(
            listOf(
                "localized<game.debug_screen.phase.not_started>",
                "localized<game.debug_screen.phase.active>",
                "localized<game.debug_screen.phase.won>",
                "localized<game.debug_screen.phase.lost>",
            ),
            MatchPhase.values().map { debugPhaseText(it, ::translated) },
        )
    }

    private fun translated(key: String): String = "localized<$key>"
}
