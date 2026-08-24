package com.mirkori.inplacex.ui.screens.game

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.SavedStateHandle
import com.mirkori.inplacex.data.local.HintStockType
import com.mirkori.inplacex.core.engine.GuessValidator
import com.mirkori.inplacex.platform.logging.AppLog
import com.mirkori.inplacex.ui.GameScreen
import com.mirkori.inplacex.ui.screens.game.presentation.GameAttemptList
import com.mirkori.inplacex.ui.screens.game.presentation.GamePresentationCallbacks
import com.mirkori.inplacex.ui.screens.game.state.GameFieldBoostMode
import com.mirkori.inplacex.ui.screens.game.state.GameFieldEvent
import com.mirkori.inplacex.ui.screens.game.state.GameFieldHintMode
import com.mirkori.inplacex.ui.screens.game.state.GameFieldMatchParameters
import com.mirkori.inplacex.ui.screens.game.state.GameFieldMode
import com.mirkori.inplacex.ui.screens.game.state.GameFieldNotice
import com.mirkori.inplacex.ui.screens.game.state.GameFieldOpponentProgressState
import com.mirkori.inplacex.ui.screens.game.state.GameFieldRouteUiState
import com.mirkori.inplacex.ui.screens.game.state.toGameConfig
import com.mirkori.inplacex.ui.viewmodel.GameFieldHintInventory
import com.mirkori.inplacex.ui.viewmodel.GameFieldLifecycleCallbacks
import com.mirkori.inplacex.ui.viewmodel.GameFieldRouteController
import com.mirkori.inplacex.ui.viewmodel.GameFieldViewModel
import com.mirkori.inplacex.ui.state.TransientOperationGate
import kotlinx.coroutines.delay

/**
 * Active game route: binds [GameFieldViewModel] to the stateless [GameScreen] and adapts only
 * navigation, inventory and match-lifecycle callbacks owned by the surrounding application shell.
 */
