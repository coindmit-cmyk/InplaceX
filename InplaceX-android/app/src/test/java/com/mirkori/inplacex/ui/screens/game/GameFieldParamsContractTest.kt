package com.mirkori.inplacex.ui.screens.game

import com.mirkori.inplacex.ui.screens.game.state.GameFieldMode
import org.junit.Assert.assertEquals
import org.junit.Test

class GameFieldParamsContractTest {

    @Test
    fun `route parameters preserve every game rule`() {
        val parameters = GameFieldParams(
            typeGame = TypeGame.DuelMatch,
            useHints = false,
            useBoosts = true,
            timeAll = 300,
            timeMove = 45,
            limitMoves = 17,
            lenSecret = 8,
            allowDuplicates = true,
            forbidAllSameDigitsGuess = false,
            forbidAdjacentDuplicates = true,
            forbidTripleDuplicates = true,
            maxConsecutiveDuplicateDigits = 2,
        ).toMatchParameters(autoModeAvailable = false)

        assertEquals(GameFieldMode.DUEL, parameters.mode)
        assertEquals(8, parameters.codeLength)
        assertEquals(17, parameters.attemptLimit)
        assertEquals(true, parameters.allowDuplicates)
        assertEquals(false, parameters.forbidAllSameDigitsGuess)
        assertEquals(true, parameters.forbidAdjacentDuplicates)
        assertEquals(true, parameters.forbidTripleDuplicates)
        assertEquals(2, parameters.maxConsecutiveDuplicateDigits)
        assertEquals(300, parameters.totalTimeLimitSeconds)
        assertEquals(45, parameters.turnTimeLimitSeconds)
        assertEquals(false, parameters.hintsEnabled)
        assertEquals(true, parameters.boostsEnabled)
        assertEquals(false, parameters.autoModeAvailable)
    }
}
