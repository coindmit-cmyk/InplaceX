package com.mirkori.inplacex.ui.screens.social

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.analysis.AcceptedAttemptEvidence
import com.mirkori.inplacex.core.analysis.EvidenceDeductionEngine
import com.mirkori.inplacex.core.analysis.EvidenceInput
import com.mirkori.inplacex.core.analysis.HypothesisKind
import com.mirkori.inplacex.core.analysis.ManualHypothesis
import com.mirkori.inplacex.core.engine.GuessValidator
import com.mirkori.inplacex.core.match.MatchAttempt
import com.mirkori.inplacex.core.match.MatchFeedback
import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.core.model.GameConfig
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.online.OnlineDuelSnapshotState
import com.mirkori.inplacex.platform.online.RemoteFriendPlayStyle
import com.mirkori.inplacex.ui.GameScreen
import com.mirkori.inplacex.ui.screens.game.presentation.GamePresentationCallbacks
import com.mirkori.inplacex.ui.screens.game.state.GameFieldCounters
import com.mirkori.inplacex.ui.screens.game.state.GameFieldEvent
import com.mirkori.inplacex.ui.screens.game.state.GameFieldEvidenceState
import com.mirkori.inplacex.ui.screens.game.state.GameFieldInputState
import com.mirkori.inplacex.ui.screens.game.state.GameFieldManualMark
import com.mirkori.inplacex.ui.screens.game.state.GameFieldManualMarkType
import com.mirkori.inplacex.ui.screens.game.state.GameFieldMatchParameters
import com.mirkori.inplacex.ui.screens.game.state.GameFieldMatchState
import com.mirkori.inplacex.ui.screens.game.state.GameFieldMode
import com.mirkori.inplacex.ui.screens.game.state.GameFieldRouteUiState
import com.mirkori.inplacex.ui.screens.game.state.GameFieldStatus
import com.mirkori.inplacex.ui.screens.game.state.GameFieldTimers
import com.mirkori.inplacex.ui.screens.game.state.GameFieldTool
import com.mirkori.inplacex.ui.screens.game.state.GameFieldToolsState
import kotlinx.coroutines.delay

/**
 * Adapts the authoritative online snapshot to the same game field used by local duel and race.
 * Secrets and scoring remain server-owned; only the current editor and manual table marks live here.
 */