@Composable
fun GameFieldScreen(
    params: GameFieldParams,
    title: String,
    modeLabel: String = title,
    turnLabel: String? = null,
    secondaryStatusText: String? = null,
    opponentProgress: GameFieldOpponentProgressState? = null,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onDebugSecretChange: (String?) -> Unit = {},
    fixedSecret: String? = null,
    inputEnabled: Boolean = true,
    openPositionHints: Int = 0,
    checkDigitHints: Int = 0,
    checkPositionHints: Int = 0,
    autoModeAvailable: Boolean = true,
    infiniteHintsEnabled: Boolean = false,
    extraMovesBoosts: Int = 0,
    extraTimeBoosts: Int = 0,
    onConsumeOpenPositionHint: () -> Boolean = { false },
    onConsumeCheckDigitHint: () -> Boolean = { false },
    onConsumeCheckPositionHint: () -> Boolean = { false },
    onWatchRewardedHintAd: (HintStockType, (Boolean) -> Unit) -> Unit = { _, completed ->
        completed(false)
    },
    onConsumeExtraMovesBoost: () -> Boolean = { false },
    onConsumeExtraTimeBoost: () -> Boolean = { false },
    onMatchStarted: () -> Unit = {},
    onMatchWon: () -> Unit = {},
    onMatchFinished: (MatchSessionSummary) -> Unit = {},
    onGuessResolved: (guess: String, score: Int, isWin: Boolean) -> Unit = { _, _, _ -> },
    onMatchProgress: (attemptsUsed: Int, elapsedSeconds: Int) -> Unit = { _, _ -> },
    autoRestartOnWin: Boolean = false,
    extraMovesPerBoost: Int = 0,
    extraTimeSecondsPerBoost: Int = 0,
) {
    val matchParameters = remember(params, autoModeAvailable) {
        params.toMatchParameters(autoModeAvailable)
    }
    val acceptedFixedSecret = remember(matchParameters, fixedSecret) {
        fixedSecret?.takeIf { candidate ->
            GuessValidator.validate(candidate, matchParameters.toGameConfig())
        }
    }
    val viewModel = remember(matchParameters, acceptedFixedSecret) {
        GameFieldViewModel(SavedStateHandle(), matchParameters, acceptedFixedSecret)
    }
    val routeController = remember(viewModel) { GameFieldRouteController(viewModel) }
    val rewardedHintOperation = remember(routeController) { TransientOperationGate() }
    val holderState by viewModel.uiState.collectAsState()
    val overlay by routeController.overlay.collectAsState()
    val currentPendingRewardedHint by rememberUpdatedState(overlay.pendingRewardedHint)
    val hintInventory = GameFieldHintInventory(
        openPositionHints = openPositionHints,
        checkDigitHints = checkDigitHints,
        checkPositionHints = checkPositionHints,
        infiniteHintsEnabled = infiniteHintsEnabled,
        consumeOpenPositionHint = onConsumeOpenPositionHint,
        consumeCheckDigitHint = onConsumeCheckDigitHint,
        consumeCheckPositionHint = onConsumeCheckPositionHint,
    )
    val lifecycleCallbacks = GameFieldLifecycleCallbacks(
        onMatchStarted = onMatchStarted,
        onMatchWon = onMatchWon,
        onMatchFinished = onMatchFinished,
        onGuessResolved = onGuessResolved,
        autoRestartOnWin = autoRestartOnWin,
    )
    val currentLifecycleCallbacks by rememberUpdatedState(lifecycleCallbacks)
    val currentOnMatchProgress by rememberUpdatedState(onMatchProgress)

    DisposableEffect(routeController, rewardedHintOperation) {
        onDispose(rewardedHintOperation::cancel)
    }

    LaunchedEffect(viewModel) {
        if (fixedSecret != null && acceptedFixedSecret == null) {
            AppLog.warn(
                tag = "GameFieldScreen",
                message = "invalid fixed secret replaced with generated match secret",
                attributes = mapOf(
                    "configuredLength" to matchParameters.codeLength.toString(),
                    "receivedLength" to fixedSecret.length.toString(),
                ),
            )
        }
        AppLog.info(
            tag = "GameFieldScreen",
            message = "match route started",
            attributes = mapOf(
                "length" to params.lenSecret.toString(),
                "limitMoves" to params.limitMoves.toString(),
                "fixedSecret" to (fixedSecret != null).toString(),
            ),
        )
        currentLifecycleCallbacks.onMatchStarted()
    }
    LaunchedEffect(holderState.match.debugSecret) {
        onDebugSecretChange(holderState.match.debugSecret)
    }
    LaunchedEffect(holderState.match.attempts.size, holderState.timers.elapsedSeconds) {
        currentOnMatchProgress(
            holderState.match.attempts.size,
            holderState.timers.elapsedSeconds,
        )
    }
    LaunchedEffect(routeController, overlay.pendingRewardedHint) {
        while (true) {
            delay(1_000)
            if (overlay.pendingRewardedHint == null) {
                routeController.dispatch(GameFieldEvent.TimerTicked(), currentLifecycleCallbacks)
            }
        }
    }

    val routedState = holderState.copy(
        route = GameFieldRouteUiState(
            modeLabel = modeLabel.takeIf(String::isNotBlank),
            turnLabel = turnLabel,
            secondaryStatusText = secondaryStatusText,
            inputEnabled = inputEnabled,
            configuredMoveLimit = params.limitMoves.takeIf { it > 0 },
            movesUnlimited = params.limitMoves <= 0,
            openPositionHints = routeController.visibleHintCount(
                GameFieldHintMode.OPEN_POSITION,
                hintInventory,
            ),
            checkDigitHints = routeController.visibleHintCount(
                GameFieldHintMode.CHECK_DIGIT,
                hintInventory,
            ),
            checkPositionHints = routeController.visibleHintCount(
                GameFieldHintMode.CHECK_POSITION,
                hintInventory,
            ),
            infiniteHintsEnabled = infiniteHintsEnabled,
            extraMovesBoosts = extraMovesBoosts,
            extraTimeBoosts = extraTimeBoosts,
            extraMovesPerBoost = extraMovesPerBoost,
            extraTimeSecondsPerBoost = extraTimeSecondsPerBoost,
            pendingRewardedHint = overlay.pendingRewardedHint,
            rewardedHintInFlight = rewardedHintOperation.inProgress,
            opponentProgress = opponentProgress,
        ),
    )

    GameScreen(
        uiState = routedState,
        callbacks = GamePresentationCallbacks(
            onEvent = { event ->
                if (event == GameFieldEvent.MatchRestarted) {
                    routeController.restart(lifecycleCallbacks)
                } else {
                    routeController.dispatch(event, lifecycleCallbacks)
                }
            },
            onBack = onBack,
            onOpenSettings = onOpenSettings,
            onHintRequested = { routeController.selectHint(it, hintInventory) },
            onAnalysisCellPressed = { digit, position ->
                routeController.handleAnalysisCell(
                    digit,
                    position,
                    hintInventory,
                    lifecycleCallbacks,
                )
            },
            onGuessSlotPressed = {
                routeController.handleGuessSlot(it, hintInventory, lifecycleCallbacks)
            },
            onDigitPressed = {
                routeController.handleDigit(it, hintInventory, lifecycleCallbacks)
            },
            onExtraMovesBoostRequested = {
                routeController.requestBoost(
                    stock = extraMovesBoosts,
                    amount = extraMovesPerBoost,
                    consume = onConsumeExtraMovesBoost,
                    mode = GameFieldBoostMode.EXTRA_MOVES,
                    emptyNotice = GameFieldNotice.NoMoveBoosts,
                    callbacks = lifecycleCallbacks,
                )
            },
            onExtraTimeBoostRequested = {
                routeController.requestBoost(
                    stock = extraTimeBoosts,
                    amount = extraTimeSecondsPerBoost,
                    consume = onConsumeExtraTimeBoost,
                    mode = GameFieldBoostMode.EXTRA_TIME,
                    emptyNotice = GameFieldNotice.NoTimeBoosts,
                    callbacks = lifecycleCallbacks,
                )
            },
            onRewardedHintConfirmed = rewardedHintConfirmed@{ mode ->
                val operationId = rewardedHintOperation.start()
                    ?: return@rewardedHintConfirmed
                try {
                    onWatchRewardedHintAd(mode.toStockType()) rewardedHintCompleted@{ granted ->
                        if (!rewardedHintOperation.finish(operationId)) {
                            return@rewardedHintCompleted
                        }
                        if (currentPendingRewardedHint != mode) {
                            return@rewardedHintCompleted
                        }
                        routeController.confirmRewardedHint(
                            mode = mode,
                            granted = granted,
                        )
                    }
                } catch (error: Exception) {
                    AppLog.warn(
                        tag = "GameFieldScreen",
                        message = "rewarded hint operation could not start",
                        attributes = mapOf("errorClass" to error.javaClass.name),
                    )
                    if (
                        rewardedHintOperation.finish(operationId) &&
                        currentPendingRewardedHint == mode
                    ) {
                        routeController.confirmRewardedHint(mode = mode, granted = false)
                    }
                }
            },
            onRewardedHintDismissed = {
                if (!rewardedHintOperation.inProgress) {
                    routeController.dismissRewardedHint()
                }
            },
        ),
    )
}

