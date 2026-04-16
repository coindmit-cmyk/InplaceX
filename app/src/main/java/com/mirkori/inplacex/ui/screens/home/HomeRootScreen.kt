package com.mirkori.inplacex.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class DuelTurnOwner {
    PLAYER,
    BOT,
}

@Composable
fun HomeRootScreen(
    requestExitGame: Boolean = false,
    onExitGameConsumed: () -> Unit = {},
    onInGameChange: (Boolean) -> Unit = {},
    onDebugSecretChange: (String?) -> Unit = {},
    openPositionHints: Int = 0,
    checkDigitHints: Int = 0,
    checkPositionHints: Int = 0,
    onConsumeOpenPositionHint: () -> Boolean = { false },
    onConsumeCheckDigitHint: () -> Boolean = { false },
    onConsumeCheckPositionHint: () -> Boolean = { false },
    onMatchStarted: () -> Unit = {},
    onRecordPveResult: (Boolean) -> Unit = {},
    onRecordPvpResult: (Boolean) -> Unit = {},
) {
    var screenState by remember { mutableStateOf(HomeScreenState.ROOT) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showPreMatchDialog by remember { mutableStateOf(false) }
    var showDuelResultDialog by remember { mutableStateOf(false) }
    var duelResultText by remember { mutableStateOf("") }
    var preMatchPhase by remember { mutableStateOf(PreMatchPhase.SECRET_SELECTION) }
    var preMatchSecretInput by remember { mutableStateOf("") }
    var preMatchTimeoutLeft by remember { mutableIntStateOf(60) }
    var botWaitLeft by remember { mutableIntStateOf(5) }
    var preMatchError by remember { mutableStateOf<String?>(null) }
    var playerSecretForDuel by remember { mutableStateOf("") }
    var botSecretForDuel by remember { mutableStateOf("") }
    var duelTurnOwner by remember { mutableStateOf(DuelTurnOwner.PLAYER) }
    var botLastScore by remember { mutableIntStateOf(-1) }
    var botConfirmedPositions by remember { mutableIntStateOf(0) }
    var duelSessionSeed by remember { mutableIntStateOf(1) }

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
                    screenState = HomeScreenState.PVP_GAME
                }
            }

            else -> Unit
        }
    }

    LaunchedEffect(requestExitGame) {
        if (requestExitGame && screenState != HomeScreenState.ROOT) {
            screenState = HomeScreenState.ROOT
            showExitDialog = false
            onExitGameConsumed()
            onDebugSecretChange(null)
        }
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

    when (screenState) {
        HomeScreenState.ROOT -> {
            HomeSelectionScreen(
                pveMode = pveMode,
                pvpMode = pvpMode,
                onOpenPve = { screenState = HomeScreenState.PVE_GAME },
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
                onConsumeOpenPositionHint = onConsumeOpenPositionHint,
                onConsumeCheckDigitHint = onConsumeCheckDigitHint,
                onConsumeCheckPositionHint = onConsumeCheckPositionHint,
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
                    duelResultText = "Opponent guessed your secret. Last bot score: ${botTurn.score}"
                    showDuelResultDialog = true
                    screenState = HomeScreenState.ROOT
                    onDebugSecretChange(null)
                } else {
                    duelTurnOwner = DuelTurnOwner.PLAYER
                }
            }

            GameFieldScreen(
                title = "",
                modeLabel = "",
                turnLabel = if (duelTurnOwner == DuelTurnOwner.PLAYER) "Player turn" else "Opponent turn",
                secondaryStatusText = buildString {
                    if (botLastScore >= 0) {
                        append("Opponent last score: $botLastScore")
                    } else {
                        append("Opponent has not moved yet")
                    }
                    append(" | Confirmed: $botConfirmedPositions/${pvpMode.config.codeLength}")
                },
                params = pvpMode.toFieldParams(TypeGame.DuelMatch),
                onBack = { showExitDialog = true },
                onDebugSecretChange = onDebugSecretChange,
                fixedSecret = botSecretForDuel,
                inputEnabled = duelTurnOwner == DuelTurnOwner.PLAYER,
                openPositionHints = openPositionHints,
                checkDigitHints = checkDigitHints,
                checkPositionHints = checkPositionHints,
                onConsumeOpenPositionHint = onConsumeOpenPositionHint,
                onConsumeCheckDigitHint = onConsumeCheckDigitHint,
                onConsumeCheckPositionHint = onConsumeCheckPositionHint,
                onMatchStarted = onMatchStarted,
                onGuessResolved = { _, _, isWin ->
                    if (duelTurnOwner != DuelTurnOwner.PLAYER) return@GameFieldScreen
                    if (isWin) {
                        onRecordPvpResult(true)
                        duelResultText = "You guessed the opponent secret first."
                        showDuelResultDialog = true
                        screenState = HomeScreenState.ROOT
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
            title = { Text("Exit current game?") },
            text = { Text("The current match will be closed and you will return to the home screen.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        screenState = HomeScreenState.ROOT
                        onDebugSecretChange(null)
                    }
                ) {
                    Text("Exit")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("Stay")
                }
            }
        )
    }

    if (showDuelResultDialog) {
        AlertDialog(
            onDismissRequest = { showDuelResultDialog = false },
            title = { Text("Duel result") },
            text = { Text(duelResultText) },
            confirmButton = {
                TextButton(onClick = { showDuelResultDialog = false }) {
                    Text("OK")
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
            title = { Text("Secret setup") },
            text = {
                when (preMatchPhase) {
                    PreMatchPhase.SECRET_SELECTION -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Set your secret before the duel starts.")
                            OutlinedTextField(
                                value = preMatchSecretInput,
                                onValueChange = { value ->
                                    preMatchSecretInput = value.filter(Char::isDigit).take(pvpMode.config.codeLength)
                                    preMatchError = null
                                },
                                singleLine = true,
                                label = { Text("Secret (${pvpMode.config.codeLength} digits)") }
                            )
                            Text("Time left: ${preMatchTimeoutLeft}s")
                            preMatchError?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
                        }
                    }

                    PreMatchPhase.WAITING_OPPONENT_SECRET -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Waiting for opponent secret...")
                            Text("Bot will be ready in ${botWaitLeft}s")
                        }
                    }

                    PreMatchPhase.CANCELLED_TIMEOUT -> {
                        Text("Secret selection timed out. Match was cancelled.")
                    }

                    else -> {
                        Text("Preparing match...")
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
                                    preMatchError = "Enter ${pvpMode.config.codeLength} digits"
                                } else if (!GuessValidator.validate(preMatchSecretInput, preMatchConfig)) {
                                    preMatchError = "Secret does not match duel rules"
                                } else {
                                    preMatchPhase = PreMatchPhase.WAITING_OPPONENT_SECRET
                                }
                            }
                        ) {
                            Text("Confirm")
                        }
                    }

                    PreMatchPhase.CANCELLED_TIMEOUT -> {
                        TextButton(
                            onClick = {
                                showPreMatchDialog = false
                                preMatchPhase = PreMatchPhase.SECRET_SELECTION
                            }
                        ) {
                            Text("Close")
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
                    Text("Cancel")
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(
                space = maxHeight * 0.03f,
                alignment = Alignment.CenterVertically
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = strings.text("home.title"),
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = strings.text("home.subtitle"),
                style = MaterialTheme.typography.bodyLarge
            )
            Button(
                onClick = onOpenPve,
                modifier = Modifier.fillMaxWidth(fraction = 0.62f)
            ) {
                Text(strings.text(pveMode.titleKey))
            }
            FilledTonalButton(
                onClick = onOpenPvp,
                modifier = Modifier.fillMaxWidth(fraction = 0.62f)
            ) {
                Text(strings.text(pvpMode.titleKey))
            }
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
