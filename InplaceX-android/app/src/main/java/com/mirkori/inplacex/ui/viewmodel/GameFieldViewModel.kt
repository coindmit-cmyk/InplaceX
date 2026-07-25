package com.mirkori.inplacex.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.mirkori.inplacex.core.match.MatchSnapshot
import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.platform.logging.AppLog
import com.mirkori.inplacex.ui.screens.game.MatchSessionSummary
import com.mirkori.inplacex.ui.screens.game.state.GameFieldBoostMode
import com.mirkori.inplacex.ui.screens.game.state.GameFieldEvent
import com.mirkori.inplacex.ui.screens.game.state.GameFieldHintMode
import com.mirkori.inplacex.ui.screens.game.state.GameFieldManualMarkType
import com.mirkori.inplacex.ui.screens.game.state.GameFieldMatchParameters
import com.mirkori.inplacex.ui.screens.game.state.GameFieldNotice
import com.mirkori.inplacex.ui.screens.game.state.GameFieldStateHolder
import com.mirkori.inplacex.ui.screens.game.state.GameFieldTool
import com.mirkori.inplacex.ui.screens.game.state.GameFieldUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GameFieldViewModel(
    savedStateHandle: SavedStateHandle,
    parameters: GameFieldMatchParameters = GameFieldMatchParameters(),
    initialSecret: String? = null,
) : ViewModel() {

    private val stateHolder = GameFieldStateHolder(savedStateHandle, parameters, initialSecret)

    private val _state = MutableStateFlow(stateHolder.currentSnapshot())
    val state: StateFlow<MatchSnapshot> = _state.asStateFlow()
    val uiState: StateFlow<GameFieldUiState> = stateHolder.state

    fun submit(guess: String) {
        stateHolder.submitRawGuess(guess)
        _state.value = stateHolder.currentSnapshot()
    }

    fun restart() {
        stateHolder.dispatch(GameFieldEvent.MatchRestarted)
        _state.value = stateHolder.currentSnapshot()
    }

    fun dispatch(event: GameFieldEvent): GameFieldUiState {
        stateHolder.dispatch(event)
        _state.value = stateHolder.currentSnapshot()
        return stateHolder.state.value
    }

    constructor() : this(SavedStateHandle())
}

internal data class GameFieldLifecycleCallbacks(
    val onMatchStarted: () -> Unit,
    val onMatchWon: () -> Unit,
    val onMatchFinished: (MatchSessionSummary) -> Unit,
    val onGuessResolved: (guess: String, score: Int, isWin: Boolean) -> Unit,
    val autoRestartOnWin: Boolean,
)

internal data class GameFieldHintInventory(
    val openPositionHints: Int,
    val checkDigitHints: Int,
    val checkPositionHints: Int,
    val infiniteHintsEnabled: Boolean,
    val consumeOpenPositionHint: () -> Boolean,
    val consumeCheckDigitHint: () -> Boolean,
    val consumeCheckPositionHint: () -> Boolean,
) {
    fun count(mode: GameFieldHintMode): Int = when (mode) {
        GameFieldHintMode.OPEN_POSITION -> openPositionHints
        GameFieldHintMode.CHECK_DIGIT -> checkDigitHints
        GameFieldHintMode.CHECK_POSITION -> checkPositionHints
    }

    fun consume(mode: GameFieldHintMode): Boolean = when (mode) {
        GameFieldHintMode.OPEN_POSITION -> consumeOpenPositionHint()
        GameFieldHintMode.CHECK_DIGIT -> consumeCheckDigitHint()
        GameFieldHintMode.CHECK_POSITION -> consumeCheckPositionHint()
    }
}

internal data class GameFieldRouteOverlay(
    val pendingRewardedHint: GameFieldHintMode? = null,
    val rewardedHintAllowance: GameFieldHintMode? = null,
)

