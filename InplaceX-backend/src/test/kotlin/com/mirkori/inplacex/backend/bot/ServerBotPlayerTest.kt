package com.mirkori.inplacex.backend.bot

import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.core.engine.GuessValidator
import com.mirkori.inplacex.core.engine.ScoreCalculator
import com.mirkori.inplacex.core.model.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerBotPlayerTest {

    @Test
    fun generatedSecretFollowsRulesAndIncomingGuessIsScored() {
        val config = GameConfig(
            codeLength = 6,
            allowDuplicates = true,
            attemptLimit = 60,
            forbidAllSameDigitsGuess = true,
        )
        val bot = ServerBotPlayer.create(
            config = config,
            difficulty = BotDifficulty.MEDIUM,
            secretSeed = 42L,
            brainSeed = 77L,
        )

        assertTrue(GuessValidator.validate(bot.revealSecret(), config))

        val defense = bot.scoreIncomingGuess("120056")
        assertEquals(ScoreCalculator.countExactMatches(bot.revealSecret(), "120056"), defense.exactMatches)
        assertFalse(defense.solvedSecret)
        assertEquals(1, bot.snapshot().defensiveHistory.size)
    }

    @Test
    fun pendingTurnIsStableUntilFeedbackArrives() {
        val config = GameConfig(
            codeLength = 4,
            allowDuplicates = true,
            attemptLimit = 40,
            forbidAllSameDigitsGuess = true,
        )
        val bot = ServerBotPlayer.create(
            config = config,
            difficulty = BotDifficulty.EASY,
            secret = "1234",
            brainSeed = 5L,
        )

        val firstTurn = bot.nextTurn()
        val repeatedTurn = bot.nextTurnOrNull()

        assertNotNull(repeatedTurn)
        assertEquals(firstTurn.guess, repeatedTurn?.guess)
        assertEquals(firstTurn.moveNumber, repeatedTurn?.moveNumber)

        bot.registerTurnFeedback(firstTurn.guess, 0)
        val nextTurn = bot.nextTurn()

        assertNotEquals(firstTurn.guess, nextTurn.guess)
        assertEquals(2, nextTurn.moveNumber)
    }

    @Test(timeout = 30_000)
    fun serverBotCanPlayOffensivelyAgainstOpponentSecret() {
        val config = GameConfig(
            codeLength = 10,
            allowDuplicates = true,
            attemptLimit = 100,
            forbidAllSameDigitsGuess = true,
        )
        val opponentSecret = "5294817063"
        val bot = ServerBotPlayer.create(
            config = config,
            difficulty = BotDifficulty.EXPERT,
            secret = "1039482756",
            brainSeed = 19L,
        )

        repeat(config.attemptLimit) {
            val turn = bot.nextTurn()
            val exactMatches = ScoreCalculator.countExactMatches(opponentSecret, turn.guess)
            val feedback = bot.registerTurnFeedback(turn.guess, exactMatches)
            if (feedback.solvedOpponentSecret) {
                assertEquals(opponentSecret, turn.guess)
                assertTrue(bot.snapshot().solvedOpponentSecret)
                return
            }
        }

        error("Server bot did not solve the opponent secret within the configured attempt limit")
    }
}
