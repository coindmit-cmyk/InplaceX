package com.mirkori.inplacex.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirkori.inplacex.core.bot.BotSolver
import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.core.bot.BotProfiles
import com.mirkori.inplacex.core.engine.GuessValidator
import com.mirkori.inplacex.core.engine.ScoreCalculator
import com.mirkori.inplacex.core.engine.SecretGenerator
import com.mirkori.inplacex.core.match.PreMatchPhase
import com.mirkori.inplacex.core.model.GameConfig
import com.mirkori.inplacex.core.model.GameModeDefinition
import com.mirkori.inplacex.platform.config.AppConfigCatalog
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.online.RemoteFriendPlayStyle
import com.mirkori.inplacex.platform.logging.AppLog
import com.mirkori.inplacex.ui.screens.game.GameFieldParams
import com.mirkori.inplacex.ui.screens.game.GameFieldScreen
import com.mirkori.inplacex.ui.screens.game.MatchSessionSummary
import com.mirkori.inplacex.ui.screens.game.TypeGame
import com.mirkori.inplacex.ui.screens.game.state.GameFieldOpponentAttempt
import com.mirkori.inplacex.ui.screens.game.state.GameFieldOpponentProgressState
import com.mirkori.inplacex.ui.screens.shared.SceneActionTile
import com.mirkori.inplacex.ui.theme.FinalUiColors
import com.mirkori.inplacex.ui.theme.InplaceXColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class DuelTurnOwner {
    PLAYER,
    BOT,
}

