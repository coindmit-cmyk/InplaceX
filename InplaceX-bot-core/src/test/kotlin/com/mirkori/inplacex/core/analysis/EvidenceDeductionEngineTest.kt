package com.mirkori.inplacex.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EvidenceDeductionEngineTest {

    @Test
    fun deductionsAgreeWithAnExhaustiveOracleForSeededHistories() {
        val alphabet = ('0'..'3').toSet()
        val codes = (0 until 1024).map { it.toString(4).padStart(5, '0') }
        val random = Random(26)
        repeat(80) {
            val secret = codes.random(random)
            val attempts = List(8) {
                val guess = codes.random(random)
                AcceptedAttemptEvidence(guess, guess.indices.count { guess[it] == secret[it] })
            }
            val solutions = codes.filter { code ->
                attempts.all { attempt -> code.indices.count { code[it] == attempt.guess[it] } == attempt.score }
            }
            val result = EvidenceDeductionEngine(5, alphabet).infer(acceptedAttempts = attempts)
            assertTrue(result.isConsistent)
            result.provenFacts.forEach { fact ->
                assertTrue("Unsound deduction: $fact", solutions.all { code ->
                    when (fact.kind) {
                        ProvenFactKind.EXACT_MATCH -> code[fact.position] == fact.symbol
                        ProvenFactKind.NOT_AT_POSITION -> code[fact.position] != fact.symbol
                    }
                })
            }
        }
    }

    @Test
    fun sharedZeroGroupExcludesEveryOneWithoutChoosingWhichZeroMatches() {
        val attempts = listOf(
            AcceptedAttemptEvidence("000111", 1),
            AcceptedAttemptEvidence("111323", 0),
            AcceptedAttemptEvidence("000323", 1),
        )
        val engine = EvidenceDeductionEngine(codeLength = 6)
        val result = engine.infer(acceptedAttempts = attempts)

        assertTrue(result.isConsistent)
        (0 until 6).forEach { position ->
            assertTrue(ProvenFact.notAtPosition(position, '1') in result.provenFacts)
        }
        (0 until 3).forEach { position ->
            assertTrue('0' in result.candidates[position])
            assertTrue(result.candidates[position].size > 1)
        }
        assertTrue(result.exactMatches.isEmpty())
        assertEquals(result.provenFacts, engine.infer(acceptedAttempts = attempts.reversed()).provenFacts)
    }

    @Test
    fun equalScoresWithoutAZeroFillerDoNotExcludeTheChangedGroup() {
        val result = EvidenceDeductionEngine(6).infer(acceptedAttempts = listOf(
            AcceptedAttemptEvidence("000111", 1),
            AcceptedAttemptEvidence("000323", 1),
        ))
        assertTrue(result.isConsistent)
        assertTrue(result.provenFacts.isEmpty())
    }

    @Test
    fun groupedHypothesesNeverBecomeAuthoritativeFacts() {
        val attempts = listOf(
            AcceptedAttemptEvidence("000111", 1),
            AcceptedAttemptEvidence("000323", 1),
        )
        val result = EvidenceDeductionEngine(6).infer(
            acceptedAttempts = attempts,
            hypotheses = listOf(
                ManualHypothesis(3, '3', HypothesisKind.IMPOSSIBLE),
                ManualHypothesis(4, '2', HypothesisKind.IMPOSSIBLE),
                ManualHypothesis(5, '3', HypothesisKind.IMPOSSIBLE),
            ),
        )
        assertTrue(result.isConsistent)
        (3 until 6).forEach { assertFalse('1' in result.candidates[it]) }
        assertTrue(result.provenFacts.isEmpty())
    }

    @Test(timeout = 2_000)
    fun groupedDeductionDoesNotEnumerateLargeCodes() {
        val result = EvidenceDeductionEngine(14).infer(acceptedAttempts = listOf(
            AcceptedAttemptEvidence("00011199999999", 1),
            AcceptedAttemptEvidence("11132399999999", 0),
            AcceptedAttemptEvidence("00032399999999", 1),
        ))
        assertTrue(result.isConsistent)
        (0 until 6).forEach { assertTrue(ProvenFact.notAtPosition(it, '1') in result.provenFacts) }
        (0 until 3).forEach { assertTrue('0' in result.candidates[it]) }
    }

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

    @Test
    fun noDuplicatesRemovesFixedSymbolFromEveryOtherPosition() {
        val result = EvidenceDeductionEngine(
            codeLength = 4,
            allowDuplicates = false,
        ).infer(
            provenFacts = listOf(ProvenFact.exactMatch(position = 1, symbol = '7')),
        )

        assertTrue(result.isConsistent)
        assertEquals(setOf('7'), result.candidates[1])
        result.candidates.forEachIndexed { position, candidates ->
            if (position != 1) assertFalse('7' in candidates)
        }
    }

    @Test
    fun noDuplicatesReportsContradictoryRepeatedExactFacts() {
        val result = EvidenceDeductionEngine(
            codeLength = 4,
            allowDuplicates = false,
        ).infer(
            provenFacts = listOf(
                ProvenFact.exactMatch(position = 0, symbol = '3'),
                ProvenFact.exactMatch(position = 2, symbol = '3'),
            ),
        )

        assertFalse(result.isConsistent)
        assertTrue(result.contradictions.any { it.type == ContradictionType.UNSATISFIABLE_EVIDENCE })
    }
}
