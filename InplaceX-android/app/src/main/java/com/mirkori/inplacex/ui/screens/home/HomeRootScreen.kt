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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.bot.BotSolver
import com.mirkori.inplacex.core.engine.GuessValidator
import com.mirkori.inplacex.core.engine.ScoreCalculator
import com.mirkori.inplacex.core.engine.SecretGenerator
import com.mirkori.inplacex.core.match.PreMatchPhase
import com.mirkori.inplacex.core.model.GameConfig
import com.mirkori.inplacex.core.model.GameModeDefinition
import com.mirkori.inplacex.platform.config.AppConfigCatalog
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.screens.game.GameFieldParams
import com.mirkori.inplacex.ui.screens.game.GameFieldScreen
import com.mirkori.inplacex.ui.screens.game.MatchSessionSummary
import com.mirkori.inplacex.ui.screens.game.TypeGame
import com.mirkori.inplacex.ui.screens.shared.SceneActionTile
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import com.mirkori.inplacex.ui.screens.shared.SceneSplitStatRow
import com.mirkori.inplacex.ui.theme.InplaceXColors
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
    onWatchRewardedHintAd: (com.mirkori.inplacex.data.local.HintStockType) -> Boolean = { false },
    onMatchStarted: () -> Unit = {},
    onRecordPveResult: (Boolean) -> Unit = {},
    onRecordPvpResult: (Boolean) -> Unit = {},
) {
    var showExitDialog by rememberSaveable { mutableStateOf(false) }
    var showPreMatchDialog by rememberSaveable { mutableStateOf(false) }
    var showDuelResultDialog by rememberSaveable { mutableStateOf(false) }
    var duelResultText by rememberSaveable { mutableStateOf("") }
    var preMatchPhase by rememberSaveable { mutableStateOf(PreMatchPhase.SECRET_SELECTION) }
    var preMatchSecretInput by rememberSaveable { mutableStateOf("") }
    var preMatchTimeoutLeft by rememberSaveable { mutableIntStateOf(60) }
    var botWaitLeft by rememberSaveable { mutableIntStateOf(5) }
    var preMatchError by rememberSaveable { mutableStateOf<String?>(null) }
    var playerSecretForDuel by rememberSaveable { mutableStateOf("") }
    var botSecretForDuel by rememberSaveable { mutableStateOf("") }
    var duelTurnOwner by rememberSaveable { mutableStateOf(DuelTurnOwner.PLAYER) }
    var botLastScore by rememberSaveable { mutableIntStateOf(-1) }
    var botConfirmedPositions by rememberSaveable { mutableIntStateOf(0) }
    var duelSessionSeed by rememberSaveable { mutableIntStateOf(1) }

    val pveMode = AppConfigCatalog.gameModes.first { it.id == "pve_race" }
    val pvpMode = AppConfigCatalog.gameModes.first { it.id == "pvp_bot_duel" }
    val strings = LocalAppStrings.current

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
                        GameConfig(
                            codeLength = pvpMode.config.codeLength,
                            allowDuplicates = pvpMode.config.allowDuplicates,
                            attemptLimit = 999,
                            forbidAllSameDigitsGuess = true,
                            seed = duelSessionSeed.toLong() * 37L,
                        )
                    )
                    duelTurnOwner = DuelTurnOwner.PLAYER
                    botLastScore = -1
                    botConfirmedPositions = 0
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

        if (screenState != HomeScreenState.ROOT) {
            showExitDialog = true
        }
        onExitGameConsumed()
    }

    BackHandler(enabled = showExitDialog || showPreMatchDialog || showDuelResultDialog) {
        when {
            showExitDialog -> showExitDialog = false
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
        enabled = screenState != HomeScreenState.ROOT &&
            !showExitDialog &&
            !showPreMatchDialog &&
            !showDuelResultDialog
    ) {
        showExitDialog = true
    }

    when (screenState) {
        HomeScreenState.ROOT -> {
            HomeSelectionScreen(
                pveMode = pveMode,
                pvpMode = pvpMode,
                onOpenPve = { onScreenStateChange(HomeScreenState.PVE_GAME) },
                onOpenPvp = {
                    preMatchPhase = PreMatchPhase.SECRET_SELECTION
                    preMatchTimeoutLeft = pvpMode.preMatchConfig?.secretSelectionTimeoutSeconds ?: 60
                    botWaitLeft = pvpMode.preMatchConfig?.devBotSecretDelaySeconds ?: 5
                    preMatchSecretInput = ""
                    preMatchError = null
                    showPreMatchDialog = true
                }
            )
        }

        HomeScreenState.PVE_GAME -> {
            GameFieldScreen(
                title = "",
                params = pveMode.toFieldParams(TypeGame.RaceMatch),
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
                onMatchStarted = onMatchStarted,
                onMatchFinished = { summary ->
                    onRecordPveResult(summary.won)
                }
            )
        }

        HomeScreenState.PVP_GAME -> {
            val botSolver = remember(duelSessionSeed, playerSecretForDuel) {
                BotSolver(
                    config = GameConfig(
                        codeLength = pvpMode.config.codeLength,
                        allowDuplicates = pvpMode.config.allowDuplicates,
                        attemptLimit = 999,
                        forbidAllSameDigitsGuess = true,
                    ),
                    difficulty = pvpMode.botDifficulty ?: com.mirkori.inplacex.core.bot.BotDifficulty.MEDIUM,
                    seed = duelSessionSeed.toLong(),
                )
            }

            LaunchedEffect(screenState, duelTurnOwner, duelSessionSeed) {
                if (screenState != HomeScreenState.PVP_GAME || duelTurnOwner != DuelTurnOwner.BOT) return@LaunchedEffect
                delay(2200)

                val botTurn = withContext(Dispatchers.Default) {
                    val decision = botSolver.nextTurn()
                    val score = ScoreCalculator.countExactMatches(playerSecretForDuel, decision.guess)
                    botSolver.registerFeedback(decision.guess, score)
                    BotTurnResolution(
                        score = score,
                        confirmedPositions = botSolver.confirmedPositionsCount(),
                    )
                }
                botLastScore = botTurn.score
                botConfirmedPositions = botTurn.confirmedPositions

                if (botTurn.score == pvpMode.config.codeLength) {
                    onRecordPvpResult(false)
                    duelResultText = strings.homeDuelResultBotWin(botTurn.score)
                    showDuelResultDialog = true
                    onScreenStateChange(HomeScreenState.ROOT)
                    onDebugSecretChange(null)
                } else {
                    duelTurnOwner = DuelTurnOwner.PLAYER
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
                secondaryStatusText = if (botLastScore >= 0) {
                    strings.homeDuelStatus(botLastScore, botConfirmedPositions, pvpMode.config.codeLength)
                } else {
                    strings.homeDuelWaiting(botConfirmedPositions, pvpMode.config.codeLength)
                },
                params = pvpMode.toFieldParams(TypeGame.DuelMatch),
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
                                    preMatchSecretInput = value.filter(Char::isDigit).take(pvpMode.config.codeLength)
                                    preMatchError = null
                                },
                                singleLine = true,
                                label = { Text(strings.homeSecretLabel(pvpMode.config.codeLength)) }
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
                                val preMatchConfig = GameConfig(
                                    codeLength = pvpMode.config.codeLength,
                                    allowDuplicates = pvpMode.config.allowDuplicates,
                                    attemptLimit = 999,
                                    forbidAllSameDigitsGuess = true,
                                )
                                if (preMatchSecretInput.length != pvpMode.config.codeLength) {
                                    preMatchError = strings.homeEnterDigits(pvpMode.config.codeLength)
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

private data class BotTurnResolution(
    val score: Int,
    val confirmedPositions: Int,
)

@Composable
private fun HomeSelectionScreen(
    pveMode: GameModeDefinition,
    pvpMode: GameModeDefinition,
    onOpenPve: () -> Unit,
    onOpenPvp: () -> Unit
) {
    val strings = LocalAppStrings.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        val heroSpacing = maxHeight * 0.022f

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(heroSpacing),
        ) {
            SceneCard(
                accentColor = InplaceXColors.Surface.copy(alpha = 0.96f)
            ) {
                Text(
                    text = AppConfigCatalog.branding.appName,
                    style = MaterialTheme.typography.displaySmall,
                    color = InplaceXColors.Ink,
                )
                Text(
                    text = strings.text("home.subtitle"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SceneSplitStatRow(
                    leftLabel = strings.text("mode.pve.title"),
                    leftValue = strings.homeCodeLength(pveMode.config.codeLength),
                    rightLabel = strings.text("mode.pvp.title"),
                    rightValue = strings.text("home.duel.kind")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SceneActionTile(
                    title = strings.text(pveMode.titleKey),
                    subtitle = strings.text(pveMode.subtitleKey),
                    modifier = Modifier.weight(1f),
                    accentBrush = Brush.verticalGradient(
                        listOf(InplaceXColors.Cyan, InplaceXColors.Cobalt)
                    ),
                    onClick = onOpenPve
                )
                SceneActionTile(
                    title = strings.text(pvpMode.titleKey),
                    subtitle = strings.text(pvpMode.subtitleKey),
                    modifier = Modifier.weight(1f),
                    accentBrush = Brush.verticalGradient(
                        listOf(InplaceXColors.Indigo, Color(0xFF7B2FF2))
                    ),
                    onClick = onOpenPvp
                )
            }

            SceneCard(
                accentColor = InplaceXColors.Surface.copy(alpha = 0.94f)
            ) {
                Text(
                    text = strings.text("section.company.short"),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = strings.text("home.company.teaser"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

private fun GameModeDefinition.toFieldParams(typeGame: TypeGame): GameFieldParams {
    return GameFieldParams(
        typeGame = typeGame,
        useHints = hintsEnabled,
        useBoosts = false,
        timeAll = if (typeGame == TypeGame.DuelMatch) 0 else (totalTimeLimitSeconds ?: 0),
        timeMove = turnTimeLimitSeconds ?: 0,
        limitMoves = if (typeGame == TypeGame.DuelMatch) 0 else config.attemptLimit,
        lenSecret = config.codeLength
    )
}
