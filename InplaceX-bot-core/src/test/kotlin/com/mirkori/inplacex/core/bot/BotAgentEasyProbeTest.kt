package com.mirkori.inplacex.core.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BotAgentEasyProbeTest {
    private fun newEasyAgent(): BotAgent {
        val rules = BotMatchRules(
            codeLength = 4,
            alphabet = BotMatchRules.digits,
            allowDuplicates = true,
            forbidAllSameDigitsGuess = false,
            forbidAdjacentDuplicates = false,
            forbidTripleDuplicates = false,
        )
        return BotAgent(
            rules = rules,
            difficulty = BotDifficulty.EASY,
            seed = 731L,
        )
    }

    @Test
    fun zeroFeedbackEstablishesSafeBaselineWithoutGridPlan() {
        val agent = newEasyAgent()
        assertNull(agent.prepareMatch().gridPlanId)

        val firstGuess = agent.nextGuess()
        agent.registerFeedback(firstGuess, 0)

        val snapshot = agent.snapshot()
        assertNull(snapshot.gridPlanId)
        assertEquals(listOf(BotObservation(firstGuess, 0)), snapshot.history)
        assertEquals(emptyMap<Int, Char>(), snapshot.resolvedPositions)
        assertEquals(
            List(4) { BotMatchRules.digits.toSet() - firstGuess[it] },
            snapshot.candidates,
        )
        assertEquals(
            List(4) { setOf(firstGuess[it]) },
            snapshot.safeSymbols,
        )
    }

    @Test
    fun onePointFeedbackConfirmsOnlyTheChangedPosition() {
        val agent = newEasyAgent()
        assertNull(agent.prepareMatch().gridPlanId)

        val firstGuess = agent.nextGuess()
        agent.registerFeedback(firstGuess, 0)

        val secondGuess = agent.nextGuess()
        val changedPositions = firstGuess.indices.filter { firstGuess[it] != secondGuess[it] }
        assertEquals(1, changedPositions.size)
        val changedPosition = changedPositions.single()
        agent.registerFeedback(secondGuess, 1)

        val snapshot = agent.snapshot()
        assertNull(snapshot.gridPlanId)
        assertEquals(
            listOf(BotObservation(firstGuess, 0), BotObservation(secondGuess, 1)),
            snapshot.history,
        )
        assertEquals(mapOf(changedPosition to secondGuess[changedPosition]), snapshot.resolvedPositions)
        assertEquals(setOf(secondGuess[changedPosition]), snapshot.candidates[changedPosition])
    }
}
