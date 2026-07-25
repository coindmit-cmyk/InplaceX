package com.mirkori.inplacex.core.analysis

private const val MAX_ENUMERATED_ASSIGNMENTS = 200_000L

/**
 * Pure exact-position evidence solver.
 *
 * The engine never mutates its inputs or keeps match state between calls. It
 * first applies local score bounds until no domain changes remain, then uses
 * exhaustive enumeration only when the remaining Cartesian product is small
 * enough. Both stages are deterministic and therefore produce the same
 * result for the same evidence, regardless of collection implementation.
 */
class EvidenceDeductionEngine(
    val codeLength: Int,
    alphabet: Set<Char> = ('0'..'9').toSet(),
) {
    private val orderedAlphabet: List<Char> = alphabet.toSortedSet().toList()

    init {
        require(codeLength > 0) { "codeLength must be positive" }
        require(orderedAlphabet.isNotEmpty()) { "alphabet must not be empty" }
    }

    fun infer(input: EvidenceInput = EvidenceInput()): DeductionResult {
        val domains = MutableList(codeLength) { orderedAlphabet.toMutableSet() }
        val contradictions = linkedSetOf<AnalysisContradiction>()
        val assertedFacts = linkedSetOf<ProvenFact>()
        val exactByPosition = mutableMapOf<Int, Char>()
        val impossibleFacts = mutableSetOf<Pair<Int, Char>>()
        val attempts = input.acceptedAttempts.filter { attempt ->
            validateAttempt(attempt, contradictions)
        }

        input.hypotheses.forEach { hypothesis ->
            if (!validatePositionAndSymbol(hypothesis.position, hypothesis.symbol, contradictions)) {
                return@forEach
            }
            when (hypothesis.kind) {
                HypothesisKind.POSSIBLE -> domains[hypothesis.position].retainAll(setOf(hypothesis.symbol))
                HypothesisKind.IMPOSSIBLE -> domains[hypothesis.position].remove(hypothesis.symbol)
            }
        }

        input.provenFacts.forEach { fact ->
            if (!validatePositionAndSymbol(fact.position, fact.symbol, contradictions)) {
                return@forEach
            }
            assertedFacts += fact
            when (fact.kind) {
                ProvenFactKind.EXACT_MATCH -> {
                    val previous = exactByPosition.putIfAbsent(fact.position, fact.symbol)
                    if (previous != null && previous != fact.symbol) {
                        contradictions += AnalysisContradiction(
                            type = ContradictionType.CONFLICTING_EXACT_FACTS,
                            message = "Position ${fact.position} is proven as both '$previous' and '${fact.symbol}'",
                            position = fact.position,
                            symbol = fact.symbol,
                        )
                    }
                    if ((fact.position to fact.symbol) in impossibleFacts) {
                        contradictions += AnalysisContradiction(
                            type = ContradictionType.EXACT_AND_IMPOSSIBLE_FACT,
                            message = "Symbol '${fact.symbol}' is both exact and impossible at position ${fact.position}",
                            position = fact.position,
                            symbol = fact.symbol,
                        )
                    }
                    domains[fact.position].retainAll(setOf(fact.symbol))
                }

                ProvenFactKind.NOT_AT_POSITION -> {
                    impossibleFacts += fact.position to fact.symbol
                    if (exactByPosition[fact.position] == fact.symbol) {
                        contradictions += AnalysisContradiction(
                            type = ContradictionType.EXACT_AND_IMPOSSIBLE_FACT,
                            message = "Symbol '${fact.symbol}' is both exact and impossible at position ${fact.position}",
                            position = fact.position,
                            symbol = fact.symbol,
                        )
                    }
                    domains[fact.position].remove(fact.symbol)
                }
            }
        }

        var iterations = 0
        var changed: Boolean
        do {
            iterations += 1
            changed = false

            domains.forEachIndexed { position, domain ->
                if (domain.isEmpty()) {
                    contradictions += AnalysisContradiction(
                        type = ContradictionType.EMPTY_POSITION_DOMAIN,
                        message = "No symbol can occupy position $position",
                        position = position,
                    )
                }
            }

            attempts.forEach { attempt ->
                val fixedMatches = mutableListOf<Int>()
                val unresolved = mutableListOf<Int>()
                attempt.guess.forEachIndexed { position, symbol ->
                    val domain = domains[position]
                    when {
                        symbol !in domain -> Unit
                        domain.size == 1 -> fixedMatches += position
                        else -> unresolved += position
                    }
                }

                val minimum = fixedMatches.size
                val maximum = minimum + unresolved.size
                if (attempt.score !in minimum..maximum) {
                    contradictions += AnalysisContradiction(
                        type = ContradictionType.UNSATISFIABLE_EVIDENCE,
                        message = "Guess ${attempt.guess} cannot have score ${attempt.score}; allowed range is $minimum..$maximum",
                    )
                    return@forEach
                }

                when {
                    attempt.score == minimum -> unresolved.forEach { position ->
                        changed = domains[position].remove(attempt.guess[position]) || changed
                    }

                    attempt.score == maximum -> unresolved.forEach { position ->
                        val symbol = attempt.guess[position]
                        if (domains[position] != setOf(symbol)) {
                            domains[position].retainAll(setOf(symbol))
                            changed = true
                        }
                    }
                }
            }

            if (contradictions.isEmpty()) {
                val enumeration = enumerateSolutions(domains, attempts)
                if (enumeration != null) {
                    if (enumeration.isEmpty()) {
                        contradictions += AnalysisContradiction(
                            type = ContradictionType.UNSATISFIABLE_EVIDENCE,
                            message = "No code satisfies all supplied evidence",
                        )
                    } else {
                        domains.forEachIndexed { position, domain ->
                            val viableSymbols = enumeration
                                .asSequence()
                                .map { solution -> solution[position] }
                                .toSet()
                            if (domain.retainAll(viableSymbols)) {
                                changed = true
                            }
                        }
                    }
                }
            }
        } while (changed && contradictions.isEmpty())

        val inferredFacts = linkedSetOf<ProvenFact>()
        domains.forEachIndexed { position, domain ->
            if (domain.isNotEmpty()) {
                if (domain.size == 1) {
                    inferredFacts += ProvenFact.exactMatch(position, domain.single())
                }
                orderedAlphabet
                    .filterNot(domain::contains)
                    .forEach { symbol -> inferredFacts += ProvenFact.notAtPosition(position, symbol) }
            }
        }

        return DeductionResult(
            candidates = domains.map { it.toSortedSet() },
            provenFacts = (assertedFacts + inferredFacts).toSet(),
            contradictions = contradictions.toList(),
            iterations = iterations,
        )
    }

    fun infer(
        hypotheses: Iterable<ManualHypothesis> = emptyList(),
        acceptedAttempts: Iterable<AcceptedAttemptEvidence> = emptyList(),
        provenFacts: Iterable<ProvenFact> = emptyList(),
    ): DeductionResult {
        return infer(
            EvidenceInput.of(
                hypotheses = hypotheses,
                acceptedAttempts = acceptedAttempts,
                provenFacts = provenFacts,
            ),
        )
    }

    private fun validateAttempt(
        attempt: AcceptedAttemptEvidence,
        contradictions: MutableSet<AnalysisContradiction>,
    ): Boolean {
        var valid = true
        if (attempt.guess.length != codeLength) {
            contradictions += AnalysisContradiction(
                type = ContradictionType.INVALID_GUESS_LENGTH,
                message = "Guess must contain $codeLength symbols: ${attempt.guess}",
            )
            valid = false
        }
        attempt.guess.forEach { symbol ->
            if (symbol !in orderedAlphabet) {
                contradictions += AnalysisContradiction(
                    type = ContradictionType.INVALID_GUESS_SYMBOL,
                    message = "Guess contains symbol '$symbol' outside the alphabet",
                    symbol = symbol,
                )
                valid = false
            }
        }
        if (attempt.score !in 0..codeLength) {
            contradictions += AnalysisContradiction(
                type = ContradictionType.SCORE_OUT_OF_RANGE,
                message = "Exact-match score must be in 0..$codeLength: ${attempt.score}",
            )
            valid = false
        }
        return valid
    }

    private fun validatePositionAndSymbol(
        position: Int,
        symbol: Char,
        contradictions: MutableSet<AnalysisContradiction>,
    ): Boolean {
        if (position !in 0 until codeLength) {
            contradictions += AnalysisContradiction(
                type = ContradictionType.INVALID_POSITION,
                message = "Position must be in 0 until $codeLength: $position",
                position = position,
                symbol = symbol,
            )
            return false
        }
        if (symbol !in orderedAlphabet) {
            contradictions += AnalysisContradiction(
                type = ContradictionType.SYMBOL_OUTSIDE_ALPHABET,
                message = "Symbol '$symbol' is outside the alphabet",
                position = position,
                symbol = symbol,
            )
            return false
        }
        return true
    }

    /** Returns null when enumeration would exceed the bounded pure-search budget. */
    private fun enumerateSolutions(
        domains: List<Set<Char>>,
        attempts: List<AcceptedAttemptEvidence>,
    ): List<CharArray>? {
        var searchSpace = 1L
        domains.forEach { domain ->
            if (domain.isEmpty()) return emptyList()
            if (searchSpace > MAX_ENUMERATED_ASSIGNMENTS / domain.size) return null
            searchSpace *= domain.size
        }

        val order = domains.indices.sortedWith(compareBy<Int> { domains[it].size }.thenBy { it })
        val assigned = arrayOfNulls<Char>(codeLength)
        val solutions = mutableListOf<CharArray>()

        fun canStillReachEveryScore(): Boolean {
            return attempts.all { attempt ->
                var fixed = 0
                var possibleRemaining = 0
                assigned.indices.forEach { position ->
                    val value = assigned[position]
                    if (value == null) {
                        if (attempt.guess[position] in domains[position]) possibleRemaining += 1
                    } else if (value == attempt.guess[position]) {
                        fixed += 1
                    }
                }
                attempt.score in fixed..(fixed + possibleRemaining)
            }
        }

        fun visit(depth: Int) {
            if (depth == order.size) {
                if (canStillReachEveryScore()) {
                    solutions += CharArray(codeLength) { position -> assigned[position]!! }
                }
                return
            }
            val position = order[depth]
            domains[position].toSortedSet().forEach { symbol ->
                assigned[position] = symbol
                if (canStillReachEveryScore()) visit(depth + 1)
                assigned[position] = null
            }
        }

        visit(depth = 0)
        return solutions
    }
}

typealias DeductionEngine = EvidenceDeductionEngine