@Composable
internal fun OnlineDuelGameField(
    snapshot: OnlineDuelSnapshotState,
    knownPlayerGuesses: Map<Int, String>,
    submitting: Boolean,
    onSubmitGuess: (String) -> Unit,
    onBack: () -> Unit,
) {
    val strings = LocalAppStrings.current
    var editor by rememberSaveable(
        snapshot.sessionId,
        stateSaver = onlineDuelEditorSaver(snapshot.codeLength),
    ) {
        mutableStateOf(OnlineDuelEditorState.empty(snapshot.codeLength))
    }
    val receivedAtEpochMs = remember(snapshot.revision, snapshot.serverTimeEpochMs) {
        System.currentTimeMillis()
    }
    var localNowEpochMs by remember(snapshot.sessionId) {
        mutableStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(snapshot.sessionId, snapshot.deadlineAtEpochMs) {
        while (snapshot.phase == "active") {
            localNowEpochMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val estimatedServerNowEpochMs =
        snapshot.serverTimeEpochMs + (localNowEpochMs - receivedAtEpochMs).coerceAtLeast(0L)
    val elapsedSeconds = snapshot.startedAtEpochMs?.let { startedAt ->
        ((estimatedServerNowEpochMs - startedAt).coerceAtLeast(0L) / 1_000L).toInt()
    } ?: 0
    val totalTimeLimitSeconds = if (
        snapshot.startedAtEpochMs != null &&
        snapshot.deadlineAtEpochMs != null
    ) {
        ((snapshot.deadlineAtEpochMs - snapshot.startedAtEpochMs) / 1_000L).toInt()
    } else {
        0
    }
    val playerAttemptCount = snapshot.attempts.count { it.actor == PLAYER_ACTOR }

    LaunchedEffect(playerAttemptCount) {
        if (playerAttemptCount > editor.acceptedAttemptCount) {
            editor = editor.afterAcceptedAttempt(playerAttemptCount)
        }
    }

    val uiState = buildOnlineDuelGameFieldState(
        snapshot = snapshot,
        knownPlayerGuesses = knownPlayerGuesses,
        editor = editor,
        inputEnabled = (
            snapshot.playStyle == RemoteFriendPlayStyle.RACE ||
                snapshot.currentTurn == PLAYER_ACTOR
            ) && !submitting,
        modeLabel = if (snapshot.playStyle == RemoteFriendPlayStyle.RACE) {
            strings.text("social.match.timed.field_title")
        } else {
            strings.text("social.match.turn_based.field_title")
        },
        turnLabel = when {
            snapshot.playStyle == RemoteFriendPlayStyle.RACE ->
                strings.text("social.match.timed.turn_status")
            snapshot.currentTurn == PLAYER_ACTOR -> strings.text("social.duel.your_turn")
            else -> strings.text("social.duel.opponent_turn")
        },
        elapsedSeconds = elapsedSeconds,
        totalTimeLimitSeconds = totalTimeLimitSeconds,
    )

    fun submitCurrentGuess() {
        val effectiveFacts = uiState.evidence.deduction.provenFacts
        val guess = uiState.input.guessOrNull(effectiveFacts)
        if (guess == null) {
            editor = editor.copy(status = GameFieldStatus.InputIncomplete)
            return
        }
        val reason = GuessValidator.validateOrReason(
            guess = guess.toCharArray(),
            config = GameConfig(
                codeLength = snapshot.codeLength,
                allowDuplicates = snapshot.allowDuplicates,
                attemptLimit = snapshot.attemptLimit ?: UnlimitedAttemptCapacity,
                maxConsecutiveDuplicateDigits = snapshot.maxConsecutiveDuplicateDigits,
            ),
        )
        if (reason != null) {
            editor = editor.copy(
                status = GameFieldStatus.EngineFeedback(
                    MatchFeedback.ValidationRejected(reason),
                ),
            )
            return
        }
        onSubmitGuess(guess)
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GameScreen(
            uiState = uiState,
            modifier = Modifier.weight(1f),
            callbacks = GamePresentationCallbacks(
                onEvent = { event ->
                    editor = when (event) {
                        GameFieldEvent.GuessSubmitted -> {
                            submitCurrentGuess()
                            editor
                        }

                        GameFieldEvent.MatchRestarted -> editor.clearInputKeepingYes()
                        else -> editor.reduce(event)
                    }
                },
                onAnalysisCellPressed = { digit, position ->
                    editor = editor.changeManualMark(digit, position)
                },
                onDigitPressed = { digit ->
                    editor = editor.reduce(GameFieldEvent.DigitEntered(digit))
                },
            ),
        )
    }
}

internal data class OnlineDuelEditorState(
    val input: GameFieldInputState,
    val manualMarks: List<GameFieldManualMark>,
    val selectedTool: GameFieldTool,
    val autoExcludeEnabled: Boolean,
    val status: GameFieldStatus,
    val acceptedAttemptCount: Int,
) {
    fun reduce(event: GameFieldEvent): OnlineDuelEditorState = when (event) {
        is GameFieldEvent.DigitEntered -> enterDigit(event.digit)
        GameFieldEvent.BackspacePressed -> backspace()
        is GameFieldEvent.ToolSelected -> copy(
            selectedTool = event.tool,
            status = GameFieldStatus.Idle,
        )

        is GameFieldEvent.AutoExcludeChanged -> copy(
            autoExcludeEnabled = event.enabled,
            status = GameFieldStatus.Idle,
        )

        is GameFieldEvent.NoticeChanged -> copy(status = GameFieldStatus.Idle)
        else -> this
    }

    fun changeManualMark(symbol: Char, position: Int): OnlineDuelEditorState {
        if (position !in input.slots.indices || symbol !in '0'..'9') return this
        val type = selectedTool.toManualMarkType()
        val existing = manualMarks.lastOrNull {
            it.position == position && it.symbol == symbol
        }
        var retained = manualMarks.filterNot {
            it.position == position && it.symbol == symbol
        }
        if (type == GameFieldManualMarkType.YES) {
            retained = retained.filterNot {
                it.position == position && it.type == GameFieldManualMarkType.YES
            }
        }
        val nextType = type.takeUnless { existing?.type == it }
        val nextMarks = nextType?.let {
            retained + GameFieldManualMark(position, symbol, it)
        } ?: retained
        val slots = input.slots.toMutableList()
        when {
            nextType == GameFieldManualMarkType.YES -> slots[position] = symbol
            existing?.type == GameFieldManualMarkType.YES && slots[position] == symbol ->
                slots[position] = null
        }
        return copy(
            input = GameFieldInputState(slots),
            manualMarks = nextMarks,
            status = GameFieldStatus.Idle,
        )
    }

    fun afterAcceptedAttempt(count: Int): OnlineDuelEditorState =
        clearInputKeepingYes().copy(
            acceptedAttemptCount = count,
            status = GameFieldStatus.Idle,
        )

    fun clearInputKeepingYes(): OnlineDuelEditorState {
        val slots = MutableList<Char?>(input.slots.size) { null }
        manualMarks
            .filter { it.type == GameFieldManualMarkType.YES }
            .forEach { mark -> slots[mark.position] = mark.symbol }
        return copy(input = GameFieldInputState(slots), status = GameFieldStatus.Idle)
    }

    private fun enterDigit(digit: Char): OnlineDuelEditorState {
        if (digit !in '0'..'9') return this
        val position = input.slots.indexOfFirst { it == null }
        if (position < 0) return this
        val slots = input.slots.toMutableList().apply { this[position] = digit }
        return copy(input = GameFieldInputState(slots), status = GameFieldStatus.Idle)
    }

    private fun backspace(): OnlineDuelEditorState {
        val fixedPositions = manualMarks
            .filter { it.type == GameFieldManualMarkType.YES }
            .mapTo(mutableSetOf(), GameFieldManualMark::position)
        val position = input.slots.indices.reversed().firstOrNull {
            it !in fixedPositions && input.slots[it] != null
        } ?: return this
        val slots = input.slots.toMutableList().apply { this[position] = null }
        return copy(input = GameFieldInputState(slots), status = GameFieldStatus.Idle)
    }

    companion object {
        fun empty(codeLength: Int): OnlineDuelEditorState = OnlineDuelEditorState(
            input = GameFieldInputState.empty(codeLength),
            manualMarks = emptyList(),
            selectedTool = GameFieldTool.NO,
            autoExcludeEnabled = true,
            status = GameFieldStatus.Idle,
            acceptedAttemptCount = 0,
        )
    }
}

internal fun buildOnlineDuelGameFieldState(
    snapshot: OnlineDuelSnapshotState,
    knownPlayerGuesses: Map<Int, String>,
    editor: OnlineDuelEditorState,
    inputEnabled: Boolean,
    modeLabel: String,
    turnLabel: String,
    elapsedSeconds: Int = 0,
    totalTimeLimitSeconds: Int = 0,
): com.mirkori.inplacex.ui.screens.game.state.GameFieldUiState {
    val playerAttempts = snapshot.attempts
        .filter { it.actor == PLAYER_ACTOR }
        .map { attempt ->
            MatchAttempt(
                guess = knownPlayerGuesses[attempt.number] ?: UNKNOWN_GUESS.repeat(snapshot.codeLength),
                score = attempt.exactMatches,
                number = attempt.number,
                isWin = attempt.exactMatches == snapshot.codeLength,
            )
        }
    val acceptedEvidence = playerAttempts
        .filter { it.guess.all(Char::isDigit) }
        .map { AcceptedAttemptEvidence(it.guess, it.score) }
    val hypotheses = editor.manualMarks.mapNotNull { mark ->
        when (mark.type) {
            GameFieldManualMarkType.NO -> ManualHypothesis(
                mark.position,
                mark.symbol,
                HypothesisKind.IMPOSSIBLE,
            )

            GameFieldManualMarkType.YES -> ManualHypothesis(
                mark.position,
                mark.symbol,
                HypothesisKind.POSSIBLE,
            )

            GameFieldManualMarkType.MAYBE -> null
        }
    }
    val deduction = EvidenceDeductionEngine(snapshot.codeLength).infer(
        EvidenceInput(
            hypotheses = hypotheses,
            acceptedAttempts = acceptedEvidence,
        ),
    )
    return com.mirkori.inplacex.ui.screens.game.state.GameFieldUiState(
        parameters = GameFieldMatchParameters(
            mode = if (snapshot.playStyle == RemoteFriendPlayStyle.RACE) {
                GameFieldMode.RACE
            } else {
                GameFieldMode.DUEL
            },
            codeLength = snapshot.codeLength,
            attemptLimit = snapshot.attemptLimit ?: UnlimitedAttemptCapacity,
            allowDuplicates = snapshot.allowDuplicates,
            maxConsecutiveDuplicateDigits = snapshot.maxConsecutiveDuplicateDigits,
            totalTimeLimitSeconds = totalTimeLimitSeconds,
            hintsEnabled = false,
            boostsEnabled = false,
            autoModeAvailable = true,
        ),
        match = GameFieldMatchState(
            phase = MatchPhase.ACTIVE,
            attempts = playerAttempts,
            attemptsLeft = snapshot.attemptLimit
                ?.let { (it - playerAttempts.size).coerceAtLeast(0) }
                ?: UnlimitedAttemptCapacity,
            debugSecret = "",
        ),
        input = editor.input,
        evidence = GameFieldEvidenceState(
            acceptedAttempts = acceptedEvidence,
            hypotheses = hypotheses,
            provenFacts = emptySet(),
            deduction = deduction,
        ),
        manualMarks = editor.manualMarks,
        timers = GameFieldTimers(elapsedSeconds = elapsedSeconds),
        tools = GameFieldToolsState(
            selectedTool = editor.selectedTool,
            autoExcludeEnabled = editor.autoExcludeEnabled,
        ),
        counters = GameFieldCounters(),
        status = editor.status,
        route = GameFieldRouteUiState(
            modeLabel = modeLabel,
            turnLabel = turnLabel,
            secondaryStatusText = null,
            inputEnabled = inputEnabled,
            configuredMoveLimit = snapshot.attemptLimit,
            movesUnlimited = snapshot.attemptLimit == null,
        ),
    )
}

private fun onlineDuelEditorSaver(codeLength: Int): Saver<OnlineDuelEditorState, Any> =
    listSaver(
        save = { editor ->
            listOf(
                editor.input.slots.joinToString("") { it?.toString() ?: EMPTY_SLOT },
                editor.manualMarks.joinToString(MARK_SEPARATOR) {
                    "${it.position},${it.symbol},${it.type.name}"
                },
                editor.selectedTool.name,
                editor.autoExcludeEnabled.toString(),
                editor.acceptedAttemptCount.toString(),
            )
        },
        restore = { values ->
            val slots = values[0].toString()
                .padEnd(codeLength, EMPTY_SLOT.single())
                .take(codeLength)
                .map { it.takeUnless(EMPTY_SLOT.single()::equals) }
            val marks = values[1].toString()
                .split(MARK_SEPARATOR)
                .mapNotNull { encoded ->
                    val parts = encoded.split(',')
                    if (parts.size != 3) return@mapNotNull null
                    val position = parts[0].toIntOrNull() ?: return@mapNotNull null
                    val symbol = parts[1].singleOrNull() ?: return@mapNotNull null
                    val type = runCatching {
                        GameFieldManualMarkType.valueOf(parts[2])
                    }.getOrNull() ?: return@mapNotNull null
                    GameFieldManualMark(position, symbol, type)
                }
            OnlineDuelEditorState(
                input = GameFieldInputState(slots),
                manualMarks = marks,
                selectedTool = runCatching {
                    GameFieldTool.valueOf(values[2].toString())
                }.getOrDefault(GameFieldTool.NO),
                autoExcludeEnabled = values[3].toString().toBooleanStrictOrNull() ?: true,
                status = GameFieldStatus.Idle,
                acceptedAttemptCount = values[4].toString().toIntOrNull() ?: 0,
            )
        },
    )

private fun GameFieldTool.toManualMarkType(): GameFieldManualMarkType = when (this) {
    GameFieldTool.NO -> GameFieldManualMarkType.NO
    GameFieldTool.MAYBE -> GameFieldManualMarkType.MAYBE
    GameFieldTool.YES -> GameFieldManualMarkType.YES
}

private const val PLAYER_ACTOR = "player"
private const val UNKNOWN_GUESS = "·"
private const val EMPTY_SLOT = "_"
private const val MARK_SEPARATOR = ";"
private const val UnlimitedAttemptCapacity = 1_000
