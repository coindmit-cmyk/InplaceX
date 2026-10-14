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

    private fun mode(turnTimeLimitSeconds: Int?): GameModeDefinition = GameModeDefinition(
        id = "test-mode",
        titleKey = "test.title",
        subtitleKey = "test.subtitle",
        config = GameConfig(),
        opponentKind = OpponentKind.BOT,
        hintsEnabled = true,
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
