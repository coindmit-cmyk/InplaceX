package com.mirkori.inplacex.core.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotGridCatalogTest {

    @Test
    fun digitCatalogContainsFortyPreparedPlans() {
        assertEquals(40, BotGridCatalog.allDigitPlans().size)
    }

    @Test
    fun eachPreparedPlanKeepsColumnsUniqueAcrossWholeCycle() {
        BotGridCatalog.allDigitPlans().forEach { plan ->
            val guesses = plan.guesses(codeLength = 10)
            assertEquals(10, guesses.size)
            assertTrue(guesses.all { guess -> guess.toSet().size == guess.length })

            repeat(10) { position ->
                val columnSymbols = guesses.map { guess -> guess[position] }
                assertEquals(
                    "column $position repeats in ${plan.id}",
                    10,
                    columnSymbols.toSet().size,
                )
            }
        }
    }

    @Test
    fun firstRowsLookScrambledInsteadOfPlainSequence() {
        val firstRows = BotGridCatalog.allDigitPlans().map { it.guesses(codeLength = 4).first() }
        assertTrue(firstRows.none { it == "0123" || it == "1234" || it == "2345" })
        assertTrue(firstRows.toSet().size > 20)
    }
}
