package com.mirkori.inplacex.backend.bot

import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.core.engine.GuessValidator
import com.mirkori.inplacex.core.engine.ScoreCalculator
import com.mirkori.inplacex.core.engine.SecretGenerator
import com.mirkori.inplacex.core.model.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerBotPlayerTest {

    @Test
    fun productionEntropyCannotBeRecreatedFromPublicMatchIdentifiers() {
        val config = GameConfig(
            codeLength = 6,
            allowDuplicates = true,
            attemptLimit = 60,
            forbidAllSameDigitsGuess = true,
            maxConsecutiveDuplicateDigits = 3,
        )
        val sessionId = "00000000-0000-0000-0000-000000000001"
        val playerId = "public-player-id"
        val legacySecret = SecretGenerator.generate(
            config.copy(seed = sessionId.hashCode().toLong()),
        )
        val legacyBrainSeed = playerId.hashCode().toLong() xor sessionId.hashCode().toLong()

        val productionBots = List(16) {
            ServerBotPlayer.create(
                config = config,
                difficulty = BotDifficulty.MEDIUM,
            )
        }
        val productionSecrets = productionBots.map(ServerBotPlayer::revealSecret)
        val productionBrainSeeds = List(16) { ProductionServerBotEntropy.nextBrainSeed() }

        assertTrue(productionSecrets.all { GuessValidator.validate(it, config) })
        assertTrue("production secrets must use fresh entropy", productionSecrets.toSet().size > 1)
        assertFalse(
            "public identifiers must not determine every secret",
            productionSecrets.all { it == legacySecret },
        )
        assertEquals(productionBrainSeeds.size, productionBrainSeeds.toSet().size)
        assertFalse(productionBrainSeeds.contains(legacyBrainSeed))
    }

    @Test
    fun productionSecretsPreserveEverySupportedDuplicateRule() {
        val configs = listOf(
            GameConfig(codeLength = 10, allowDuplicates = false, attemptLimit = 60),
            GameConfig(
                codeLength = 8,
                allowDuplicates = true,
                attemptLimit = 60,
                forbidAdjacentDuplicates = true,
            ),
            GameConfig(
                codeLength = 8,
                allowDuplicates = true,
                attemptLimit = 60,
                forbidTripleDuplicates = true,
            ),
            GameConfig(
                codeLength = 10,
                allowDuplicates = true,
                attemptLimit = 60,
                maxConsecutiveDuplicateDigits = 2,
            ),
        )

        configs.forEach { config ->
            repeat(32) {
                assertTrue(
                    GuessValidator.validate(
                        ProductionServerBotEntropy.generateSecret(config),
                        config,
                    ),
                )
            }
        }
    }

    @Test
    fun generatedSecretFollowsRulesAndIncomingGuessIsScored() {
        val config = GameConfig(
            codeLength = 6,
            allowDuplicates = true,
            attemptLimit = 60,
            forbidAllSameDigitsGuess = true,
        )
        val bot = DeterministicServerBotPlayerFactory.create(
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
        val bot = DeterministicServerBotPlayerFactory.create(
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
        val bot = DeterministicServerBotPlayerFactory.create(
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