/** Coordinates route-only inventory and lifecycle effects without owning match or deduction state. */
internal class GameFieldRouteController(
    private val viewModel: GameFieldViewModel,
) {
    private val _overlay = MutableStateFlow(GameFieldRouteOverlay())
    val overlay: StateFlow<GameFieldRouteOverlay> = _overlay.asStateFlow()
    private var completionReported = false

    fun dispatch(event: GameFieldEvent, callbacks: GameFieldLifecycleCallbacks): GameFieldUiState {
        val attemptsBefore = viewModel.uiState.value.match.attempts.size
        val state = viewModel.dispatch(event)
        state.match.attempts
            .takeIf { it.size > attemptsBefore }
            ?.lastOrNull()
            ?.let { callbacks.onGuessResolved(it.guess, it.score, it.isWin) }
        handleTerminalState(state, callbacks)
        return state
    }

    fun restart(callbacks: GameFieldLifecycleCallbacks) {
        completionReported = false
        _overlay.value = GameFieldRouteOverlay()
        viewModel.dispatch(GameFieldEvent.MatchRestarted)
        callbacks.onMatchStarted()
    }

    fun selectHint(mode: GameFieldHintMode, inventory: GameFieldHintInventory) {
        val selected = viewModel.uiState.value.tools.selectedHint
        val overlay = _overlay.value
        when {
            selected == mode -> viewModel.dispatch(GameFieldEvent.HintSelected(null))
            inventory.infiniteHintsEnabled ||
                inventory.count(mode) > 0 ||
                overlay.rewardedHintAllowance == mode ->
                viewModel.dispatch(GameFieldEvent.HintSelected(mode))

            else -> {
                _overlay.value = overlay.copy(pendingRewardedHint = mode)
                viewModel.dispatch(GameFieldEvent.NoticeChanged(GameFieldNotice.WatchAdForHint))
            }
        }
    }

    fun handleAnalysisCell(
        digit: Char,
        position: Int,
        inventory: GameFieldHintInventory,
        callbacks: GameFieldLifecycleCallbacks,
    ) {
        when (viewModel.uiState.value.tools.selectedHint) {
            GameFieldHintMode.CHECK_POSITION -> if (consumeHint(GameFieldHintMode.CHECK_POSITION, inventory)) {
                dispatch(GameFieldEvent.PositionHintRequested(digit.digitToInt(), position), callbacks)
            }

            GameFieldHintMode.OPEN_POSITION,
            GameFieldHintMode.CHECK_DIGIT,
            -> Unit

            null -> {
                val type = when (viewModel.uiState.value.tools.selectedTool) {
                    GameFieldTool.NO -> GameFieldManualMarkType.NO
                    GameFieldTool.MAYBE -> GameFieldManualMarkType.MAYBE
                    GameFieldTool.YES -> GameFieldManualMarkType.YES
                }
                dispatch(GameFieldEvent.ManualMarkChanged(position, digit, type), callbacks)
            }
        }
    }

    fun handleGuessSlot(
        position: Int,
        inventory: GameFieldHintInventory,
        callbacks: GameFieldLifecycleCallbacks,
    ) {
        if (
            viewModel.uiState.value.tools.selectedHint == GameFieldHintMode.OPEN_POSITION &&
            consumeHint(GameFieldHintMode.OPEN_POSITION, inventory)
        ) {
            dispatch(GameFieldEvent.OpenPositionHintRequested(position), callbacks)
        }
    }

    fun handleDigit(
        digit: Char,
        inventory: GameFieldHintInventory,
        callbacks: GameFieldLifecycleCallbacks,
    ) {
        if (viewModel.uiState.value.tools.selectedHint == GameFieldHintMode.CHECK_DIGIT) {
            if (consumeHint(GameFieldHintMode.CHECK_DIGIT, inventory)) {
                dispatch(GameFieldEvent.DigitHintRequested(digit.digitToInt()), callbacks)
            }
        } else {
            dispatch(GameFieldEvent.DigitEntered(digit), callbacks)
        }
    }

    fun requestBoost(
        stock: Int,
        amount: Int,
        consume: () -> Boolean,
        mode: GameFieldBoostMode,
        emptyNotice: GameFieldNotice,
        callbacks: GameFieldLifecycleCallbacks,
    ) {
        if (stock <= 0 || amount <= 0 || !consume()) {
            viewModel.dispatch(GameFieldEvent.NoticeChanged(emptyNotice))
        } else {
            dispatch(GameFieldEvent.BoostConsumed(mode, amount), callbacks)
        }
    }

    fun confirmRewardedHint(mode: GameFieldHintMode, granted: Boolean) {
        _overlay.value = if (granted) {
            GameFieldRouteOverlay(rewardedHintAllowance = mode)
        } else {
            GameFieldRouteOverlay()
        }
        if (granted) {
            viewModel.dispatch(GameFieldEvent.HintSelected(mode))
            viewModel.dispatch(GameFieldEvent.NoticeChanged(GameFieldNotice.BonusHintReady))
        } else {
            viewModel.dispatch(GameFieldEvent.NoticeChanged(GameFieldNotice.BonusNotGranted))
        }
    }

    fun dismissRewardedHint() {
        _overlay.value = _overlay.value.copy(pendingRewardedHint = null)
        viewModel.dispatch(GameFieldEvent.NoticeChanged(null))
    }

    fun visibleHintCount(mode: GameFieldHintMode, inventory: GameFieldHintInventory): Int =
        inventory.count(mode) + if (_overlay.value.rewardedHintAllowance == mode) 1 else 0

    private fun consumeHint(mode: GameFieldHintMode, inventory: GameFieldHintInventory): Boolean {
        if (inventory.infiniteHintsEnabled) return true
        if (_overlay.value.rewardedHintAllowance == mode) {
            _overlay.value = _overlay.value.copy(rewardedHintAllowance = null)
            return true
        }
        val consumed = inventory.count(mode) > 0 && inventory.consume(mode)
        if (!consumed) {
            viewModel.dispatch(GameFieldEvent.NoticeChanged(GameFieldNotice.NoHints))
        }
        return consumed
    }

    private fun handleTerminalState(
        state: GameFieldUiState,
        callbacks: GameFieldLifecycleCallbacks,
    ) {
        when (state.match.phase) {
            MatchPhase.WON -> {
                if (!completionReported) {
                    callbacks.onMatchWon()
                    reportFinished(state, won = true, callbacks)
                }
                if (callbacks.autoRestartOnWin) restart(callbacks)
            }

            MatchPhase.LOST -> reportFinished(state, won = false, callbacks)
            MatchPhase.ACTIVE,
            MatchPhase.NOT_STARTED,
            -> Unit
        }
    }

    private fun reportFinished(
        state: GameFieldUiState,
        won: Boolean,
        callbacks: GameFieldLifecycleCallbacks,
    ) {
        if (completionReported) return
        completionReported = true
        val counters = state.counters
        AppLog.info(
            tag = "GameFieldScreen",
            message = "match route finished",
            attributes = mapOf(
                "won" to won.toString(),
                "attempts" to state.match.attempts.size.toString(),
                "elapsedSeconds" to state.timers.elapsedSeconds.toString(),
            ),
        )
        callbacks.onMatchFinished(
            MatchSessionSummary(
                won = won,
                attemptsUsed = state.match.attempts.size,
                elapsedSeconds = state.timers.elapsedSeconds,
                hintUses = counters.openPositionHintUses +
                    counters.checkDigitHintUses +
                    counters.checkPositionHintUses,
                boostUses = counters.extraMovesBoostUses + counters.extraTimeBoostUses,
                openPositionHintUses = counters.openPositionHintUses,
                checkDigitHintUses = counters.checkDigitHintUses,
                checkPositionHintUses = counters.checkPositionHintUses,
                extraMovesBoostUses = counters.extraMovesBoostUses,
                extraTimeBoostUses = counters.extraTimeBoostUses,
            ),
        )
    }
}
