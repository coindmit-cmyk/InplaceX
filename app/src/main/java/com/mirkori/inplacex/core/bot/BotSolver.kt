package com.mirkori.inplacex.core.bot

import com.mirkori.inplacex.core.engine.GuessValidator
import com.mirkori.inplacex.core.engine.ScoreCalculator
import com.mirkori.inplacex.core.model.GameConfig
import kotlin.math.ln
import kotlin.random.Random

data class BotObservation(
    val guess: String,
    val score: Int,
)

data class BotTurnDecision(
    val guess: String,
    val candidatesLeft: Int,
)

data class BotSolverState(
    val config: GameConfig,
    val difficulty: BotDifficulty,
    val profile: BotDifficultyProfile,
    val history: List<BotObservation>,
    val candidates: List<String>,
)

class BotSolver(
    private val config: GameConfig,
    difficulty: BotDifficulty,
    seed: Long = 0L,
) {
    private var fallbackGuessCursor = 0
    private val random = Random(seed)
    private val profile = BotProfiles.forDifficulty(difficulty)
    private val usesExhaustiveUniverse = estimateSearchSpace(config) <= MAX_EXHAUSTIVE_SPACE
    private val attemptedGuesses = linkedSetOf<String>()
    private var history = emptyList<BotObservation>()
    private var candidates = initialCandidates(config, profile, random)

    fun snapshot(): BotSolverState {
        return BotSolverState(
            config = config,
            difficulty = profile.difficulty,
            profile = profile,
            history = history,
            candidates = candidates,
        )
    }

    fun nextTurn(): BotTurnDecision {
        refreshCandidatesIfNeeded()
        val openingGuess = openingGuess()

        val guess = when {
            openingGuess != null -> openingGuess
            candidates.isEmpty() -> generateRandomConsistentGuess(
                config,
                history,
                attemptedGuesses,
                random,
                ::nextDeterministicFallbackGuess
            )
            candidates.size == 1 -> candidates.first()
            shouldUseRandomGuess() -> randomUntriedCandidateOrConsistentGuess()
            else -> chooseHeuristicGuess()
        }

        attemptedGuesses += guess
        return BotTurnDecision(
            guess = guess,
            candidatesLeft = candidates.size,
        )
    }

    fun registerFeedback(guess: String, score: Int) {
        history = history + BotObservation(guess = guess, score = score)
        candidates = candidates.filter { candidate ->
            ScoreCalculator.countExactMatches(candidate, guess) == score
        }
        refreshCandidatesIfNeeded()
    }

    fun confirmedPositionsCount(): Int {
        if (candidates.isEmpty()) return 0
        return (0 until config.codeLength).count { position ->
            candidates.map { it[position] }.distinct().size == 1
        }
    }

    private fun shouldUseRandomGuess(): Boolean {
        if (attemptedGuesses.isEmpty()) return true
        if (profile.randomGuessShare <= 0.0) return false
        if (candidates.size <= 2) return false
        return random.nextDouble() < profile.randomGuessShare
    }

    private fun openingGuess(): String? {
        return when (profile.difficulty) {
            BotDifficulty.EASY -> easyOpeningGuess()
            BotDifficulty.MEDIUM -> mediumOpeningGuess()
            BotDifficulty.HARD -> hardOpeningGuess()
            BotDifficulty.EXPERT -> null
        }?.takeIf { it !in attemptedGuesses }
    }

    private fun easyOpeningGuess(): String? {
        val stage = history.size
        val codeLength = config.codeLength
        return when {
            stage < 6 -> repeatedDigitGuess(stage + 1, codeLength)
            stage == 6 -> buildOpeningMixGuess(codeLength, listOf(1, 2))
            stage == 7 -> buildOpeningMixGuess(codeLength, listOf(2, 1))
            stage == 8 -> buildOpeningMixGuess(codeLength, listOf(3, 4))
            else -> null
        }
    }

    private fun mediumOpeningGuess(): String? {
        val stage = history.size
        val codeLength = config.codeLength
        return when {
            stage < 4 -> repeatedDigitGuess(stage + 1, codeLength)
            stage == 4 -> buildOpeningMixGuess(codeLength, listOf(1, 2))
            stage == 5 -> buildOpeningMixGuess(codeLength, listOf(2, 1))
            else -> null
        }
    }

    private fun hardOpeningGuess(): String? {
        val codeLength = config.codeLength
        return when (history.size) {
            0 -> buildOpeningMixGuess(codeLength, listOf(1, 2))
            1 -> buildOpeningMixGuess(codeLength, listOf(2, 1))
            else -> null
        }
    }

    private fun randomUntriedCandidateOrConsistentGuess(): String {
        val pool = candidates.filterNot(attemptedGuesses::contains)
        if (pool.isNotEmpty()) return pool.random(random)
        return generateRandomConsistentGuess(config, history, attemptedGuesses, random, ::nextDeterministicFallbackGuess)
    }

    private fun chooseHeuristicGuess(): String {
        val candidateSubset = sampleCandidates(
            source = candidates,
            sampleSize = evaluationSampleSize(profile, config.codeLength),
            random = random,
            exclude = attemptedGuesses,
        )
        if (candidateSubset.isNotEmpty() && config.codeLength >= 6 && profile.difficulty == BotDifficulty.MEDIUM) {
            return candidateSubset.random(random)
        }
        val evaluationSecrets = sampleCandidates(
            source = candidates,
            sampleSize = partitionSampleSize(profile, config.codeLength),
            random = random,
            exclude = emptySet(),
        ).ifEmpty { candidates }

        if (candidateSubset.isEmpty()) {
            return generateRandomConsistentGuess(config, history, attemptedGuesses, random, ::nextDeterministicFallbackGuess)
        }

        val scored = candidateSubset.map { guess ->
            val partition = partitionQuality(guess, evaluationSecrets)
            ScoredGuess(
                guess = guess,
                largestBucket = partition.largestBucket,
                entropy = partition.entropy,
                isCandidate = true,
            )
        }.sortedWith(
            compareBy<ScoredGuess> { it.largestBucket }
                .thenByDescending { it.entropy }
                .thenByDescending { it.isCandidate }
        )

        return when (profile.difficulty) {
            BotDifficulty.EASY -> scored.random(random).guess
            BotDifficulty.MEDIUM -> scored.take(minOf(4, scored.size)).random(random).guess
            BotDifficulty.HARD -> scored.take(minOf(2, scored.size)).random(random).guess
            BotDifficulty.EXPERT -> scored.first().guess
        }
    }

    private fun refreshCandidatesIfNeeded() {
        if (usesExhaustiveUniverse) return
        val minimumPool = replenishmentThreshold(profile, config.codeLength)
        if (candidates.size >= minimumPool) return

        val replenished = candidates.toMutableSet()
        val targetSize = replenishmentTarget(profile, config.codeLength)
        var attempts = 0
        while (replenished.size < targetSize && attempts < targetSize * 30) {
            replenished += generateRandomConsistentGuess(config, history, emptySet(), random, ::nextDeterministicFallbackGuess)
            attempts += 1
        }
        candidates = replenished.toList()
    }

    private fun nextDeterministicFallbackGuess(): String {
        val digitPool = ('0'..'9').toList()
        repeat(500) {
            val guess = if (config.allowDuplicates) {
                buildString {
                    repeat(config.codeLength) { position ->
                        append(digitPool[(fallbackGuessCursor + position) % digitPool.size])
                    }
                }
            } else {
                digitPool.drop(fallbackGuessCursor % digitPool.size)
                    .plus(digitPool.take(fallbackGuessCursor % digitPool.size))
                    .take(config.codeLength)
                    .joinToString("")
            }
            fallbackGuessCursor += 1
            if (guess !in attemptedGuesses) {
                return guess
            }
        }
        return buildString {
            repeat(config.codeLength) { append((fallbackGuessCursor++ % 10).digitToChar()) }
        }
    }

    private data class ScoredGuess(
        val guess: String,
        val largestBucket: Int,
        val entropy: Double,
        val isCandidate: Boolean,
    )

    private data class PartitionMetrics(
        val largestBucket: Int,
        val entropy: Double,
    )

    private fun partitionQuality(guess: String, candidatePool: List<String>): PartitionMetrics {
        val buckets = IntArray(config.codeLength + 1)
        candidatePool.forEach { candidate ->
            val score = ScoreCalculator.countExactMatches(candidate, guess)
            buckets[score] += 1
        }
        val total = candidatePool.size.toDouble().coerceAtLeast(1.0)
        var entropy = 0.0
        var largest = 0
        buckets.forEach { size ->
            if (size > 0) {
                val p = size / total
                entropy -= p * ln(p)
                if (size > largest) largest = size
            }
        }
        return PartitionMetrics(
            largestBucket = largest,
            entropy = entropy,
        )
    }

    companion object {
        private const val MAX_EXHAUSTIVE_SPACE = 250_000L

        fun solveSecret(
            secret: String,
            config: GameConfig,
            difficulty: BotDifficulty,
            seed: Long = 0L,
            maxMoves: Int = BotProfiles.forDifficulty(difficulty).targetMovesForCodeLength(config.codeLength) + 6,
        ): BotSimulationRun {
            val solver = BotSolver(config = config, difficulty = difficulty, seed = seed)
            var moves = 0

            while (moves < maxMoves) {
                val turn = solver.nextTurn()
                val score = ScoreCalculator.countExactMatches(secret, turn.guess)
                moves += 1
                solver.registerFeedback(turn.guess, score)
                if (score == config.codeLength) {
                    return BotSimulationRun(
                        secret = secret,
                        difficulty = difficulty,
                        won = true,
                        moves = moves,
                        targetMoves = BotProfiles.forDifficulty(difficulty).targetMovesForCodeLength(config.codeLength),
                    )
                }
            }

            return BotSimulationRun(
                secret = secret,
                difficulty = difficulty,
                won = false,
                moves = moves,
                targetMoves = BotProfiles.forDifficulty(difficulty).targetMovesForCodeLength(config.codeLength),
            )
        }

        private fun initialCandidates(
            config: GameConfig,
            profile: BotDifficultyProfile,
            random: Random,
        ): List<String> {
            val searchSpace = estimateSearchSpace(config)
            return if (searchSpace <= MAX_EXHAUSTIVE_SPACE) {
                enumerateAllValidGuesses(config)
            } else {
                buildSamplePool(
                    config = config,
                    size = replenishmentTarget(profile, config.codeLength),
                    history = emptyList(),
                    random = random,
                )
            }
        }

        private fun estimateSearchSpace(config: GameConfig): Long {
            return if (config.allowDuplicates) {
                generateSequence(1L) { it * 10L }
                    .drop(config.codeLength)
                    .first()
            } else {
                var result = 1L
                var remaining = 10
                repeat(config.codeLength) {
                    result *= remaining.toLong()
                    remaining -= 1
                }
                result
            }
        }

        private fun enumerateAllValidGuesses(config: GameConfig): List<String> {
            val results = mutableListOf<String>()
            val digits = ('0'..'9').toList()

            fun build(current: StringBuilder, used: BooleanArray) {
                if (current.length == config.codeLength) {
                    val guess = current.toString()
                    if (GuessValidator.validate(guess, config)) {
                        results += guess
                    }
                    return
                }

                digits.forEachIndexed { index, digit ->
                    if (!config.allowDuplicates && used[index]) return@forEachIndexed
                    current.append(digit)
                    used[index] = true
                    build(current, used)
                    used[index] = false
                    current.deleteCharAt(current.length - 1)
                }
            }

            build(StringBuilder(), BooleanArray(10))
            return results
        }

        private fun buildSamplePool(
            config: GameConfig,
            size: Int,
            history: List<BotObservation>,
            random: Random,
        ): List<String> {
            val guesses = linkedSetOf<String>()
            var attempts = 0
            val maxAttempts = if (config.codeLength >= 6) size * 8 else size * 40
            while (guesses.size < size && attempts < maxAttempts) {
                guesses += generateRandomConsistentGuess(config, history, emptySet(), random)
                attempts += 1
            }
            return guesses.toList()
        }

        private fun generateRandomConsistentGuess(
            config: GameConfig,
            history: List<BotObservation>,
            forbidden: Set<String>,
            random: Random,
            fallback: (() -> String)? = null,
        ): String {
            var attempts = 0
            val maxAttempts = if (config.codeLength >= 6) 240 else 1_500
            while (attempts < maxAttempts) {
                val guess = generateRandomValidGuess(config, random)
                attempts += 1
                if (guess in forbidden) continue
                val consistent = history.all { observation ->
                    ScoreCalculator.countExactMatches(guess, observation.guess) == observation.score
                }
                if (consistent) return guess
            }
            var sampledAttempts = 0
            val sampledMaxAttempts = if (config.codeLength >= 6) 64 else 256
            while (sampledAttempts < sampledMaxAttempts) {
                val sampledGuess = generateRandomValidGuess(config, random)
                sampledAttempts += 1
                if (sampledGuess in forbidden) continue
                val consistent = history.all { observation ->
                    ScoreCalculator.countExactMatches(sampledGuess, observation.guess) == observation.score
                }
                if (consistent) return sampledGuess
            }
            fallback?.let { return it() }
            return generateRandomValidGuess(config, random)
        }

        private fun generateRandomValidGuess(config: GameConfig, random: Random): String {
            while (true) {
                val guess = if (config.allowDuplicates) {
                    buildString {
                        repeat(config.codeLength) {
                            append(random.nextInt(0, 10))
                        }
                    }
                } else {
                    ('0'..'9').shuffled(random).take(config.codeLength).joinToString("")
                }
                if (GuessValidator.validate(guess, config)) return guess
            }
        }

        private fun sampleCandidates(
            source: List<String>,
            sampleSize: Int,
            random: Random,
            exclude: Set<String>,
        ): List<String> {
            val filtered = source.filterNot(exclude::contains)
            if (filtered.size <= sampleSize) return filtered
            return filtered.shuffled(random).take(sampleSize)
        }

        private fun repeatedDigitGuess(digit: Int, codeLength: Int): String {
            val safeDigit = digit % 10
            return buildString {
                repeat(codeLength) { append(safeDigit) }
            }
        }

        private fun buildOpeningMixGuess(codeLength: Int, digits: List<Int>): String {
            if (digits.isEmpty()) return "0".repeat(codeLength)
            return buildString {
                repeat(codeLength) { index ->
                    append(digits[index % digits.size])
                }
            }
        }

        private fun evaluationSampleSize(profile: BotDifficultyProfile, codeLength: Int): Int {
            if (codeLength >= 6) {
                return when (profile.difficulty) {
                    BotDifficulty.EASY -> 8
                    BotDifficulty.MEDIUM -> 10
                    BotDifficulty.HARD -> 14
                    BotDifficulty.EXPERT -> 18
                }
            }
            return when (profile.difficulty) {
                BotDifficulty.EASY -> 10
                BotDifficulty.MEDIUM -> 16
                BotDifficulty.HARD -> 24
                BotDifficulty.EXPERT -> 32
            }
        }

        private fun partitionSampleSize(profile: BotDifficultyProfile, codeLength: Int): Int {
            if (codeLength >= 6) {
                return when (profile.difficulty) {
                    BotDifficulty.EASY -> 48
                    BotDifficulty.MEDIUM -> 64
                    BotDifficulty.HARD -> 96
                    BotDifficulty.EXPERT -> 128
                }
            }
            return when (profile.difficulty) {
                BotDifficulty.EASY -> 90
                BotDifficulty.MEDIUM -> 120
                BotDifficulty.HARD -> 180
                BotDifficulty.EXPERT -> 240
            }
        }

        private fun replenishmentThreshold(profile: BotDifficultyProfile, codeLength: Int): Int {
            if (codeLength >= 6) {
                return when (profile.difficulty) {
                    BotDifficulty.EASY -> 24
                    BotDifficulty.MEDIUM -> 32
                    BotDifficulty.HARD -> 40
                    BotDifficulty.EXPERT -> 56
                }
            }
            return when (profile.difficulty) {
                BotDifficulty.EASY -> 40
                BotDifficulty.MEDIUM -> 60
                BotDifficulty.HARD -> 80
                BotDifficulty.EXPERT -> 120
            }
        }

        private fun replenishmentTarget(profile: BotDifficultyProfile, codeLength: Int): Int {
            if (codeLength >= 6) {
                return when (profile.difficulty) {
                    BotDifficulty.EASY -> 120
                    BotDifficulty.MEDIUM -> 160
                    BotDifficulty.HARD -> 240
                    BotDifficulty.EXPERT -> 320
                }
            }
            return when (profile.difficulty) {
                BotDifficulty.EASY -> 240
                BotDifficulty.MEDIUM -> 420
                BotDifficulty.HARD -> 720
                BotDifficulty.EXPERT -> 1_200
            }
        }
    }
}

data class BotSimulationRun(
    val secret: String,
    val difficulty: BotDifficulty,
    val won: Boolean,
    val moves: Int,
    val targetMoves: Int,
)
