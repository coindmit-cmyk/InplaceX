package com.mirkori.inplacex.core.model

import org.junit.Assert.assertEquals
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

    @Test
    fun `duplicate mode accepts both documented length boundaries and rejects out of range lengths`() {
        val minimum = GameConfig(codeLength = 4, allowDuplicates = true, attemptLimit = 1)
        val maximum = GameConfig(codeLength = 20, allowDuplicates = true, attemptLimit = 1)

        assertEquals(4, minimum.codeLength)
        assertEquals(20, maximum.codeLength)

        assertThrows(IllegalArgumentException::class.java) {
            GameConfig(codeLength = 3, allowDuplicates = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GameConfig(codeLength = 21, allowDuplicates = true)
        }
    }

    @Test
    fun `unique digit mode accepts the ten decimal digits`() {
        val config = GameConfig(codeLength = 10, allowDuplicates = false, attemptLimit = 1)

        assertEquals(10, config.codeLength)
    }

    @Test
    fun `attempt limit must be positive and configured values are retained`() {
        assertThrows(IllegalArgumentException::class.java) {
            GameConfig(attemptLimit = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GameConfig(attemptLimit = -1)
        }

        val minimum = GameConfig(attemptLimit = 1)
        val configured = GameConfig(attemptLimit = 7)
        assertEquals(1, minimum.attemptLimit)
        assertEquals(7, configured.attemptLimit)
    }

    @Test
    fun `copy enforces documented length and attempt constraints`() {
        val config = GameConfig(codeLength = 4, allowDuplicates = true, attemptLimit = 1)

        assertThrows(IllegalArgumentException::class.java) { config.copy(codeLength = 3) }
        assertThrows(IllegalArgumentException::class.java) { config.copy(codeLength = 21) }
        assertThrows(IllegalArgumentException::class.java) { config.copy(attemptLimit = 0) }
        assertThrows(IllegalArgumentException::class.java) { config.copy(attemptLimit = -1) }

        val copied = config.copy(codeLength = 20, attemptLimit = 7)
        assertEquals(20, copied.codeLength)
        assertEquals(7, copied.attemptLimit)
    }
}
