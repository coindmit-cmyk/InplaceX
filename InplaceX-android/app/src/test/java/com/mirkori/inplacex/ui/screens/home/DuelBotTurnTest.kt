package com.mirkori.inplacex.ui.screens.home

import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.core.bot.BotSolver
import com.mirkori.inplacex.core.model.GameConfig
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuelBotTurnTest {

    @Test
    fun productionSixDigitBotCompletesItsFirstTurn() {
        repeat(500) { seed ->
            val solver = BotSolver(
                config = GameConfig(
                    codeLength = 6,
                    allowDuplicates = true,
                    attemptLimit = 999,
                    forbidAllSameDigitsGuess = true,
                ),
                difficulty = BotDifficulty.MEDIUM,
                seed = seed.toLong(),
            )

            val result = resolveDuelBotTurn(
                playerSecret = "123456",
                codeLength = 6,
                nextGuess = { solver.nextTurn().guess },
                registerFeedback = solver::registerFeedback,
                confirmedPositions = solver::confirmedPositionsCount,
            )

            assertTrue("seed=$seed returned $result", result is DuelBotTurnResult.Completed)
        }
    }

    @Test
    fun successfulTurnScoresAndRegistersTheBotsGuess() {
        var registeredGuess: String? = null
        var registeredScore: Int? = null

        val result = resolveDuelBotTurn(
            playerSecret = "012345",
            codeLength = 6,
            nextGuess = { "092345" },
            registerFeedback = { guess, score ->
                registeredGuess = guess
                registeredScore = score
            },
            confirmedPositions = { 3 },
        )

        assertEquals(
            DuelBotTurnResult.Completed(score = 5, confirmedPositions = 3),
            result,
        )
        assertEquals("092345", registeredGuess)
        assertEquals(5, registeredScore)
    }

    @Test
    fun invalidRestoredSecretBecomesARecoverableFailure() {
        var botWasCalled = false

        val result = resolveDuelBotTurn(
            playerSecret = "",
            codeLength = 6,
            nextGuess = {
                botWasCalled = true
                "092345"
            },
            registerFeedback = { _, _ -> },
            confirmedPositions = { 0 },
        )

        assertTrue(result is DuelBotTurnResult.Failed)
        assertFalse(botWasCalled)
    }

    @Test
    fun botFailureDoesNotEscapeTheTurnBoundary() {
        val result = resolveDuelBotTurn(
            playerSecret = "012345",
            codeLength = 6,
            nextGuess = { error("solver state is inconsistent") },
            registerFeedback = { _, _ -> },
            confirmedPositions = { 0 },
        )

        assertTrue(result is DuelBotTurnResult.Failed)
    }

    @Test(expected = CancellationException::class)
    fun cancellationStillCancelsTheTurn() {
        resolveDuelBotTurn(
            playerSecret = "012345",
            codeLength = 6,
            nextGuess = { throw CancellationException("test cancellation") },
            registerFeedback = { _, _ -> },
            confirmedPositions = { 0 },
        )
    }
}