@Composable
fun HomeRootScreen(
    screenState: HomeScreenState,
    onScreenStateChange: (HomeScreenState) -> Unit,
    requestExitGame: Boolean = false,
    onExitGameConsumed: () -> Unit = {},
    onInGameChange: (Boolean) -> Unit = {},
    onDebugSecretChange: (String?) -> Unit = {},
    openPositionHints: Int = 0,
    checkDigitHints: Int = 0,
    checkPositionHints: Int = 0,
    autoModeAvailable: Boolean = true,
    infiniteHintsEnabled: Boolean = false,
    onConsumeOpenPositionHint: () -> Boolean = { false },
    onConsumeCheckDigitHint: () -> Boolean = { false },
    onConsumeCheckPositionHint: () -> Boolean = { false },
    onWatchRewardedHintAd: (
        com.mirkori.inplacex.data.local.HintStockType,
        (Boolean) -> Unit,
    ) -> Unit = { _, completed -> completed(false) },
    onMatchStarted: () -> Unit = {},
    onRecordPveResult: (Boolean) -> Unit = {},
    onRecordPvpResult: (Boolean) -> Unit = {},
    onOpenCompany: () -> Unit = {},
    onOpenOnlineMatch: (RemoteFriendPlayStyle, Int) -> Unit = { _, _ -> },
    onlineAvailable: Boolean = false,
) {
    val pveMode = AppConfigCatalog.gameModes.first { it.id == "pve_race" }
    val pvpMode = AppConfigCatalog.gameModes.first { it.id == "pvp_bot_duel" }
    val strings = LocalAppStrings.current
    var showExitDialog by rememberSaveable { mutableStateOf(false) }
    var showPreMatchDialog by rememberSaveable { mutableStateOf(false) }
    var showDuelResultDialog by rememberSaveable { mutableStateOf(false) }
    var duelResultText by rememberSaveable { mutableStateOf("") }
    var raceResultWon by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var raceResultAttempts by rememberSaveable { mutableIntStateOf(0) }
    var raceResultElapsedSeconds by rememberSaveable { mutableIntStateOf(0) }
    var pveSessionSeed by rememberSaveable { mutableIntStateOf(1) }
    var preMatchPhase by rememberSaveable { mutableStateOf(PreMatchPhase.SECRET_SELECTION) }
    var preMatchSecretInput by rememberSaveable { mutableStateOf("") }
    var preMatchTimeoutLeft by rememberSaveable { mutableIntStateOf(60) }
    var botWaitLeft by rememberSaveable { mutableIntStateOf(5) }
    var preMatchError by rememberSaveable { mutableStateOf<String?>(null) }
    var playerSecretForDuel by rememberSaveable { mutableStateOf("") }
    var botSecretForDuel by rememberSaveable { mutableStateOf("") }
    var duelTurnOwner by rememberSaveable { mutableStateOf(DuelTurnOwner.PLAYER) }
    var duelTurnError by rememberSaveable { mutableStateOf<String?>(null) }
    var duelSessionSeed by rememberSaveable { mutableIntStateOf(1) }
    var selectedRaceCodeLength by rememberSaveable {
        mutableIntStateOf(selectHomeCodeLength(pveMode.config.codeLength))
    }
    var selectedDuelCodeLength by rememberSaveable {
        mutableIntStateOf(selectHomeCodeLength(pvpMode.config.codeLength))
    }
    val configuredPveMode = pveMode.withCodeLength(selectedRaceCodeLength)
    val configuredPvpMode = pvpMode.withCodeLength(selectedDuelCodeLength)

    LaunchedEffect(screenState) {
        onInGameChange(screenState != HomeScreenState.ROOT)
        if (screenState == HomeScreenState.ROOT) {
            onDebugSecretChange(null)
        }
    }

    LaunchedEffect(showPreMatchDialog, preMatchPhase) {
        if (!showPreMatchDialog) return@LaunchedEffect

        when (preMatchPhase) {
            PreMatchPhase.SECRET_SELECTION -> {
                while (showPreMatchDialog && preMatchPhase == PreMatchPhase.SECRET_SELECTION && preMatchTimeoutLeft > 0) {
                    delay(1000)
                    preMatchTimeoutLeft -= 1
                }
                if (showPreMatchDialog && preMatchPhase == PreMatchPhase.SECRET_SELECTION && preMatchTimeoutLeft <= 0) {
                    preMatchPhase = PreMatchPhase.CANCELLED_TIMEOUT
                }
            }

            PreMatchPhase.WAITING_OPPONENT_SECRET -> {
                while (showPreMatchDialog && preMatchPhase == PreMatchPhase.WAITING_OPPONENT_SECRET && botWaitLeft > 0) {
                    delay(1000)
                    botWaitLeft -= 1
                }
                if (showPreMatchDialog && preMatchPhase == PreMatchPhase.WAITING_OPPONENT_SECRET && botWaitLeft <= 0) {
                    playerSecretForDuel = preMatchSecretInput
                    botSecretForDuel = SecretGenerator.generate(
                        localBotDuelConfig(
                            mode = configuredPvpMode,
                            seed = duelSessionSeed.toLong() * 37L,
                        ),
                    )
                    duelTurnOwner = DuelTurnOwner.PLAYER
                    duelTurnError = null
                    duelSessionSeed += 1
                    preMatchPhase = PreMatchPhase.READY_TO_START
                    showPreMatchDialog = false
                    onScreenStateChange(HomeScreenState.PVP_GAME)
                }
            }

            else -> Unit
        }
    }

    LaunchedEffect(requestExitGame, screenState) {
        if (!requestExitGame) return@LaunchedEffect

        when (screenState) {
            HomeScreenState.ROOT -> Unit
            HomeScreenState.RACE_MODES,
            HomeScreenState.PVP_MODES -> onScreenStateChange(HomeScreenState.ROOT)
            HomeScreenState.PVE_GAME,
            HomeScreenState.PVP_GAME,
            -> showExitDialog = true
        }
        onExitGameConsumed()
    }

    fun closeRaceResultToHome() {
        raceResultWon = null
        onScreenStateChange(HomeScreenState.ROOT)
        onDebugSecretChange(null)
    }

    BackHandler(
        enabled = showExitDialog ||
            showPreMatchDialog ||
            showDuelResultDialog ||
            raceResultWon != null,
    ) {
        when {
            showExitDialog -> showExitDialog = false
            raceResultWon != null -> closeRaceResultToHome()
            showDuelResultDialog -> showDuelResultDialog = false
            showPreMatchDialog -> {
                showPreMatchDialog = false
                preMatchPhase = PreMatchPhase.SECRET_SELECTION
                preMatchTimeoutLeft = pvpMode.preMatchConfig?.secretSelectionTimeoutSeconds ?: 60
                botWaitLeft = pvpMode.preMatchConfig?.devBotSecretDelaySeconds ?: 5
                preMatchError = null
                preMatchSecretInput = ""
            }
        }
    }
    BackHandler(
        enabled = screenState in setOf(HomeScreenState.RACE_MODES, HomeScreenState.PVP_MODES) &&
            !showExitDialog &&
            !showPreMatchDialog &&
            !showDuelResultDialog &&
            raceResultWon == null,
    ) {
        onScreenStateChange(HomeScreenState.ROOT)
    }
    BackHandler(
        enabled = screenState in setOf(HomeScreenState.PVE_GAME, HomeScreenState.PVP_GAME) &&
            !showExitDialog &&
            !showPreMatchDialog &&
            !showDuelResultDialog &&
            raceResultWon == null
    ) {
        showExitDialog = true
    }

    when (screenState) {
        HomeScreenState.ROOT -> {
            HomeSelectionScreen(
                pveMode = pveMode,
                pvpMode = pvpMode,
                onOpenPve = { onScreenStateChange(HomeScreenState.RACE_MODES) },
                onOpenPvp = { onScreenStateChange(HomeScreenState.PVP_MODES) },
                onOpenCompany = onOpenCompany,
            )
        }

        HomeScreenState.PVP_MODES -> {
            PvpModesScreen(
                codeLength = selectedDuelCodeLength,
                onCodeLengthChange = { selectedDuelCodeLength = it },
                onPlayWithBot = {
                    preMatchPhase = PreMatchPhase.SECRET_SELECTION
                    preMatchTimeoutLeft = pvpMode.preMatchConfig?.secretSelectionTimeoutSeconds ?: 60
                    botWaitLeft = pvpMode.preMatchConfig?.devBotSecretDelaySeconds ?: 5
                    preMatchSecretInput = ""
                    preMatchError = null
                    showPreMatchDialog = true
                },
                onPlayOnline = {
                    onOpenOnlineMatch(RemoteFriendPlayStyle.TURN_BASED, selectedDuelCodeLength)
                },
                onlineAvailable = onlineAvailable,
                onBack = { onScreenStateChange(HomeScreenState.ROOT) },
                modeAccentColor = InplaceXColors.ToyPurple,
            )
        }

        HomeScreenState.RACE_MODES -> {
            PvpModesScreen(
                codeLength = selectedRaceCodeLength,
                onCodeLengthChange = { selectedRaceCodeLength = it },
                onPlayWithBot = { onScreenStateChange(HomeScreenState.PVE_GAME) },
                onPlayOnline = {
                    onOpenOnlineMatch(RemoteFriendPlayStyle.RACE, selectedRaceCodeLength)
                },
                onlineAvailable = onlineAvailable,
                onBack = { onScreenStateChange(HomeScreenState.ROOT) },
                modeAccentColor = InplaceXColors.ToyOrange,
            )
        }

        HomeScreenState.PVE_GAME -> {
            key(pveSessionSeed, selectedRaceCodeLength) {
                val raceSecret = remember(configuredPveMode, pveSessionSeed) {
                    SecretGenerator.generate(
                        localBotRaceConfig(
                            mode = configuredPveMode,
                            seed = pveSessionSeed.toLong() * 41L,
                        ),
                    )
                }
                val raceBotSolver = remember(configuredPveMode, pveSessionSeed) {
                    BotSolver(
                        config = localBotRaceConfig(configuredPveMode),
                        difficulty = configuredPveMode.botDifficulty
                            ?: com.mirkori.inplacex.core.bot.BotDifficulty.MEDIUM,
                        seed = pveSessionSeed.toLong() * 43L,
                    )
                }
                var opponentAttempts by remember {
                    mutableStateOf<List<GameFieldOpponentAttempt>>(emptyList())
                }
                var opponentThinking by remember { mutableStateOf(false) }
                var opponentFailed by remember { mutableStateOf(false) }
                var raceStarted by remember { mutableStateOf(false) }
                var playerAttemptCount by remember { mutableIntStateOf(0) }
                var playerElapsedSeconds by remember { mutableIntStateOf(0) }
                val opponentCompleted = opponentAttempts.lastOrNull()?.exactMatches ==
                    configuredPveMode.config.codeLength

                LaunchedEffect(raceStarted, raceBotSolver, raceSecret) {
                    if (!raceStarted) return@LaunchedEffect
                    var consecutiveFailures = 0
                    try {
                        while (
                            raceResultWon == null &&
                            opponentAttempts.lastOrNull()?.exactMatches != configuredPveMode.config.codeLength &&
                            !opponentFailed
                        ) {
                            opponentThinking = false
                            delay(
                                raceBotReactionDelayMillis(
                                    configuredPveMode.botDifficulty ?: BotDifficulty.MEDIUM,
                                ),
                            )
                            if (raceResultWon != null) break

                            val botTurn = try {
                                opponentThinking = true
                                withContext(Dispatchers.Default) {
                                    resolveDuelBotTurn(
                                        playerSecret = raceSecret,
                                        codeLength = configuredPveMode.config.codeLength,
                                        nextGuess = { raceBotSolver.nextTurn().guess },
                                        registerFeedback = raceBotSolver::registerFeedback,
                                        confirmedPositions = raceBotSolver::confirmedPositionsCount,
                                    )
                                }
                            } finally {
                                opponentThinking = false
                            }
                            if (raceResultWon != null) break
                            when (botTurn) {
                                is DuelBotTurnResult.Completed -> {
                                    consecutiveFailures = 0
                                    opponentAttempts = opponentAttempts + GameFieldOpponentAttempt(
                                        number = opponentAttempts.size + 1,
                                        exactMatches = botTurn.score,
                                    )
                                    if (
                                        isRaceBotVictory(
                                            score = botTurn.score,
                                            codeLength = configuredPveMode.config.codeLength,
                                        )
                                    ) {
                                        onRecordPveResult(false)
                                        raceResultWon = false
                                        raceResultAttempts = playerAttemptCount
                                        raceResultElapsedSeconds = playerElapsedSeconds
                                    }
                                }
                                is DuelBotTurnResult.Failed -> {
                                    consecutiveFailures += 1
                                    AppLog.error(
                                        tag = "HomeRootScreen",
                                        message = "race bot progress turn failed",
                                        attributes = mapOf(
                                            "codeLength" to configuredPveMode.config.codeLength.toString(),
                                            "failureStage" to botTurn.stage.name,
                                        ),
                                        throwable = botTurn.cause,
                                    )
                                    if (consecutiveFailures >= MAX_RACE_BOT_CONSECUTIVE_FAILURES) {
                                        opponentFailed = true
                                    }
                                }
                            }
                        }
                    } finally {
                        opponentThinking = false
                    }
                }

                GameFieldScreen(
                    title = "",
                    params = configuredPveMode.toFieldParams(TypeGame.RaceMatch),
                    fixedSecret = raceSecret,
                    opponentProgress = GameFieldOpponentProgressState(
                        attempts = opponentAttempts,
                        isThinking = opponentThinking,
                        completed = opponentCompleted,
                        failed = opponentFailed,
                    ),
                    onBack = { showExitDialog = true },
                    onDebugSecretChange = onDebugSecretChange,
                    openPositionHints = openPositionHints,
                    checkDigitHints = checkDigitHints,
                    checkPositionHints = checkPositionHints,
                    autoModeAvailable = autoModeAvailable,
                    infiniteHintsEnabled = infiniteHintsEnabled,
                    onConsumeOpenPositionHint = onConsumeOpenPositionHint,
                    onConsumeCheckDigitHint = onConsumeCheckDigitHint,
                    onConsumeCheckPositionHint = onConsumeCheckPositionHint,
                    onWatchRewardedHintAd = onWatchRewardedHintAd,
                    onMatchStarted = {
                        if (raceResultWon == null) {
                            raceStarted = true
                            onMatchStarted()
                        }
                    },
                    onMatchProgress = { attemptsUsed, elapsedSeconds ->
                        playerAttemptCount = attemptsUsed
                        playerElapsedSeconds = elapsedSeconds
                    },
                    onMatchFinished = { summary ->
                        if (raceResultWon == null) {
                            onRecordPveResult(summary.won)
                            raceResultWon = summary.won
                            raceResultAttempts = summary.attemptsUsed
                            raceResultElapsedSeconds = summary.elapsedSeconds
                        }
                    },
                    autoRestartOnWin = false,
                )
            }
        }

        HomeScreenState.PVP_GAME -> {
            val botSolver = remember(duelSessionSeed, playerSecretForDuel, selectedDuelCodeLength) {
                BotSolver(
                    config = localBotDuelConfig(configuredPvpMode),
                    difficulty = configuredPvpMode.botDifficulty
                        ?: com.mirkori.inplacex.core.bot.BotDifficulty.MEDIUM,
                    seed = duelSessionSeed.toLong(),
                )
            }

            LaunchedEffect(screenState, duelTurnOwner, duelSessionSeed) {
                if (screenState != HomeScreenState.PVP_GAME || duelTurnOwner != DuelTurnOwner.BOT) return@LaunchedEffect
                delay(2200)

                val botTurn = withContext(Dispatchers.Default) {
                    resolveDuelBotTurn(
                        playerSecret = playerSecretForDuel,
                        codeLength = configuredPvpMode.config.codeLength,
                        nextGuess = { botSolver.nextTurn().guess },
                        registerFeedback = botSolver::registerFeedback,
                        confirmedPositions = botSolver::confirmedPositionsCount,
                    )
                }

                when (botTurn) {
                    is DuelBotTurnResult.Completed -> {
                        duelTurnError = null

                        if (botTurn.score == configuredPvpMode.config.codeLength) {
                            onRecordPvpResult(false)
                            duelResultText = strings.homeDuelResultBotWin(botTurn.score)
                            showDuelResultDialog = true
                            onScreenStateChange(HomeScreenState.ROOT)
                            onDebugSecretChange(null)
                        } else {
                            duelTurnOwner = DuelTurnOwner.PLAYER
                        }
                    }

                    is DuelBotTurnResult.Failed -> {
                        AppLog.error(
                            tag = "HomeRootScreen",
                            message = "duel bot turn failed",
                            attributes = mapOf(
                                "codeLength" to configuredPvpMode.config.codeLength.toString(),
                                "inputLength" to playerSecretForDuel.length.toString(),
                                "failureStage" to botTurn.stage.name,
                            ),
                            throwable = botTurn.cause,
                        )
                        duelTurnError = strings.text("home.duel.status.bot_turn_failed")
                        duelTurnOwner = DuelTurnOwner.PLAYER
                    }
                }
            }

            GameFieldScreen(
                title = "",
                modeLabel = "",
                turnLabel = if (duelTurnOwner == DuelTurnOwner.PLAYER) {
                    strings.text("home.duel.turn.player")
                } else {
                    strings.text("home.duel.turn.opponent")
                },
                secondaryStatusText = duelTurnError,
                params = configuredPvpMode.toFieldParams(TypeGame.DuelMatch),
                onBack = { showExitDialog = true },
                onDebugSecretChange = onDebugSecretChange,
                fixedSecret = botSecretForDuel,
                inputEnabled = duelTurnOwner == DuelTurnOwner.PLAYER,
                openPositionHints = openPositionHints,
                checkDigitHints = checkDigitHints,
                checkPositionHints = checkPositionHints,
                autoModeAvailable = autoModeAvailable,
                infiniteHintsEnabled = infiniteHintsEnabled,
                onConsumeOpenPositionHint = onConsumeOpenPositionHint,
                onConsumeCheckDigitHint = onConsumeCheckDigitHint,
                onConsumeCheckPositionHint = onConsumeCheckPositionHint,
                onWatchRewardedHintAd = onWatchRewardedHintAd,
                onMatchStarted = onMatchStarted,
                onGuessResolved = { _, _, isWin ->
                    if (duelTurnOwner != DuelTurnOwner.PLAYER) return@GameFieldScreen
                    if (isWin) {
                        onRecordPvpResult(true)
                        duelResultText = strings.text("home.duel.result.player_win")
                        showDuelResultDialog = true
                        onScreenStateChange(HomeScreenState.ROOT)
                        onDebugSecretChange(null)
                    } else {
                        duelTurnOwner = DuelTurnOwner.BOT
                    }
                },
                onMatchFinished = { _: MatchSessionSummary -> }
            )
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(strings.text("home.dialog.exit.title")) },
            text = { Text(strings.text("home.dialog.exit.text")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        onScreenStateChange(HomeScreenState.ROOT)
                        onDebugSecretChange(null)
                    }
                ) {
                    Text(strings.text("home.dialog.exit.confirm"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text(strings.text("home.dialog.exit.stay"))
                }
            }
        )
    }

    if (showDuelResultDialog) {
        AlertDialog(
            onDismissRequest = { showDuelResultDialog = false },
            title = { Text(strings.text("home.dialog.result.title")) },
            text = { Text(duelResultText) },
            confirmButton = {
                TextButton(onClick = { showDuelResultDialog = false }) {
                    Text(strings.text("home.dialog.result.ok"))
                }
            }
        )
    }

    raceResultWon?.let { won ->
        RaceResultDialog(
            won = won,
            attemptsUsed = raceResultAttempts,
            attemptLimit = configuredPveMode.moveLimit,
            elapsedSeconds = raceResultElapsedSeconds,
            onRetry = {
                raceResultWon = null
                pveSessionSeed += 1
            },
            onHome = ::closeRaceResultToHome,
        )
    }

    if (showPreMatchDialog) {
        AlertDialog(
            onDismissRequest = {
                showPreMatchDialog = false
                preMatchPhase = PreMatchPhase.SECRET_SELECTION
            },
            title = { Text(strings.text("home.dialog.setup.title")) },
            text = {
                when (preMatchPhase) {
                    PreMatchPhase.SECRET_SELECTION -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(strings.text("home.dialog.setup.secret_prompt"))
                            OutlinedTextField(
                                value = preMatchSecretInput,
                                onValueChange = { value ->
                                    preMatchSecretInput = value.filter(Char::isDigit)
                                        .take(configuredPvpMode.config.codeLength)
                                    preMatchError = null
                                },
                                singleLine = true,
                                label = {
                                    Text(strings.homeSecretLabel(configuredPvpMode.config.codeLength))
                                }
                            )
                            Text(strings.homeTimeLeft(preMatchTimeoutLeft))
                            preMatchError?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
                        }
                    }

                    PreMatchPhase.WAITING_OPPONENT_SECRET -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(strings.text("home.dialog.setup.wait_opponent"))
                            Text(strings.homeBotReady(botWaitLeft))
                        }
                    }

                    PreMatchPhase.CANCELLED_TIMEOUT -> {
                        Text(strings.text("home.dialog.setup.timeout"))
                    }

                    else -> {
                        Text(strings.text("home.dialog.setup.preparing"))
                    }
                }
            },
            confirmButton = {
                when (preMatchPhase) {
                    PreMatchPhase.SECRET_SELECTION -> {
                        TextButton(
                            onClick = {
                                val preMatchConfig = localBotDuelConfig(configuredPvpMode)
                                if (preMatchSecretInput.length != configuredPvpMode.config.codeLength) {
                                    preMatchError = strings.homeEnterDigits(
                                        configuredPvpMode.config.codeLength,
                                    )
                                } else if (!GuessValidator.validate(preMatchSecretInput, preMatchConfig)) {
                                    preMatchError = strings.text("home.dialog.setup.invalid_secret")
                                } else {
                                    preMatchPhase = PreMatchPhase.WAITING_OPPONENT_SECRET
                                }
                            }
                        ) {
                            Text(strings.text("home.dialog.setup.confirm"))
                        }
                    }

                    PreMatchPhase.CANCELLED_TIMEOUT -> {
                        TextButton(
                            onClick = {
                                showPreMatchDialog = false
                                preMatchPhase = PreMatchPhase.SECRET_SELECTION
                            }
                        ) {
                            Text(strings.text("home.dialog.setup.close"))
                        }
                    }

                    else -> Unit
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPreMatchDialog = false
                        preMatchPhase = PreMatchPhase.SECRET_SELECTION
                        preMatchTimeoutLeft = pvpMode.preMatchConfig?.secretSelectionTimeoutSeconds ?: 60
                        botWaitLeft = pvpMode.preMatchConfig?.devBotSecretDelaySeconds ?: 5
                    }
                ) {
                    Text(strings.text("home.dialog.setup.cancel"))
                }
            }
        )
    }
}

