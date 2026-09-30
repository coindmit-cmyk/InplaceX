package com.mirkori.inplacex.core.model

import org.junit.Assert.assertThrows
import org.junit.Test

class GameConfigTest {

    @Test
    fun `unique digit mode cannot exceed the digit alphabet`() {
        assertThrows(IllegalArgumentException::class.java) {
            GameConfig(
                codeLength = 11,
                allowDuplicates = false,
                attemptLimit = 20,
            )
        }
    }

    @Test
    fun `duplicate digit mode supports the full canonical length range`() {
        GameConfig(
            codeLength = 20,
            allowDuplicates = true,
            attemptLimit = 40,
        )
    }

    @Test
    fun `turn limit is either absent or positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            GameConfig(turnTimeLimitSeconds = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GameConfig(turnTimeLimitSeconds = -1)
        }

        GameConfig(turnTimeLimitSeconds = null)
        GameConfig(turnTimeLimitSeconds = 1)
    }
}
