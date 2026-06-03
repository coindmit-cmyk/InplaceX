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
                    duelResultText = "Opponent guessed your secret. Last bot score: ${botTurn.score}"
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
                        duelResultText = "You guessed the opponent secret first."
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
            title = { Text("Exit current game?") },
            text = { Text("The current match will be closed and you will return to the home screen.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        onScreenStateChange(HomeScreenState.ROOT)
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
                accentColor = Color.White.copy(alpha = 0.76f)
            ) {
                Text(
                    text = strings.text("home.title"),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = strings.text("home.subtitle"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SceneSplitStatRow(
                    leftLabel = strings.text("mode.pve.title"),
                    leftValue = "${pveMode.config.codeLength} digits",
                    rightLabel = strings.text("mode.pvp.title"),
                    rightValue = "Bot duel"
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
                    accentBrush = Brush.verticalGradient(listOf(Color(0xFF7BB9FF), Color(0xFF4C6FFF))),
                    onClick = onOpenPve
                )
                SceneActionTile(
                    title = strings.text(pvpMode.titleKey),
                    subtitle = strings.text(pvpMode.subtitleKey),
                    modifier = Modifier.weight(1f),
                    accentBrush = Brush.verticalGradient(listOf(Color(0xFF8A8CFF), Color(0xFF5D4EFF))),
                    onClick = onOpenPvp
                )
            }

            SceneCard(
                accentColor = Color.White.copy(alpha = 0.72f)
            ) {
                Text(
                    text = strings.text("section.company.short"),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Campaign, progress road and energy now live in the Company tab.",
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
