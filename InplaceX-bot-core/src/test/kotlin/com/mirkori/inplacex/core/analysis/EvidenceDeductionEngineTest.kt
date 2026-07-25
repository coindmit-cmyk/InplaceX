package com.mirkori.inplacex.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceDeductionEngineTest {

    @Test
    fun keepsHypothesesAttemptsAndFactsAsDifferentInputTypes() {
        val input = EvidenceInput(
            hypotheses = listOf(ManualHypothesis(position = 1, symbol = '4')),
            acceptedAttempts = listOf(AcceptedAttemptEvidence(guess = "0000", score = 1)),
            provenFacts = listOf(ProvenFact.exactMatch(position = 0, symbol = '4')),
        )

        assertEquals(1, input.hypotheses.size)
        assertEquals(1, input.acceptedAttempts.size)
        assertEquals(1, input.provenFacts.size)
        assertEquals(HypothesisKind.POSSIBLE, input.hypotheses.single().kind)
        assertEquals(1, input.acceptedAttempts.single().exactMatches)
        assertEquals(ProvenFactKind.EXACT_MATCH, input.provenFacts.single().kind)
    }

    @Test
    fun manualHypothesesConstrainCandidatesWithoutBecomingFacts() {
        val result = EvidenceDeductionEngine(codeLength = 4).infer(
            hypotheses = listOf(
                ManualHypothesis(position = 0, symbol = '9', kind = HypothesisKind.POSSIBLE),
                ManualHypothesis(position = 1, symbol = '8', kind = HypothesisKind.IMPOSSIBLE),
            ),
        )

        assertEquals(setOf('9'), result.candidates[0])
        assertFalse('8' in result.candidates[1])
        assertFalse(result.provenFacts.contains(ProvenFact.exactMatch(position = 0, symbol = '9')))
        assertFalse(result.provenFacts.contains(ProvenFact.notAtPosition(position = 1, symbol = '8')))
        assertTrue(result.provenFacts.isEmpty())
    }

    @Test
    fun zeroGuessKnownFourAnd4060ProveSixInThirdPosition() {
        val result = EvidenceDeductionEngine(codeLength = 4).infer(
            acceptedAttempts = listOf(
                AcceptedAttemptEvidence(guess = "0000", score = 1),
                AcceptedAttemptEvidence(guess = "4060", score = 3),
            ),
            provenFacts = listOf(ProvenFact.exactMatch(position = 0, symbol = '4')),
        )

        assertTrue(result.isConsistent)
        assertEquals(setOf('6'), result.candidates[2])
        assertEquals('6', result.exactMatches[2])
        assertTrue(result.provenFacts.contains(ProvenFact.exactMatch(2, '6')))
    }

    @Test
    fun contradictoryEvidenceIsTypedAndDoesNotThrow() {
        val result = EvidenceDeductionEngine(codeLength = 4).infer(
            provenFacts = listOf(
                ProvenFact.exactMatch(position = 1, symbol = '2'),
                ProvenFact.exactMatch(position = 1, symbol = '7'),
            ),
        )

        assertFalse(result.isConsistent)
        assertTrue(result.contradictions.any { it.type == ContradictionType.CONFLICTING_EXACT_FACTS })
    }

    @Test
    fun repeatedInferenceIsDeterministic() {
        val engine = EvidenceDeductionEngine(codeLength = 4)
        val input = EvidenceInput(
            hypotheses = listOf(ManualHypothesis(position = 3, symbol = '8', kind = HypothesisKind.IMPOSSIBLE)),
            acceptedAttempts = listOf(AcceptedAttemptEvidence(guess = "1111", score = 0)),
        )

        val first = engine.infer(input)
        val second = engine.infer(input)

        assertEquals(first, second)
        assertTrue(first.reachedFixpoint)
        assertEquals(first.iterations, second.iterations)
    }
}
