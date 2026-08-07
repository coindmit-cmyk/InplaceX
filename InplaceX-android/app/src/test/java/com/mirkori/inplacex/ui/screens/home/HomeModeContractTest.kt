package com.mirkori.inplacex.ui.screens.home

import com.mirkori.inplacex.platform.config.AppConfigCatalog
import com.mirkori.inplacex.ui.screens.game.TypeGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HomeModeContractTest {

    @Test
    fun raceIsUnlimitedAtTheModeLayerAndKeepsAValidEngineConfig() {
        val mode = AppConfigCatalog.gameModes.first { it.id == "pve_race" }
        val params = mode.toFieldParams(TypeGame.RaceMatch)

        assertNull(mode.moveLimit)
        assertEquals(12, mode.config.attemptLimit)
        assertEquals(0, params.limitMoves)
    }

    @Test
    fun localBotDuelHasNeitherMoveNorTurnTimer() {
        val mode = AppConfigCatalog.gameModes.first { it.id == "pvp_bot_duel" }
        val params = mode.toFieldParams(TypeGame.DuelMatch)

        assertNull(mode.moveLimit)
        assertNull(mode.turnTimeLimitSeconds)
        assertNull(mode.config.turnTimeLimitSeconds)
        assertEquals(0, params.limitMoves)
        assertEquals(0, params.timeMove)
        assertFalse(params.useHints)
    }
}
