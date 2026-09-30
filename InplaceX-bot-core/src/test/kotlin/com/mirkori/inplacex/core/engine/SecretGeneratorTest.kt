package com.mirkori.inplacex.core.engine

import com.mirkori.inplacex.core.model.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecretGeneratorTest {

    @Test
    fun `every generated secret satisfies the exact active rules`() {
        val configs = buildList {
            (4..20).forEach { codeLength ->
                add(
                    GameConfig(
                        codeLength = codeLength,
                        allowDuplicates = true,
                        attemptLimit = 40,
                        maxConsecutiveDuplicateDigits = 3,
                    ),
                )
            }
            (4..10).forEach { codeLength ->
                add(
                    GameConfig(
                        codeLength = codeLength,
                        allowDuplicates = false,
                        attemptLimit = 40,
                    ),
                )
            }
            add(
                GameConfig(
                    codeLength = 20,
                    allowDuplicates = true,
                    attemptLimit = 40,
                    forbidAdjacentDuplicates = true,
                ),
            )
            add(
                GameConfig(
                    codeLength = 20,
                    allowDuplicates = true,
                    attemptLimit = 40,
                    forbidTripleDuplicates = true,
                ),
            )
        }

        configs.forEachIndexed { configIndex, config ->
            repeat(50) { sample ->
                val secret = SecretGenerator.generate(
                    config.copy(seed = configIndex * 10_000L + sample),
                )
                assertNull(
                    "Invalid secret for config=$config",
                    GuessValidator.validateOrReason(secret, config),
                )
            }
        }
    }

    @Test
    fun `same seed and rules produce the same secret`() {
        val config = GameConfig(
            codeLength = 10,
            allowDuplicates = true,
            attemptLimit = 30,
            maxConsecutiveDuplicateDigits = 3,
            seed = 42L,
        )

        assertEquals(SecretGenerator.generate(config), SecretGenerator.generate(config))
    }
}
