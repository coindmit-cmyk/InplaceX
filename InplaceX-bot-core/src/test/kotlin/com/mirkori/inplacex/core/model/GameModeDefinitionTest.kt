package com.mirkori.inplacex.core.model

import com.mirkori.inplacex.core.match.OpponentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun mode(
        config: GameConfig,
        moveLimit: Int? = config.attemptLimit,
    ) = GameModeDefinition(
        id = "test",
        titleKey = "test.title",
        subtitleKey = "test.subtitle",
        config = config,
        opponentKind = OpponentKind.BOT,
        hintsEnabled = false,
        moveLimit = moveLimit,
    )
}
