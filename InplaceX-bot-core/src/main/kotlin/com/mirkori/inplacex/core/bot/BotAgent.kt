package com.mirkori.inplacex.core.bot

import com.mirkori.inplacex.core.engine.ScoreCalculator
import java.util.ArrayDeque
import kotlin.random.Random

enum class BotSolveStage {
    GRID_SEARCH,
    SAFE_BASE_SEARCH,
    VALUE_SEARCH,
    READY_TO_FINISH,
    COMPLETE,
}

data class BotAgentSnapshot(
    val rules: BotMatchRules,
    val difficulty: BotDifficulty,
    val behavior: BotBehaviorModel,
    val stage: BotSolveStage,
    val history: List<BotObservation>,
    val gridPlanId: String?,
    val candidates: List<Set<Char>>,
    val safeSymbols: List<Set<Char>>,
    val resolvedPositions: Map<Int, Char>,
)

class BotAgent(
    private val rules: BotMatchRules,
    private val difficulty: BotDifficulty,
    private val behavior: BotBehaviorModel = BotBehaviorModel.BALANCED,
    seed: Long = 0L,
) {
    private val random = Random(seed)
    private val behaviorProfile = BotBehaviorProfiles.forModel(behavior)
    private val actualHistory = mutableListOf<BotObservation>()
    private val gridKnowledge = mutableListOf<BotObservation>()
    private val attemptedGuesses = linkedSetOf<String>()
    private val candidates = MutableList(rules.codeLength) { rules.alphabet.toMutableSet() }
    private val safeSymbols = MutableList(rules.codeLength) { mutableSetOf<Char>() }
    private val positiveGridEvidence = MutableList(rules.codeLength) { mutableSetOf<Char>() }
    private val resolvedPositions = linkedMapOf<Int, Char>()
    private val testedCandidates = MutableList(rules.codeLength) { mutableSetOf<Char>() }
    private val isolationTasks = ArrayDeque<IsolationTask>()

    private val gridPlan = if (usesGridSearch()) BotGridCatalog.randomDigitPlan(random) else null
    private val gridGuesses = gridPlan?.guesses(rules.codeLength).orEmpty()
    private var nextGridIndex = 0
    private var gridScoreSum = 0
    private var safeBaseGuess: CharArray? = null
    private var stage = if (usesGridSearch()) BotSolveStage.GRID_SEARCH else BotSolveStage.SAFE_BASE_SEARCH
    private var pendingAction: PendingAction? = null

    fun prepareMatch(): BotAgentSnapshot {
        return snapshot()
    }

    fun snapshot(): BotAgentSnapshot {
        return BotAgentSnapshot(
            rules = rules,
            difficulty = difficulty,
            behavior = behavior,
            stage = stage,
            history = actualHistory.toList(),
            gridPlanId = gridPlan?.id,
            candidates = candidates.map { it.toSet() },
            safeSymbols = safeSymbols.map { it.toSet() },
            resolvedPositions = resolvedPositions.toMap(),
        )
    }

    fun nextGuess(): String {
        prepareMatch()
        resolveForcedKnowledge()
        advanceSyntheticGridKnowledgeIfNeeded()

        if (stage == BotSolveStage.GRID_SEARCH) {
            return planGridGuess()
        }

        if (resolvedPositions.size == rules.codeLength) {
            stage = BotSolveStage.READY_TO_FINISH
            return planFinalGuess()
        }

        if (stage == BotSolveStage.SAFE_BASE_SEARCH) {
            return planSafeBaseGuess()
        }

        val expertTailGuess = if (usesExpertTailSolve()) planExpertTailGuess() else null
        if (expertTailGuess != null) return expertTailGuess

        val isolationGuess = if (usesSplitIsolation()) planIsolationGuess() else null
        if (isolationGuess != null) return isolationGuess

        val groupGuess = if (usesGroupProbe()) planGroupProbeGuess() else null
        if (groupGuess != null) return groupGuess

        val positionalGuess = planPositionalGuess()
        if (positionalGuess != null) return positionalGuess

        stage = BotSolveStage.SAFE_BASE_SEARCH
        return planSafeBaseGuess()
    }

    fun registerFeedback(guess: String, score: Int) {
        val pending = pendingAction ?: error("Bot has no pending guess to score")
        require(pending.guess == guess) { "Unexpected guess feedback: $guess" }
        pendingAction = null

        actualHistory += BotObservation(guess = guess, score = score)

        when (pending) {
            is PendingAction.GridGuess -> {
                applyGridObservation(guess = guess, score = score, isActualMove = true)
                advanceSyntheticGridKnowledgeIfNeeded()
            }

            is PendingAction.SafeBaseGuess -> {
                if (score == pending.expectedScore) {
                    safeBaseGuess = guess.toCharArray()
                    guess.forEachIndexed { index, symbol ->
                        if (index !in resolvedPositions) {
                            markImpossible(index, symbol)
                        }
                    }
                    stage = BotSolveStage.VALUE_SEARCH
                } else if (score == rules.codeLength) {
                    guess.forEachIndexed { index, symbol -> markResolved(index, symbol) }
                    stage = BotSolveStage.COMPLETE
                }
            }

            is PendingAction.PositionProbe -> {
                when (score) {
                    rules.codeLength -> {
                        guess.forEachIndexed { index, symbol -> markResolved(index, symbol) }
                        stage = BotSolveStage.COMPLETE
                    }

                    pending.baseScore + 1 -> {
                        markResolved(pending.position, pending.candidate)
                    }

                    pending.baseScore -> {
                        markImpossible(pending.position, pending.candidate)
                    }

                    else -> {
                        stage = BotSolveStage.SAFE_BASE_SEARCH
                    }
                }
            }

            is PendingAction.HardSplitProbe -> {
                val subsetScore = score - pending.baseScore
                if (subsetScore in 0..pending.task.matches && subsetScore <= pending.subsetPositions.size) {
                    val subsetTask = IsolationTask(
                        sourceGuess = pending.task.sourceGuess,
                        positions = pending.subsetPositions,
                        matches = subsetScore,
                    )
                    val remainderTask = IsolationTask(
                        sourceGuess = pending.task.sourceGuess,
                        positions = pending.remainderPositions,
                        matches = pending.task.matches - subsetScore,
                    )
                    if (subsetTask.positions.isNotEmpty()) {
                        isolationTasks += subsetTask
                    }
                    if (remainderTask.positions.isNotEmpty()) {
                        isolationTasks += remainderTask
                    }
                } else {
                    stage = BotSolveStage.SAFE_BASE_SEARCH
                }
            }

            is PendingAction.GroupProbe -> {
                val isolatedScore = score - pending.baseScore
                when {
                    isolatedScore == pending.positions.size -> {
                        pending.positions.forEach { position ->
                            markResolved(position, guess[position])
                        }
                    }

                    isolatedScore == 0 -> {
                        pending.positions.forEach { position ->
                            markImpossible(position, guess[position])
                        }
                    }

                    isolatedScore in 1 until pending.positions.size -> {
                        isolationTasks += IsolationTask(
                            sourceGuess = guess,
                            positions = pending.positions,
                            matches = isolatedScore,
                        )
                    }

                    else -> {
                        stage = BotSolveStage.SAFE_BASE_SEARCH
                    }
                }
            }

            is PendingAction.FinalGuess -> {
                if (score == rules.codeLength) {
                    guess.forEachIndexed { index, symbol -> markResolved(index, symbol) }
                    stage = BotSolveStage.COMPLETE
                } else {
                    stage = BotSolveStage.VALUE_SEARCH
                }
            }

            is PendingAction.ExpertTailProbe -> {
                if (score == rules.codeLength) {
                    guess.forEachIndexed { index, symbol -> markResolved(index, symbol) }
                    stage = BotSolveStage.COMPLETE
                } else {
                    stage = BotSolveStage.VALUE_SEARCH
                }
            }
        }

        resolveForcedKnowledge()
        if (resolvedPositions.size == rules.codeLength && stage != BotSolveStage.COMPLETE) {
            stage = BotSolveStage.READY_TO_FINISH
        }
    }

    fun confirmedPositionsCount(): Int = resolvedPositions.size

    private fun usesGridSearch(): Boolean {
        return difficulty != BotDifficulty.EASY
    }

    private fun usesSplitIsolation(): Boolean {
        return difficulty != BotDifficulty.EASY
    }

    private fun usesGroupProbe(): Boolean {
        return difficulty == BotDifficulty.MEDIUM || difficulty == BotDifficulty.EXPERT
    }

    private fun usesExpertTailSolve(): Boolean {
        return difficulty == BotDifficulty.EXPERT
    }

    private fun usesGridIsolationSeed(): Boolean {
        return difficulty == BotDifficulty.HARD || difficulty == BotDifficulty.EXPERT
    }

    private fun planGridGuess(): String {
        val guess = gridGuesses[nextGridIndex]
        nextGridIndex += 1
        attemptedGuesses += guess
        pendingAction = PendingAction.GridGuess(guess)
        return guess
    }

    private fun planSafeBaseGuess(): String {
        val fixed = resolvedPositions.toMap()
        val options = buildMap<Int, List<Char>> {
            (0 until rules.codeLength)
                .filterNot(resolvedPositions::containsKey)
                .forEach { position ->
                    put(position, safeBaseOptionOrder())
                }
        }
        val guess = composeValidGuess(
            fixedPositions = fixed,
            freePositionOptions = options,
            mustBeNew = true,
        ) ?: fallbackGuess()

        attemptedGuesses += guess
        pendingAction = PendingAction.SafeBaseGuess(
            guess = guess,
            expectedScore = resolvedPositions.size,
        )
        return guess
    }

    private fun safeBaseOptionOrder(): List<Char> {
        return when (difficulty) {
            BotDifficulty.EASY -> rules.alphabet.shuffled(random)
            BotDifficulty.MEDIUM, BotDifficulty.HARD, BotDifficulty.EXPERT -> orderSymbols(rules.alphabet)
        }
    }

    private fun planPositionalGuess(): String? {
        val targetPosition = selectTargetPosition() ?: return null
        val candidate = nextCandidateForPosition(targetPosition) ?: return null
        val guess = buildPositionalProbeGuess(targetPosition, candidate) ?: return null

        testedCandidates[targetPosition] += candidate
        attemptedGuesses += guess
        pendingAction = PendingAction.PositionProbe(
            guess = guess,
            position = targetPosition,
            candidate = candidate,
            baseScore = resolvedPositions.size,
        )
        return guess
    }

    private fun planIsolationGuess(): String? {
        normalizeIsolationTasks()
        val task = selectIsolationTask() ?: return null
        val (subset, remainder) = selectIsolationSplit(task)
        val guess = buildIsolationProbe(task, subset) ?: return null

        attemptedGuesses += guess
        pendingAction = PendingAction.HardSplitProbe(
            guess = guess,
            task = task,
            subsetPositions = subset,
            remainderPositions = remainder,
            baseScore = resolvedPositions.size,
        )
        return guess
    }

    private fun selectIsolationSplit(task: IsolationTask): Pair<List<Int>, List<Int>> {
        if (difficulty != BotDifficulty.EXPERT) {
            val orderedPositions = orderPositions(task.positions)
            val splitSize = (orderedPositions.size + 1) / 2
            val subset = orderedPositions.take(splitSize)
            val remainder = orderedPositions.drop(splitSize)
            return subset to remainder
        }

        val sortedPositions = task.positions.sorted()
        val contiguousGroups = sortedPositions
            .fold(mutableListOf<MutableList<Int>>()) { groups, position ->
                val current = groups.lastOrNull()
                if (current == null || current.last() + 1 != position) {
                    groups += mutableListOf(position)
                } else {
                    current += position
                }
                groups
            }
            .map { it.toList() }

        val focusedGroup = contiguousGroups
            .filter { it.size >= 2 }
            .maxWithOrNull(
                compareBy<List<Int>> { it.size }
                    .thenByDescending { it.firstOrNull() ?: -1 },
            )

        if (focusedGroup == null) {
            val orderedPositions = sortedPositions
            val splitSize = (orderedPositions.size + 1) / 2
            val subset = orderedPositions.take(splitSize)
            val remainder = orderedPositions.drop(splitSize)
            return subset to remainder
        }

        val splitSize = (focusedGroup.size + 1) / 2
        val subset = focusedGroup.take(splitSize)
        val remainder = sortedPositions.filterNot(subset::contains)
        return subset to remainder
    }

    private fun planGroupProbeGuess(): String? {
        if (!usesGroupProbe()) return null
        val unresolved = groupProbePositions() ?: return null

        val guess = composeValidGuess(
            fixedPositions = resolvedPositions.toMap(),
            freePositionOptions = unresolved.associateWith { position ->
                orderSymbols(candidates[position].toList())
            },
            mustBeNew = true,
        ) ?: return null

        attemptedGuesses += guess
        pendingAction = PendingAction.GroupProbe(
            guess = guess,
            positions = unresolved,
            baseScore = resolvedPositions.size,
        )
        return guess
    }

    private fun planExpertTailGuess(): String? {
        if (!usesExpertTailSolve()) return null
        val unresolved = (0 until rules.codeLength).filterNot(resolvedPositions::containsKey)
        if (unresolved.size !in 2..5) return null
        if (resolvedPositions.isEmpty()) return null
        val searchSpace = unresolved.fold(1L) { acc, position ->
            (acc * candidates[position].size.toLong()).coerceAtMost(25_001L)
        }
        if (searchSpace > 25_000L) return null

        val solutions = buildExpertTailSolutions(unresolved)
        if (solutions.isEmpty()) return null

        applyExpertTailKnowledge(unresolved, solutions)
        if (resolvedPositions.size == rules.codeLength) {
            stage = BotSolveStage.READY_TO_FINISH
            return planFinalGuess()
        }

        val freshSolutions = buildExpertTailSolutions(unresolved)
        if (freshSolutions.isEmpty()) return null
        val guess = selectExpertTailProbe(freshSolutions) ?: return null

        attemptedGuesses += guess
        pendingAction = PendingAction.ExpertTailProbe(guess)
        return guess
    }

    private fun groupProbePositions(): List<Int>? {
        val unresolved = (0 until rules.codeLength)
            .filterNot(resolvedPositions::containsKey)
        if (unresolved.size < 2) return null

        return when (difficulty) {
            BotDifficulty.MEDIUM -> orderPositions(unresolved)
            BotDifficulty.EXPERT -> {
                val contiguousGroups = unresolved
                    .sorted()
                    .fold(mutableListOf<MutableList<Int>>()) { groups, position ->
                        val current = groups.lastOrNull()
                        if (current == null || current.last() + 1 != position) {
                            groups += mutableListOf(position)
                        } else {
                            current += position
                        }
                        groups
                    }
                    .map { group -> orderPositions(group) }

                val selectedGroup = contiguousGroups
                    .filter { it.size >= 2 }
                    .maxWithOrNull(
                        compareBy<List<Int>> { it.size }
                            .thenByDescending { it.firstOrNull() ?: -1 },
                    )

                selectedGroup ?: orderPositions(unresolved)
            }

            else -> null
        }
    }

    private fun buildExpertTailSolutions(unresolved: List<Int>): List<String> {
        val fixedPositions = resolvedPositions.toMap()
        val unresolvedSet = unresolved.toSet()
        val solutions = mutableListOf<String>()
        val current = StringBuilder()
        val used = linkedSetOf<Char>()

        fun canPlace(symbol: Char): Boolean {
            if (!rules.allowDuplicates && symbol in used) {
                return false
            }

            if (rules.forbidAdjacentDuplicates) {
                val previous = current.lastOrNull()
                if (previous == symbol) {
                    return false
                }
            }

            if (rules.forbidTripleDuplicates && current.length >= 2) {
                val previous = current[current.lastIndex]
                val secondPrevious = current[current.lastIndex - 1]
                if (previous == symbol && secondPrevious == symbol) {
                    return false
                }
            }

            return true
        }

        fun backtrack(position: Int) {
            if (position == rules.codeLength) {
                val candidate = current.toString()
                if (!rules.isValidCode(candidate)) return
                val matchesHistory = actualHistory.all { observation ->
                    ScoreCalculator.countExactMatches(candidate, observation.guess) == observation.score
                }
                if (matchesHistory) {
                    solutions += candidate
                }
                return
            }

            val options = fixedPositions[position]?.let(::listOf)
                ?: if (position in unresolvedSet) orderSymbols(candidates[position].toList()) else emptyList()

            for (symbol in options) {
                if (!canPlace(symbol)) continue
                current.append(symbol)
                val added = used.add(symbol)
                backtrack(position + 1)
                if (added) {
                    used.remove(symbol)
                }
                current.deleteCharAt(current.lastIndex)
            }
        }

        backtrack(position = 0)
        return solutions
    }

    private fun applyExpertTailKnowledge(
        unresolved: List<Int>,
        solutions: List<String>,
    ) {
        unresolved.forEach { position ->
            val viable = solutions.map { candidate -> candidate[position] }.toSet()
            candidates[position].retainAll(viable)
            safeSymbols[position] += rules.alphabet.filterNot(viable::contains)
            if (viable.size == 1) {
                markResolved(position, viable.first())
            }
        }
    }

    private fun selectExpertTailProbe(solutions: List<String>): String? {
        if (solutions.size == 1) return solutions.first()

        val candidatePool = solutions.filterNot(attemptedGuesses::contains)
            .ifEmpty { solutions }
        val probeWindow = if (candidatePool.size <= 128) {
            candidatePool
        } else {
            candidatePool.take(128)
        }

        return probeWindow.minWithOrNull(
            compareBy<String> { probe ->
                solutions.groupingBy { target ->
                    ScoreCalculator.countExactMatches(probe, target)
                }.eachCount().values.maxOrNull() ?: Int.MAX_VALUE
            }.thenBy { probe ->
                solutions.groupingBy { target ->
                    ScoreCalculator.countExactMatches(probe, target)
                }.eachCount().values.sumOf { bucketSize -> bucketSize * bucketSize }
            },
        )
    }

    private fun planFinalGuess(): String {
        val guess = (0 until rules.codeLength)
            .map { index -> resolvedPositions.getValue(index) }
            .joinToString("")
        attemptedGuesses += guess
        pendingAction = PendingAction.FinalGuess(guess)
        return guess
    }

    private fun advanceSyntheticGridKnowledgeIfNeeded() {
        if (stage != BotSolveStage.GRID_SEARCH) return

        if (gridScoreSum >= rules.codeLength) {
            while (nextGridIndex < gridGuesses.size) {
                applyGridObservation(
                    guess = gridGuesses[nextGridIndex],
                    score = 0,
                    isActualMove = false,
                )
                nextGridIndex += 1
            }
        } else if (nextGridIndex == gridGuesses.lastIndex) {
            val deducedScore = rules.codeLength - gridScoreSum
            applyGridObservation(
                guess = gridGuesses[nextGridIndex],
                score = deducedScore,
                isActualMove = false,
            )
            nextGridIndex += 1
        }

        if (nextGridIndex >= gridGuesses.size) {
            finishGridSearch()
        }
    }

    private fun finishGridSearch() {
        if (stage != BotSolveStage.GRID_SEARCH) return

        (0 until rules.codeLength).forEach { position ->
            val positiveCandidates = positiveGridEvidence[position].toMutableSet()
            if (positiveCandidates.isNotEmpty()) {
                candidates[position].retainAll(positiveCandidates)
            }
            safeSymbols[position] += rules.alphabet.filterNot(candidates[position]::contains)
            if (safeBaseGuess != null) {
                safeSymbols[position] += safeBaseGuess!![position]
            }
        }

        if (usesGridIsolationSeed()) {
            gridKnowledge.filter { it.score > 0 }
                .forEach { observation ->
                    isolationTasks += IsolationTask(
                        sourceGuess = observation.guess,
                        positions = (0 until rules.codeLength).toList(),
                        matches = observation.score,
                    )
                }
        }

        stage = BotSolveStage.VALUE_SEARCH
        resolveForcedKnowledge()
    }

    private fun applyGridObservation(guess: String, score: Int, isActualMove: Boolean) {
        gridKnowledge += BotObservation(guess = guess, score = score)
        if (isActualMove) {
            gridScoreSum += score
        } else if (nextGridIndex == gridGuesses.size || guess == gridGuesses.last()) {
            gridScoreSum += score
        }

        if (score == 0) {
            guess.forEachIndexed { index, symbol ->
                markImpossible(index, symbol)
            }
            return
        }

        guess.forEachIndexed { index, symbol ->
            positiveGridEvidence[index] += symbol
        }
    }

    private fun resolveForcedKnowledge() {
        var changed: Boolean
        do {
            changed = false

            (0 until rules.codeLength).forEach { position ->
                if (position !in resolvedPositions && candidates[position].size == 1) {
                    if (markResolved(position, candidates[position].first())) {
                        changed = true
                    }
                }
            }

            if (usesSplitIsolation()) {
                if (normalizeIsolationTasks()) {
                    changed = true
                }
            }
        } while (changed)
    }

    private fun normalizeIsolationTasks(): Boolean {
        if (!usesSplitIsolation()) return false
        var changed = false
        if (isolationTasks.isEmpty()) return false

        val snapshot = isolationTasks.toList()
        isolationTasks.clear()

        snapshot.forEach { task ->
            var matches = task.matches
            val remaining = mutableListOf<Int>()

            task.positions.forEach { position ->
                val resolved = resolvedPositions[position]
                when {
                    resolved != null -> {
                        if (resolved == task.sourceGuess[position]) {
                            matches -= 1
                        }
                        changed = true
                    }

                    task.sourceGuess[position] !in candidates[position] -> {
                        changed = true
                    }

                    else -> remaining += position
                }
            }

            val safeMatches = matches.coerceAtLeast(0)
            when {
                safeMatches == 0 -> {
                    remaining.forEach { position ->
                        if (markImpossible(position, task.sourceGuess[position])) {
                            changed = true
                        }
                    }
                }

                remaining.isEmpty() -> {
                    changed = true
                }

                safeMatches >= remaining.size -> {
                    remaining.forEach { position ->
                        if (markResolved(position, task.sourceGuess[position])) {
                            changed = true
                        }
                    }
                }

                else -> {
                    isolationTasks += task.copy(
                        positions = remaining,
                        matches = safeMatches,
                    )
                }
            }
        }

        return changed
    }

    private fun selectIsolationTask(): IsolationTask? {
        if (isolationTasks.isEmpty()) return null
        val viable = isolationTasks.filter { task ->
            task.positions.size > 1 && task.matches in 1 until task.positions.size
        }
        if (viable.isEmpty()) return null

        val selected = when (behavior) {
            BotBehaviorModel.SCOUT -> viable.random(random)
            BotBehaviorModel.BALANCED -> viable.sortedWith(
                compareByDescending<IsolationTask> { it.matches }
                    .thenBy { it.positions.size },
            ).first()
            BotBehaviorModel.ANALYST -> viable.sortedWith(
                compareBy<IsolationTask> { it.positions.size }
                    .thenByDescending { it.matches },
            ).first()
        }
        isolationTasks.remove(selected)
        return selected
    }

    private fun selectTargetPosition(): Int? {
        val unresolved = (0 until rules.codeLength).filterNot(resolvedPositions::containsKey)
        if (unresolved.isEmpty()) return null

        return when (difficulty) {
            BotDifficulty.EASY -> unresolved.random(random)
            BotDifficulty.MEDIUM -> unresolved.minByOrNull { candidates[it].size }
            BotDifficulty.HARD, BotDifficulty.EXPERT -> orderPositions(unresolved).firstOrNull()
        }
    }

    private fun nextCandidateForPosition(position: Int): Char? {
        val pool = when (difficulty) {
            BotDifficulty.EASY -> rules.alphabet.filter { symbol ->
                symbol !in safeSymbols[position] && symbol !in testedCandidates[position]
            }

            BotDifficulty.MEDIUM, BotDifficulty.HARD, BotDifficulty.EXPERT -> candidates[position].filterNot(testedCandidates[position]::contains)
        }

        if (pool.isEmpty()) return null
        return orderSymbols(pool).firstOrNull()
    }

    private fun buildPositionalProbeGuess(position: Int, candidate: Char): String? {
        val fixed = resolvedPositions.toMutableMap()
        fixed[position] = candidate
        val options = buildMap<Int, List<Char>> {
            (0 until rules.codeLength)
                .filter { it !in fixed.keys }
                .forEach { index ->
                    val safe = safeOptionList(index)
                    if (safe.isEmpty()) return null
                    put(index, safe)
                }
        }

        return composeValidGuess(
            fixedPositions = fixed,
            freePositionOptions = options,
            mustBeNew = true,
        )
    }

    private fun buildIsolationProbe(task: IsolationTask, subsetPositions: List<Int>): String? {
        val fixed = resolvedPositions.toMutableMap()
        subsetPositions.forEach { position ->
            fixed[position] = task.sourceGuess[position]
        }

        val options = buildMap<Int, List<Char>> {
            (0 until rules.codeLength)
                .filter { it !in fixed.keys }
                .forEach { index ->
                    val safe = safeOptionList(index)
                    if (safe.isEmpty()) return null
                    put(index, safe)
                }
        }

        return composeValidGuess(
            fixedPositions = fixed,
            freePositionOptions = options,
            mustBeNew = true,
        )
    }

    private fun safeOptionList(position: Int): List<Char> {
        val safe = safeSymbols[position].ifEmpty {
            val derived = rules.alphabet.filterNot(candidates[position]::contains)
            safeSymbols[position] += derived
            safeSymbols[position]
        }
        return orderSymbols(safe.toList())
    }

    private fun orderPositions(positions: List<Int>): List<Int> {
        return when (behavior) {
            BotBehaviorModel.SCOUT -> positions.shuffled(random)
            BotBehaviorModel.BALANCED -> positions.sortedBy { candidates[it].size }
            BotBehaviorModel.ANALYST -> positions.sortedWith(
                compareBy<Int> { candidates[it].size }.thenBy { it },
            )
        }
    }

    private fun orderSymbols(symbols: List<Char>): List<Char> {
        return when (behavior) {
            BotBehaviorModel.SCOUT -> symbols.shuffled(random)
            BotBehaviorModel.BALANCED -> symbols.sorted()
            BotBehaviorModel.ANALYST -> symbols.sorted()
        }
    }

    private fun composeValidGuess(
        fixedPositions: Map<Int, Char>,
        freePositionOptions: Map<Int, List<Char>>,
        mustBeNew: Boolean,
    ): String? {
        val used = linkedSetOf<Char>()
        val current = StringBuilder()

        fun canPlace(symbol: Char): Boolean {
            if (!rules.allowDuplicates && symbol in used) {
                return false
            }

            if (rules.forbidAdjacentDuplicates) {
                val previous = current.lastOrNull()
                if (previous == symbol) {
                    return false
                }
            }

            if (rules.forbidTripleDuplicates && current.length >= 2) {
                val previous = current[current.lastIndex]
                val secondPrevious = current[current.lastIndex - 1]
                if (previous == symbol && secondPrevious == symbol) {
                    return false
                }
            }

            return true
        }

        fun backtrack(position: Int): String? {
            if (position == rules.codeLength) {
                val guess = current.toString()
                if (!rules.isValidCode(guess)) return null
                if (mustBeNew && guess in attemptedGuesses) return null
                return guess
            }

            val options = fixedPositions[position]?.let(::listOf)
                ?: freePositionOptions[position]
                ?: orderSymbols(rules.alphabet)

            for (symbol in options) {
                if (!canPlace(symbol)) continue
                current.append(symbol)
                val added = used.add(symbol)
                val result = backtrack(position + 1)
                if (result != null) {
                    return result
                }
                if (added) {
                    used.remove(symbol)
                }
                current.deleteCharAt(current.lastIndex)
            }

            return null
        }

        return backtrack(position = 0)
    }

    private fun markImpossible(position: Int, symbol: Char): Boolean {
        if (resolvedPositions[position] == symbol) return false
        val removed = candidates[position].remove(symbol)
        if (removed) {
            safeSymbols[position] += symbol
        }
        return removed
    }

    private fun markResolved(position: Int, symbol: Char): Boolean {
        val current = resolvedPositions[position]
        if (current == symbol) return false
        if (current != null && current != symbol) return false
        if (symbol !in candidates[position]) {
            candidates[position] += symbol
        }
        resolvedPositions[position] = symbol
        candidates[position].clear()
        candidates[position] += symbol
        safeSymbols[position].clear()
        safeSymbols[position] += rules.alphabet.filterNot { it == symbol }
        return true
    }

    private fun fallbackGuess(): String {
        val fixed = resolvedPositions.toMap()
        return composeValidGuess(
            fixedPositions = fixed,
            freePositionOptions = emptyMap(),
            mustBeNew = true,
        ) ?: composeValidGuess(
            fixedPositions = fixed,
            freePositionOptions = emptyMap(),
            mustBeNew = false,
        ) ?: error("Bot could not compose a valid guess")
    }

    private data class IsolationTask(
        val sourceGuess: String,
        val positions: List<Int>,
        val matches: Int,
    )

    private sealed interface PendingAction {
        val guess: String

        data class GridGuess(
            override val guess: String,
        ) : PendingAction

        data class SafeBaseGuess(
            override val guess: String,
            val expectedScore: Int,
        ) : PendingAction

        data class PositionProbe(
            override val guess: String,
            val position: Int,
            val candidate: Char,
            val baseScore: Int,
        ) : PendingAction

        data class HardSplitProbe(
            override val guess: String,
            val task: IsolationTask,
            val subsetPositions: List<Int>,
            val remainderPositions: List<Int>,
            val baseScore: Int,
        ) : PendingAction

        data class GroupProbe(
            override val guess: String,
            val positions: List<Int>,
            val baseScore: Int,
        ) : PendingAction

        data class FinalGuess(
            override val guess: String,
        ) : PendingAction

        data class ExpertTailProbe(
            override val guess: String,
        ) : PendingAction
    }
}
