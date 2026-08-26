package com.mirkori.inplacex.ui.screens.home

import com.mirkori.inplacex.platform.config.AppConfigCatalog
import com.mirkori.inplacex.core.bot.BotDifficulty
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
        assertEquals(BotDifficulty.EASY, mode.botDifficulty)
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

    @Test
    fun selectedSecretLengthIsAppliedToRaceAndDuelFieldParameters() {
        val race = AppConfigCatalog.gameModes.first { it.id == "pve_race" }
            .withCodeLength(7)
        val duel = AppConfigCatalog.gameModes.first { it.id == "pvp_bot_duel" }
            .withCodeLength(9)

        assertEquals(7, race.toFieldParams(TypeGame.RaceMatch).lenSecret)
        assertEquals(9, duel.toFieldParams(TypeGame.DuelMatch).lenSecret)
        assertEquals(9, localBotDuelConfig(duel).codeLength)
    }

    @Test
    fun homeSecretLengthStaysInsideSupportedMatchRange() {
        assertEquals(4, selectHomeCodeLength(3))
        assertEquals(6, selectHomeCodeLength(6))
        assertEquals(10, selectHomeCodeLength(11))
    }

    @Test
    fun raceResultExplainsWhenTheOpponentSolvedTheSharedSecret() {
        assertEquals("home.race.result.opponent_title", raceResultTitleKey(false, true))
        assertEquals("home.race.result.opponent_message", raceResultMessageKey(false, true))
        assertEquals("home.race.result.loss_title", raceResultTitleKey(false, false))
    }
}