internal sealed interface DuelBotTurnResult {
    data class Completed(
        val guess: String,
        val score: Int,
        val confirmedPositions: Int,
    ) : DuelBotTurnResult

    data class Failed(
        val cause: Exception,
        val stage: DuelBotTurnStage,
    ) : DuelBotTurnResult
}

internal enum class DuelBotTurnStage {
    VALIDATE_SECRET,
    CREATE_GUESS,
    SCORE_GUESS,
    REGISTER_FEEDBACK,
    READ_PROGRESS,
}

internal fun resolveDuelBotTurn(
    playerSecret: String,
    codeLength: Int,
    nextGuess: () -> String,
    registerFeedback: (String, Int) -> Unit,
    confirmedPositions: () -> Int,
): DuelBotTurnResult {
    var stage = DuelBotTurnStage.VALIDATE_SECRET
    return try {
        require(playerSecret.length == codeLength) {
            "Duel player secret length does not match the configured code length"
        }
        stage = DuelBotTurnStage.CREATE_GUESS
        val guess = nextGuess()
        stage = DuelBotTurnStage.SCORE_GUESS
        val score = ScoreCalculator.countExactMatches(playerSecret, guess)
        stage = DuelBotTurnStage.REGISTER_FEEDBACK
        registerFeedback(guess, score)
        stage = DuelBotTurnStage.READ_PROGRESS
        DuelBotTurnResult.Completed(
            guess = guess,
            score = score,
            confirmedPositions = confirmedPositions(),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        DuelBotTurnResult.Failed(error, stage)
    }
}

internal fun localBotDuelConfig(
    mode: GameModeDefinition,
    seed: Long? = null,
): GameConfig = mode.config.copy(
    attemptLimit = LOCAL_DUEL_ATTEMPT_CAPACITY,
    seed = seed,
)

internal fun localBotRaceConfig(
    mode: GameModeDefinition,
    seed: Long? = null,
): GameConfig = mode.config.copy(
    attemptLimit = LOCAL_DUEL_ATTEMPT_CAPACITY,
    seed = seed,
)

internal fun raceBotReactionDelayMillis(difficulty: BotDifficulty): Long =
    BotProfiles.forDifficulty(difficulty).reactionDelayMillis * RACE_BOT_PACE_MULTIPLIER

internal fun isRaceBotVictory(score: Int, codeLength: Int): Boolean =
    codeLength > 0 && score == codeLength

internal fun GameModeDefinition.withCodeLength(codeLength: Int): GameModeDefinition = copy(
    config = config.copy(codeLength = selectHomeCodeLength(codeLength)),
)

private const val LOCAL_DUEL_ATTEMPT_CAPACITY = 999
private const val MAX_RACE_BOT_CONSECUTIVE_FAILURES = 3
private const val RACE_BOT_PACE_MULTIPLIER = 3L

@Composable
private fun HomeSelectionScreen(
    pveMode: GameModeDefinition,
    pvpMode: GameModeDefinition,
    onOpenPve: () -> Unit,
    onOpenPvp: () -> Unit,
    onOpenCompany: () -> Unit,
) {
    val strings = LocalAppStrings.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        val compact = maxWidth < 560.dp || maxHeight < 620.dp
        val heroSpacing = if (compact) 10.dp else maxHeight * 0.022f
        val scrollModifier = if (compact) {
            Modifier.verticalScroll(rememberScrollState())
        } else {
            Modifier
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(scrollModifier)
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(heroSpacing),
        ) {
            Text(
                text = buildAnnotatedString {
                    append("Inplace")
                    withStyle(SpanStyle(color = InplaceXColors.ToyOrangeTop)) {
                        append("X")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { heading() },
                style = if (compact) {
                    MaterialTheme.typography.displaySmall
                } else {
                    MaterialTheme.typography.displayMedium
                },
                color = Color.White,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
            Text(
                text = strings.text("home.subtitle"),
                modifier = Modifier.fillMaxWidth(),
                fontSize = 14.sp,
                lineHeight = 18.sp,
                color = InplaceXColors.ToyCream,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )

            if (compact) {
                SceneActionTile(
                    title = strings.text(pveMode.titleKey),
                    subtitle = strings.text(pveMode.subtitleKey),
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = Icons.Outlined.Timer,
                    trailingIcon = Icons.Outlined.ChevronRight,
                    accentBrush = Brush.verticalGradient(
                        listOf(
                            FinalUiColors.ModeOrangeTop,
                            FinalUiColors.ModeOrange,
                            FinalUiColors.ModeOrangeDeep,
                        )
                    ),
                    contentColor = FinalUiColors.WarmText,
                    singleLineTitle = true,
                    compact = true,
                    subtitleMaxLines = 2,
                    onClick = onOpenPve
                )
                SceneActionTile(
                    title = strings.text(pvpMode.titleKey),
                    subtitle = strings.text(pvpMode.subtitleKey),
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = Icons.Outlined.Groups,
                    trailingIcon = Icons.Outlined.ChevronRight,
                    accentBrush = Brush.verticalGradient(
                        listOf(
                            FinalUiColors.ModePurpleTop,
                            FinalUiColors.ModePurple,
                            FinalUiColors.ModePurpleDeep,
                        )
                    ),
                    singleLineTitle = true,
                    compact = true,
                    subtitleMaxLines = 2,
                    onClick = onOpenPvp
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SceneActionTile(
                        title = strings.text(pveMode.titleKey),
                        subtitle = strings.text(pveMode.subtitleKey),
                        modifier = Modifier.weight(1f),
                        leadingIcon = Icons.Outlined.Timer,
                        trailingIcon = Icons.Outlined.ChevronRight,
                        accentBrush = Brush.verticalGradient(
                            listOf(
                                FinalUiColors.ModeOrangeTop,
                                FinalUiColors.ModeOrange,
                                FinalUiColors.ModeOrangeDeep,
                            )
                        ),
                        contentColor = FinalUiColors.WarmText,
                        singleLineTitle = true,
                        compact = true,
                        subtitleMaxLines = 2,
                        onClick = onOpenPve,
                    )
                    SceneActionTile(
                        title = strings.text(pvpMode.titleKey),
                        subtitle = strings.text(pvpMode.subtitleKey),
                        modifier = Modifier.weight(1f),
                        leadingIcon = Icons.Outlined.Groups,
                        trailingIcon = Icons.Outlined.ChevronRight,
                        accentBrush = Brush.verticalGradient(
                            listOf(
                                FinalUiColors.ModePurpleTop,
                                FinalUiColors.ModePurple,
                                FinalUiColors.ModePurpleDeep,
                            )
                        ),
                        singleLineTitle = true,
                        compact = true,
                        subtitleMaxLines = 2,
                        onClick = onOpenPvp,
                    )
                }
            }

            SceneActionTile(
                title = strings.text("home.company.continue"),
                subtitle = strings.text("home.company.teaser"),
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = Icons.Outlined.Map,
                trailingIcon = Icons.Outlined.ChevronRight,
                accentBrush = Brush.verticalGradient(
                    listOf(
                        FinalUiColors.ModeGreenTop,
                        FinalUiColors.ModeGreen,
                        FinalUiColors.ModeGreenDeep,
                    )
                ),
                singleLineTitle = true,
                compact = true,
                subtitleMaxLines = 2,
                onClick = onOpenCompany,
            )

            if (!compact) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

internal fun GameModeDefinition.toFieldParams(typeGame: TypeGame): GameFieldParams {
    return GameFieldParams(
        typeGame = typeGame,
        useHints = hintsEnabled,
        useBoosts = false,
        timeAll = if (typeGame == TypeGame.DuelMatch) 0 else (totalTimeLimitSeconds ?: 0),
        timeMove = turnTimeLimitSeconds ?: 0,
        limitMoves = moveLimit ?: 0,
        lenSecret = config.codeLength,
        allowDuplicates = config.allowDuplicates,
        forbidAllSameDigitsGuess = config.forbidAllSameDigitsGuess,
        forbidAdjacentDuplicates = config.forbidAdjacentDuplicates,
        forbidTripleDuplicates = config.forbidTripleDuplicates,
        maxConsecutiveDuplicateDigits = config.maxConsecutiveDuplicateDigits,
    )
}
