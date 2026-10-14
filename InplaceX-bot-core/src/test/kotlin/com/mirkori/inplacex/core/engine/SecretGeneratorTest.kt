package com.mirkori.inplacex.core.engine

import com.mirkori.inplacex.core.model.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `duplicate enabled secrets have valid shape at boundary lengths`() {
        val seeds = listOf(0L, 1L, 42L)
        listOf(4, 20).forEach { codeLength ->
            seeds.forEach { seed ->
                val secret = SecretGenerator.generate(
                    GameConfig(
                        codeLength = codeLength,
                        allowDuplicates = true,
                        attemptLimit = 40,
                        maxConsecutiveDuplicateDigits = 3,
                        seed = seed,
                    ),
                )

                assertEquals("length for codeLength=$codeLength, seed=$seed", codeLength, secret.length)
                assertTrue(
                    "all characters must be decimal digits for codeLength=$codeLength, seed=$seed",
                    secret.all { it in '0'..'9' },
                )
                for (start in 0..secret.length - 4) {
                    val first = secret[start]
                    assertTrue(
                        "no four-digit window may repeat one digit for codeLength=$codeLength, seed=$seed, start=$start",
                        (1..3).any { offset -> secret[start + offset] != first },
                    )
                }
            }
        }
    }

    @Test
    fun `no duplicates secrets contain each decimal digit`() {
        val decimalAlphabet = ('0'..'9').toSet()
        listOf(0L, 1L, 42L).forEach { seed ->
            val secret = SecretGenerator.generate(
                GameConfig(
                    codeLength = 10,
                    allowDuplicates = false,
                    attemptLimit = 40,
                    seed = seed,
                ),
            )

            assertEquals("length for seed=$seed", 10, secret.length)
            assertEquals("distinct digit count for seed=$seed", 10, secret.toSet().size)
            assertEquals("decimal alphabet for seed=$seed", decimalAlphabet, secret.toSet())
        }
    }
}