@Composable
internal fun AttemptsModule(
    attempts: List<String>,
    modifier: Modifier = Modifier,
) {
    GameAttemptList(attempts = attempts, modifier = modifier)
}

internal fun GameFieldParams.toMatchParameters(autoModeAvailable: Boolean): GameFieldMatchParameters =
    GameFieldMatchParameters(
        mode = when (typeGame) {
            TypeGame.RaceMatch -> GameFieldMode.RACE
            TypeGame.DuelMatch -> GameFieldMode.DUEL
        },
        codeLength = lenSecret,
        attemptLimit = if (limitMoves > 0) limitMoves else 999,
        allowDuplicates = allowDuplicates,
        forbidAllSameDigitsGuess = forbidAllSameDigitsGuess,
        forbidAdjacentDuplicates = forbidAdjacentDuplicates,
        forbidTripleDuplicates = forbidTripleDuplicates,
        maxConsecutiveDuplicateDigits = maxConsecutiveDuplicateDigits,
        totalTimeLimitSeconds = timeAll,
        turnTimeLimitSeconds = timeMove,
        hintsEnabled = useHints,
        boostsEnabled = useBoosts,
        autoModeAvailable = autoModeAvailable,
    )

private fun GameFieldHintMode.toStockType(): HintStockType = when (this) {
    GameFieldHintMode.OPEN_POSITION -> HintStockType.OPEN_POSITION
    GameFieldHintMode.CHECK_DIGIT -> HintStockType.CHECK_DIGIT
    GameFieldHintMode.CHECK_POSITION -> HintStockType.CHECK_POSITION
}
