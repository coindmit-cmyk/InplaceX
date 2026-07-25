package com.mirkori.inplacex.core.analysis

/** The kind of a user-entered hypothesis about one exact position. */
enum class HypothesisKind {
    POSSIBLE,
    IMPOSSIBLE,
}

/** A tentative assertion. It constrains inference but is not presented as a fact. */
data class ManualHypothesis(
    val position: Int,
    val symbol: Char,
    val kind: HypothesisKind = HypothesisKind.POSSIBLE,
)

/** Feedback from an accepted guess. The score is the number of exact positions. */
data class AcceptedAttemptEvidence(
    val guess: String,
    val score: Int,
) {
    val exactMatches: Int
        get() = score
}

enum class ProvenFactKind {
    EXACT_MATCH,
    NOT_AT_POSITION,
}

/** A conclusion that is safe to expose to the rest of the game. */
data class ProvenFact(
    val position: Int,
    val symbol: Char,
    val kind: ProvenFactKind = ProvenFactKind.EXACT_MATCH,
) {
    val isExactMatch: Boolean
        get() = kind == ProvenFactKind.EXACT_MATCH

    companion object {
        fun exactMatch(position: Int, symbol: Char): ProvenFact {
            return ProvenFact(position, symbol, ProvenFactKind.EXACT_MATCH)
        }

        fun notAtPosition(position: Int, symbol: Char): ProvenFact {
            return ProvenFact(position, symbol, ProvenFactKind.NOT_AT_POSITION)
        }
    }
}

/** The complete pure input to one deduction pass. */
data class EvidenceInput(
    val hypotheses: List<ManualHypothesis> = emptyList(),
    val acceptedAttempts: List<AcceptedAttemptEvidence> = emptyList(),
    val provenFacts: List<ProvenFact> = emptyList(),
) {
    companion object {
        fun of(
            hypotheses: Iterable<ManualHypothesis> = emptyList(),
            acceptedAttempts: Iterable<AcceptedAttemptEvidence> = emptyList(),
            provenFacts: Iterable<ProvenFact> = emptyList(),
        ): EvidenceInput {
            return EvidenceInput(
                hypotheses = hypotheses.toList(),
                acceptedAttempts = acceptedAttempts.toList(),
                provenFacts = provenFacts.toList(),
            )
        }
    }
}

enum class ContradictionType {
    INVALID_POSITION,
    SYMBOL_OUTSIDE_ALPHABET,
    INVALID_GUESS_LENGTH,
    INVALID_GUESS_SYMBOL,
    SCORE_OUT_OF_RANGE,
    EMPTY_POSITION_DOMAIN,
    CONFLICTING_EXACT_FACTS,
    EXACT_AND_IMPOSSIBLE_FACT,
    UNSATISFIABLE_EVIDENCE,
}

data class AnalysisContradiction(
    val type: ContradictionType,
    val message: String,
    val position: Int? = null,
    val symbol: Char? = null,
)

/** Immutable output of the evidence engine. Position indexes are zero-based. */
data class DeductionResult(
    val candidates: List<Set<Char>>,
    val provenFacts: Set<ProvenFact>,
    val contradictions: List<AnalysisContradiction>,
    val iterations: Int,
) {
    val isConsistent: Boolean
        get() = contradictions.isEmpty()

    val reachedFixpoint: Boolean
        get() = true

    val candidatesByPosition: Map<Int, Set<Char>>
        get() = candidates.mapIndexed { position, symbols -> position to symbols }.toMap()

    val facts: Set<ProvenFact>
        get() = provenFacts

    val exactMatches: Map<Int, Char>
        get() = provenFacts
            .asSequence()
            .filter { it.kind == ProvenFactKind.EXACT_MATCH }
            .associate { it.position to it.symbol }
}

typealias Contradiction = AnalysisContradiction
typealias DeductionSnapshot = DeductionResult
