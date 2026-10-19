package com.mirkori.inplacex.core.model

import com.mirkori.inplacex.core.match.OpponentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class GameModeDefinitionTest {

    @Test
    fun moveLimitDefaultsToTheEngineAttemptLimit() {
        val mode = mode(config = GameConfig(attemptLimit = 17))

        assertEquals(17, mode.moveLimit)
    }

    @Test
    fun nullMoveLimitRepresentsAnUnlimitedModeWithoutBreakingGameConfig() {
        val mode = mode(
            config = GameConfig(attemptLimit = 12),
            moveLimit = null,
        )

        assertNull(mode.moveLimit)
        assertEquals(12, mode.config.attemptLimit)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroMoveLimitIsRejected() {
        mode(config = GameConfig(), moveLimit = 0)
    }

    @Test
    fun constructorRequiresPositiveConfiguredTurnTimeLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            mode(turnTimeLimitSeconds = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            mode(turnTimeLimitSeconds = -1)
        }

        assertNull(mode(turnTimeLimitSeconds = null).turnTimeLimitSeconds)
        assertEquals(1, mode(turnTimeLimitSeconds = 1).turnTimeLimitSeconds)
        assertEquals(30, mode(turnTimeLimitSeconds = 30).turnTimeLimitSeconds)
    }

    @Test
    fun copyRequiresPositiveConfiguredTurnTimeLimitAndPreservesOtherFields() {
        val original = mode(turnTimeLimitSeconds = 30)
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(turnTimeLimitSeconds = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(turnTimeLimitSeconds = -1)
        }

        val unconfiguredCopy = original.copy(turnTimeLimitSeconds = null)
        assertNull(unconfiguredCopy.turnTimeLimitSeconds)
        assertCopyPreservesFields(original, unconfiguredCopy)

        val longerCopy = original.copy(turnTimeLimitSeconds = 60)
        assertEquals(60, longerCopy.turnTimeLimitSeconds)
        assertCopyPreservesFields(original, longerCopy)
    }

    private fun mode(
        config: GameConfig = GameConfig(),
        moveLimit: Int? = config.attemptLimit,
        turnTimeLimitSeconds: Int? = null,
    ): GameModeDefinition = GameModeDefinition(
        id = "test",
        titleKey = "test.title",
        subtitleKey = "test.subtitle",
        config = config,
        opponentKind = OpponentKind.BOT,
        hintsEnabled = false,
        moveLimit = moveLimit,
        turnTimeLimitSeconds = turnTimeLimitSeconds,
    )

    private fun assertCopyPreservesFields(
        original: GameModeDefinition,
        copy: GameModeDefinition,
    ) {
        assertNotSame(original, copy)
        assertEquals(original.id, copy.id)
        assertEquals(original.titleKey, copy.titleKey)
        assertEquals(original.subtitleKey, copy.subtitleKey)
        assertEquals(original.family, copy.family)
        assertSame(original.config, copy.config)
        assertEquals(original.opponentKind, copy.opponentKind)
        assertEquals(original.hintsEnabled, copy.hintsEnabled)
        assertEquals(original.totalTimeLimitSeconds, copy.totalTimeLimitSeconds)
        assertEquals(original.preMatchConfig, copy.preMatchConfig)
        assertEquals(original.botDifficulty, copy.botDifficulty)
        assertEquals(original.campaignLevelNumber, copy.campaignLevelNumber)
    }
}
