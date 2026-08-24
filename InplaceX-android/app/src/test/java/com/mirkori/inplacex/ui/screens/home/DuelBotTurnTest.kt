package com.mirkori.inplacex.ui.screens.home

import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.core.bot.BotSolver
import com.mirkori.inplacex.core.engine.GuessValidationReason
import com.mirkori.inplacex.core.engine.GuessValidator
import com.mirkori.inplacex.core.engine.SecretGenerator
import com.mirkori.inplacex.core.model.GameConfig
import com.mirkori.inplacex.platform.config.AppConfigCatalog
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuelBotTurnTest {

    @Test
    fun localBotDuelPreservesEveryConfiguredDigitRule() {
        val mode = AppConfigCatalog.gameModes.first { it.id == "pvp_bot_duel" }
        val config = localBotDuelConfig(mode, seed = 42L)

        assertEquals(mode.config.codeLength, config.codeLength)
        assertEquals(mode.config.allowDuplicates, config.allowDuplicates)
        assertEquals(mode.config.forbidAllSameDigitsGuess, config.forbidAllSameDigitsGuess)
        assertEquals(mode.config.forbidAdjacentDuplicates, config.forbidAdjacentDuplicates)
        assertEquals(mode.config.forbidTripleDuplicates, config.forbidTripleDuplicates)
        assertEquals(mode.config.maxConsecutiveDuplicateDigits, config.maxConsecutiveDuplicateDigits)
        assertEquals(mode.config.turnTimeLimitSeconds, config.turnTimeLimitSeconds)
        assertEquals(
            GuessValidationReason.TOO_MANY_CONSECUTIVE_DUPLICATES,
            GuessValidator.validateOrReason("111123", config),
        )
        assertTrue(GuessValidator.validate("111223", config))
        assertTrue(GuessValidator.validate(SecretGenerator.generate(config), config))
    }

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
            DuelBotTurnResult.Completed(guess = "092345", score = 5, confirmedPositions = 3),
            result,
        )
        assertEquals("092345", registeredGuess)
        assertEquals(5, registeredScore)
    }

    @Test
    fun raceBotUsesThePlayersRaceRulesWithAnIndependentAttemptBudget() {
        val mode = AppConfigCatalog.gameModes.first { it.id == "pve_race" }
        val config = localBotRaceConfig(mode, seed = 73L)

        assertEquals(mode.config.codeLength, config.codeLength)
        assertEquals(mode.config.allowDuplicates, config.allowDuplicates)
        assertEquals(mode.config.forbidAllSameDigitsGuess, config.forbidAllSameDigitsGuess)
        assertEquals(73L, config.seed)
        assertEquals(999, config.attemptLimit)
    }

    @Test
    fun raceBotPaceComesFromDifficultyAndIsIndependentFromPlayerAttempts() {
        assertEquals(4_800L, raceBotReactionDelayMillis(BotDifficulty.EASY))
        assertEquals(3_600L, raceBotReactionDelayMillis(BotDifficulty.MEDIUM))
        assertEquals(2_700L, raceBotReactionDelayMillis(BotDifficulty.HARD))
        assertEquals(2_100L, raceBotReactionDelayMillis(BotDifficulty.EXPERT))
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
