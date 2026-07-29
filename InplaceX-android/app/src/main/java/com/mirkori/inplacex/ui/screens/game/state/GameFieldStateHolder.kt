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
import com.mirkori.inplacex.core.match.MatchEngine
import com.mirkori.inplacex.core.match.MatchFeedback
import com.mirkori.inplacex.core.match.MatchHintResult
import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.core.match.MatchSnapshot
import com.mirkori.inplacex.core.model.GameConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Владелец одной GameField-сессии без Compose-состояния и одноразовых внешних callback-ов. */
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
            is GameFieldEvent.ToolSelected -> update(
                current.copy(tools = current.tools.copy(selectedTool = event.tool), status = GameFieldStatus.Idle),
            )

            is GameFieldEvent.HintSelected -> update(
                current.copy(tools = current.tools.copy(selectedHint = event.hint), status = GameFieldStatus.Idle),
            )

            is GameFieldEvent.AutoExcludeChanged -> update(
                current.copy(
                    tools = current.tools.copy(autoExcludeEnabled = event.enabled),
                    status = GameFieldStatus.Notice(
                        if (event.enabled) GameFieldNotice.AutoEnabled else GameFieldNotice.AutoDisabled,
                    ),
                ),
            )

            is GameFieldEvent.ManualMarkChanged -> changeManualMark(current, event)
            is GameFieldEvent.ProvenFactRecorded -> update(
                current.copy(
                    evidence = current.evidence.copy(provenFacts = current.evidence.provenFacts + event.fact),
                    status = GameFieldStatus.Idle,
                ),
            )

            is GameFieldEvent.HintConsumed -> update(
                current.copy(counters = current.counters.afterHint(event.hint)),
            )

            is GameFieldEvent.PositionHintRequested -> checkPosition(current, event)
            is GameFieldEvent.OpenPositionHintRequested -> openPosition(current, event.position)
            is GameFieldEvent.DigitHintRequested -> checkDigit(current, event.digit)
            is GameFieldEvent.BoostConsumed -> consumeBoost(current, event)
            is GameFieldEvent.TimerTicked -> tick(current, event.seconds)
            is GameFieldEvent.NoticeChanged -> update(
                current.copy(status = event.notice?.let(GameFieldStatus::Notice) ?: GameFieldStatus.Idle),
            )
        }
    }

    fun currentSnapshot(): MatchSnapshot = latestSnapshot

    fun submitRawGuess(guess: String) {
        submitGuess(_state.value, guess)
    }

    private fun enterDigit(current: GameFieldUiState, digit: Char) {
        if (digit !in '0'..'9') return
        val confirmedPositions = effectiveFacts(current)
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
        val confirmedPositions = effectiveFacts(current)
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
        val guess = rawGuess ?: current.input.guessOrNull(effectiveFacts(current))
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
                input = if (acceptedAttempt == null) {
                    current.input
                } else {
                    inputFromManualConfirmations(current.manualMarks)
                },
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

    private fun inputFromManualConfirmations(
        manualMarks: Collection<GameFieldManualMark>,
    ): GameFieldInputState {
        val slots = MutableList<Char?>(parameters.codeLength) { null }
        manualMarks
            .asSequence()
            .filter { it.type == GameFieldManualMarkType.YES }
            .filter { it.position in slots.indices }
            .forEach { mark -> slots[mark.position] = mark.symbol }
        return GameFieldInputState(slots)
    }

    private fun restart() {
        latestSnapshot = engine.start(initialSecret)
        update(
            createState(
                snapshot = latestSnapshot,
                status = GameFieldStatus.Notice(GameFieldNotice.NewSecret),
            ),
        )
    }

    private fun changeManualMark(
        current: GameFieldUiState,
        event: GameFieldEvent.ManualMarkChanged,
    ) {
        if (event.position !in 0 until parameters.codeLength || event.symbol !in '0'..'9') return
        val existing = current.manualMarks.lastOrNull {
            it.position == event.position && it.symbol == event.symbol
        }
        var retained = current.manualMarks.filterNot {
            it.position == event.position && it.symbol == event.symbol
        }
        if (event.type == GameFieldManualMarkType.YES) {
            retained = retained.filterNot {
                it.position == event.position && it.type == GameFieldManualMarkType.YES
            }
        }
        val nextType = event.type?.takeUnless { existing?.type == it }
        val marks = nextType?.let {
            retained + GameFieldManualMark(event.position, event.symbol, it)
        } ?: retained
        val input = when {
            nextType == GameFieldManualMarkType.YES -> current.input.withSlot(
                position = event.position,
                symbol = event.symbol,
            )

            existing?.type == GameFieldManualMarkType.YES &&
                current.input.slots[event.position] == event.symbol -> current.input.withSlot(
                    position = event.position,
                    symbol = null,
                )

            else -> current.input
        }
        update(
            current.copy(
                input = input,
                manualMarks = marks,
                status = GameFieldStatus.Idle,
            ),
        )
    }

    private fun checkPosition(
        current: GameFieldUiState,
        event: GameFieldEvent.PositionHintRequested,
    ) {
        val outcome = engine.checkPosition(event.digit, event.position)
        latestSnapshot = outcome.snapshot
        val result = outcome.result as? MatchHintResult.PositionChecked
        val facts = if (result == null) {
            current.evidence.provenFacts
        } else {
            current.evidence.provenFacts + if (result.isMatch) {
                ProvenFact.exactMatch(result.position, result.digit.digitToChar())
            } else {
                ProvenFact.notAtPosition(result.position, result.digit.digitToChar())
            }
        }
        update(
            current.copy(
                match = latestSnapshot.toState(),
                evidence = current.evidence.copy(provenFacts = facts),
                tools = current.tools.copy(selectedHint = null),
                counters = current.counters.afterHint(GameFieldHintMode.CHECK_POSITION),
                status = result?.let {
                    GameFieldStatus.HintPositionChecked(it.digit, it.position, it.isMatch)
                } ?: GameFieldStatus.Notice(GameFieldNotice.HintUnavailable),
            ),
        )
    }

    private fun openPosition(current: GameFieldUiState, position: Int) {
        val outcome = engine.openPosition(position)
        latestSnapshot = outcome.snapshot
        val result = outcome.result as? MatchHintResult.PositionOpened
        val facts = result?.let {
            current.evidence.provenFacts + ProvenFact.exactMatch(it.position, it.digit.digitToChar())
        } ?: current.evidence.provenFacts
        update(
            current.copy(
                match = latestSnapshot.toState(),
                evidence = current.evidence.copy(provenFacts = facts),
                tools = current.tools.copy(selectedHint = null),
                counters = current.counters.afterHint(GameFieldHintMode.OPEN_POSITION),
                status = result?.let {
                    GameFieldStatus.HintPositionOpened(it.digit, it.position)
                } ?: GameFieldStatus.Notice(GameFieldNotice.HintUnavailable),
            ),
        )
    }

    private fun checkDigit(current: GameFieldUiState, digit: Int) {
        val outcome = engine.checkDigitCount(digit)
        latestSnapshot = outcome.snapshot
        val result = outcome.result as? MatchHintResult.DigitCountChecked
        val facts = if (result?.count == 0) {
            current.evidence.provenFacts + (0 until parameters.codeLength).map { position ->
                ProvenFact.notAtPosition(position, result.digit.digitToChar())
            }
        } else {
            current.evidence.provenFacts
        }
        update(
            current.copy(
                match = latestSnapshot.toState(),
                evidence = current.evidence.copy(provenFacts = facts),
                tools = current.tools.copy(selectedHint = null),
                counters = current.counters.afterHint(GameFieldHintMode.CHECK_DIGIT),
                status = result?.let {
                    GameFieldStatus.HintDigitCount(it.digit, it.count)
                } ?: GameFieldStatus.Notice(GameFieldNotice.HintUnavailable),
            ),
        )
    }

    private fun consumeBoost(current: GameFieldUiState, event: GameFieldEvent.BoostConsumed) {
        if (event.amount <= 0 || latestSnapshot.phase != MatchPhase.ACTIVE) return
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
                        status = GameFieldStatus.Notice(GameFieldNotice.MovesAdded(event.amount)),
                    ),
                )
            }

            GameFieldBoostMode.EXTRA_TIME -> update(
                current.copy(
                    timers = current.timers.copy(bonusTimeSeconds = current.timers.bonusTimeSeconds + event.amount),
                    counters = current.counters.copy(extraTimeBoostUses = current.counters.extraTimeBoostUses + 1),
                    status = GameFieldStatus.Notice(GameFieldNotice.TimeAdded(event.amount)),
                ),
            )
        }
    }

    private fun tick(current: GameFieldUiState, seconds: Int) {
        if (seconds <= 0 || latestSnapshot.phase != MatchPhase.ACTIVE) return
        val timers = current.timers.copy(
            elapsedSeconds = current.timers.elapsedSeconds + seconds,
            turnElapsedSeconds = current.timers.turnElapsedSeconds + seconds,
        )
        val totalLimit = parameters.totalTimeLimitSeconds
            .takeIf { it > 0 }
            ?.plus(timers.bonusTimeSeconds)
        if (totalLimit != null && timers.elapsedSeconds >= totalLimit) {
            latestSnapshot = engine.fail("Time is over")
            update(
                createState(
                    snapshot = latestSnapshot,
                    input = current.input,
                    manualMarks = current.manualMarks,
                    provenFacts = current.evidence.provenFacts,
                    timers = timers,
                    tools = current.tools,
                    counters = current.counters,
                    status = GameFieldStatus.TimedOut,
                ),
            )
        } else {
            update(current.copy(timers = timers))
        }
    }

    private fun update(state: GameFieldUiState) {
        _state.value = rebuildEvidence(state)
        persist()
    }

    private fun GameFieldInputState.withSlot(position: Int, symbol: Char?): GameFieldInputState =
        copy(slots = slots.toMutableList().apply { this[position] = symbol })

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
        val hypotheses = state.manualMarks.mapNotNull {
            when (it.type) {
                GameFieldManualMarkType.NO -> ManualHypothesis(
                    position = it.position,
                    symbol = it.symbol,
                    kind = HypothesisKind.IMPOSSIBLE,
                )

                GameFieldManualMarkType.YES -> ManualHypothesis(
                    position = it.position,
                    symbol = it.symbol,
                    kind = HypothesisKind.POSSIBLE,
                )

                GameFieldManualMarkType.MAYBE -> null
            }
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

    private fun effectiveFacts(state: GameFieldUiState): Set<ProvenFact> {
        val inferred = if (state.tools.autoExcludeEnabled) {
            state.evidence.deduction.provenFacts
        } else {
            emptySet()
        }
        return state.evidence.provenFacts + inferred
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
        turnTimeLimitSeconds = turnTimeLimitSeconds.takeIf { it > 0 },
    )

    private fun MatchSnapshot.toState(): GameFieldMatchState = GameFieldMatchState(
        phase = phase,
        attempts = attempts,
        attemptsLeft = attemptsLeft,
        debugSecret = debugSecret,
    )
}
