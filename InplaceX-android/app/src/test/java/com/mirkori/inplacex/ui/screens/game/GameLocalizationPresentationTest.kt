package com.mirkori.inplacex.ui.screens.game

import com.mirkori.inplacex.core.model.AnalysisCellState
import com.mirkori.inplacex.core.model.GameStatus
import com.mirkori.inplacex.ui.screens.race.raceAnalysisCellStateText
import com.mirkori.inplacex.ui.screens.race.raceStatusText
import org.junit.Assert.assertEquals
import org.junit.Test

class GameLocalizationPresentationTest {
    @Test
    fun `race status and analysis state use typed localization mappings`() {
        assertEquals(
            listOf(
                "localized<game.race.status.in_progress>",
                "localized<game.race.status.won>",
                "localized<game.race.status.lost>",
            ),
            GameStatus.values().map { raceStatusText(it, ::translated) },
        )
        assertEquals(
            listOf(
                "localized<game.race.matrix.state.empty>",
                "localized<game.race.matrix.state.no>",
                "localized<game.race.matrix.state.maybe>",
                "localized<game.race.matrix.state.yes>",
            ),
            AnalysisCellState.values().map { raceAnalysisCellStateText(it, ::translated) },
        )
    }

    private fun translated(key: String): String = "localized<$key>"
}
