package com.mirkori.inplacex.ui.screens.game.state

import androidx.lifecycle.SavedStateHandle
import com.mirkori.inplacex.core.analysis.AcceptedAttemptEvidence
import com.mirkori.inplacex.core.analysis.EvidenceDeductionEngine
import com.mirkori.inplacex.core.analysis.EvidenceInput
import com.mirkori.inplacex.core.analysis.HypothesisKind
import com.mirkori.inplacex.core.analysis.ManualHypothesis
import com.mirkori.inplacex.core.analysis.ProvenFact
import com.mirkori.inplacex.core.engine.GameEngine
import com.mirkori.inplacex.core.match.MatchAttempt
import com.mirkori.inplacex.core.match.MatchCheckpoint
import com.mirkori.inplacex.core.match.MatchEngine
import com.mirkori.inplacex.core.match.MatchFeedback
import com.mirkori.inplacex.core.match.MatchSnapshot
import com.mirkori.inplacex.core.model.GameConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Владелец одной GameField-сессии без Compose-состояния и одноразовых UI-эффектов. */
class GameFieldStateHolder(
    private val savedStateHandle: SavedStateHandle,
    private val parameters: GameFieldMatchParameters = GameFieldMatchParameters(),
    private val initialSecret: String? = null,
    private val engineFactory: (GameConfig) -> MatchEngine = ::GameEngine,
) {
    private val engine = engineFactory(parameters.toGameConfig())
    private val savedStateStore = GameFieldSavedStateStore(savedStateHandle)
    private var latestSnapshot: MatchSnapshot

    private val _state: MutableStateFlow<GameFieldUiState>
    val state: StateFlow<GameFieldUiState>
        get() = _state.asStateFlow()

    init {
        val restored = savedStateStore.restore(parameters)
        if (restored != null && engine.restoreCheckpoint(restored.checkpoint)) {
            latestSnapshot = engine.snapshot()
            _state = MutableStateFlow(
                createState(
                    snapshot = latestSnapshot,
                    input = restored.input,
                    manualMarks = restored.manualMarks,
                    provenFacts = restored.provenFacts,
                    timers = restored.timers,
                    tools = restored.tools,
                    counters = restored.counters,
                    status = GameFieldStatus.Idle,
                ),
            )
        } else {
            latestSnapshot = engine.start(initialSecret)
            _state = MutableStateFlow(createState(snapshot = latestSnapshot))
        }
        persist()
    }

    fun dispatch(event: GameFieldEvent) {
        val current = _state.value
        when (event) {
            is GameFieldEvent.DigitEntered -> enterDigit(current, event.digit)
            GameFieldEvent.BackspacePressed -> backspace(current)
            GameFieldEvent.GuessSubmitted -> submitGuess(current)
            GameFieldEvent.MatchRestarted -> restart()
            is GameFieldEvent.ToolSelected -> update(current.copy(tools = current.tools.copy(selectedTool = event.tool)))
            is GameFieldEvent.HintSelected -> update(current.copy(tools = current.tools.copy(selectedHint = event.hint)))
            is GameFieldEvent.AutoExcludeChanged -> update(
                current.copy(tools = current.tools.copy(autoExcludeEnabled = event.enabled)),
            )

            is GameFieldEvent.ManualMarkChanged -> changeManualMark(current, event)
            is GameFieldEvent.ProvenFactRecorded -> update(
                current.copy(evidence = current.evidence.copy(provenFacts = current.evidence.provenFacts + event.fact)),
            )

            is GameFieldEvent.HintConsumed -> update(current.copy(counters = current.counters.afterHint(event.hint)))
            is GameFieldEvent.BoostConsumed -> consumeBoost(current, event)
            is GameFieldEvent.TimerTicked -> tick(current, event.seconds)
        }
    }

    fun currentSnapshot(): MatchSnapshot = latestSnapshot

    fun submitRawGuess(guess: String) {
        submitGuess(_state.value, guess)
    }

    private fun enterDigit(current: GameFieldUiState, digit: Char) {
        if (digit !in '0'..'9') return
        val confirmedPositions = current.evidence.provenFacts
            .asSequence()
            .filter(ProvenFact::isExactMatch)
            .map(ProvenFact::position)
            .toSet()
        val position = current.input.slots.indices.firstOrNull { index ->
            index !in confirmedPositions && current.input.slots[index] == null
        } ?: return
        val slots = current.input.slots.toMutableList().apply { this[position] = digit }
        update(current.copy(input = GameFieldInputState(slots), status = GameFieldStatus.Idle))
    }

    private fun backspace(current: GameFieldUiState) {
        val confirmedPositions = current.evidence.provenFacts
            .asSequence()
            .filter(ProvenFact::isExactMatch)
            .map(ProvenFact::position)
            .toSet()
        val position = current.input.slots.indices.reversed().firstOrNull { index ->
            index !in confirmedPositions && current.input.slots[index] != null
        } ?: return
        val slots = current.input.slots.toMutableList().apply { this[position] = null }
        update(current.copy(input = GameFieldInputState(slots), status = GameFieldStatus.Idle))
    }

    private fun submitGuess(current: GameFieldUiState, rawGuess: String? = null) {
        val guess = rawGuess ?: current.input.guessOrNull(current.evidence.provenFacts)
        if (guess == null) {
            update(current.copy(status = GameFieldStatus.InputIncomplete))
            return
        }

        val beforeAttemptCount = latestSnapshot.attempts.size
        latestSnapshot = engine.submit(guess)
        val acceptedAttempt = latestSnapshot.attempts.lastOrNull()
            ?.takeIf { latestSnapshot.attempts.size > beforeAttemptCount }
        update(
            createState(
                snapshot = latestSnapshot,
                input = if (acceptedAttempt == null) current.input else GameFieldInputState.empty(parameters.codeLength),
                manualMarks = current.manualMarks,
                provenFacts = current.evidence.provenFacts,
                timers = if (acceptedAttempt == null) current.timers else current.timers.copy(turnElapsedSeconds = 0),
                tools = current.tools,
                counters = current.counters,
                status = acceptedAttempt?.let(GameFieldStatus::AttemptAccepted)
                    ?: GameFieldStatus.EngineFeedback(latestSnapshot.feedback),
            ),
        )
    }

    private fun restart() {
        latestSnapshot = engine.start(initialSecret)
        update(createState(snapshot = latestSnapshot))
    }

    private fun changeManualMark(
        current: GameFieldUiState,
        event: GameFieldEvent.ManualMarkChanged,
    ) {
        if (event.position !in 0 until parameters.codeLength || event.symbol !in '0'..'9') return
        val retained = current.manualMarks.filterNot {
            it.position == event.position && it.symbol == event.symbol
        }
        val marks = event.type?.let { retained + GameFieldManualMark(event.position, event.symbol, it) } ?: retained
        update(current.copy(manualMarks = marks))
    }

    private fun consumeBoost(current: GameFieldUiState, event: GameFieldEvent.BoostConsumed) {
        if (event.amount <= 0) return
        when (event.boost) {
            GameFieldBoostMode.EXTRA_MOVES -> {
                latestSnapshot = engine.grantExtraMoves(event.amount)
                update(
                    createState(
                        snapshot = latestSnapshot,
                        input = current.input,
                        manualMarks = current.manualMarks,
                        provenFacts = current.evidence.provenFacts,
                        timers = current.timers,
                        tools = current.tools,
                        counters = current.counters.copy(
                            extraMovesBoostUses = current.counters.extraMovesBoostUses + 1,
                            bonusMoves = current.counters.bonusMoves + event.amount,
                        ),
                        status = GameFieldStatus.EngineFeedback(latestSnapshot.feedback),
                    ),
                )
            }

            GameFieldBoostMode.EXTRA_TIME -> update(
                current.copy(
                    timers = current.timers.copy(bonusTimeSeconds = current.timers.bonusTimeSeconds + event.amount),
                    counters = current.counters.copy(extraTimeBoostUses = current.counters.extraTimeBoostUses + 1),
                ),
            )
        }
    }

    private fun tick(current: GameFieldUiState, seconds: Int) {
        if (seconds <= 0 || latestSnapshot.phase != com.mirkori.inplacex.core.match.MatchPhase.ACTIVE) return
        update(
            current.copy(
                timers = current.timers.copy(
                    elapsedSeconds = current.timers.elapsedSeconds + seconds,
                    turnElapsedSeconds = current.timers.turnElapsedSeconds + seconds,
                ),
            ),
        )
    }

    private fun update(state: GameFieldUiState) {
        _state.value = rebuildEvidence(state)
        persist()
    }

    private fun persist() {
        savedStateStore.save(_state.value, engine.checkpoint())
    }

    private fun createState(
        snapshot: MatchSnapshot,
        input: GameFieldInputState = GameFieldInputState.empty(parameters.codeLength),
        manualMarks: List<GameFieldManualMark> = emptyList(),
        provenFacts: Set<ProvenFact> = emptySet(),
        timers: GameFieldTimers = GameFieldTimers(),
        tools: GameFieldToolsState = GameFieldToolsState(autoExcludeEnabled = parameters.autoModeAvailable),
        counters: GameFieldCounters = GameFieldCounters(),
        status: GameFieldStatus = GameFieldStatus.Idle,
    ): GameFieldUiState {
        return rebuildEvidence(
            GameFieldUiState(
                parameters = parameters,
                match = snapshot.toState(),
                input = input,
                evidence = GameFieldEvidenceState(emptyList(), emptyList(), provenFacts, emptyDeduction()),
                manualMarks = manualMarks,
                timers = timers,
                tools = tools,
                counters = counters,
                status = status,
            ),
        )
    }

    private fun rebuildEvidence(state: GameFieldUiState): GameFieldUiState {
        val acceptedAttempts = state.match.attempts.map { AcceptedAttemptEvidence(it.guess, it.score) }
        val hypotheses = state.manualMarks.map {
            ManualHypothesis(
                position = it.position,
                symbol = it.symbol,
                kind = if (it.type == GameFieldManualMarkType.NO) {
                    HypothesisKind.IMPOSSIBLE
                } else {
                    HypothesisKind.POSSIBLE
                },
            )
        }
        val deduction = EvidenceDeductionEngine(parameters.codeLength).infer(
            EvidenceInput(hypotheses, acceptedAttempts, state.evidence.provenFacts.toList()),
        )
        return state.copy(
            evidence = GameFieldEvidenceState(
                acceptedAttempts = acceptedAttempts,
                hypotheses = hypotheses,
                provenFacts = state.evidence.provenFacts,
                deduction = deduction,
            ),
        )
    }

    private fun emptyDeduction() = EvidenceDeductionEngine(parameters.codeLength).infer()

    private fun GameFieldCounters.afterHint(hint: GameFieldHintMode): GameFieldCounters = when (hint) {
        GameFieldHintMode.OPEN_POSITION -> copy(openPositionHintUses = openPositionHintUses + 1)
        GameFieldHintMode.CHECK_DIGIT -> copy(checkDigitHintUses = checkDigitHintUses + 1)
        GameFieldHintMode.CHECK_POSITION -> copy(checkPositionHintUses = checkPositionHintUses + 1)
    }

    private fun GameFieldMatchParameters.toGameConfig(): GameConfig = GameConfig(
        codeLength = codeLength,
        allowDuplicates = allowDuplicates,
        attemptLimit = attemptLimit,
    )

    private fun MatchSnapshot.toState(): GameFieldMatchState = GameFieldMatchState(
        phase = phase,
        attempts = attempts,
        attemptsLeft = attemptsLeft,
        debugSecret = debugSecret,
    )
}
