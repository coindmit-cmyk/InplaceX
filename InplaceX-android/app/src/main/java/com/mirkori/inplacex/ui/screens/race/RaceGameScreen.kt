package com.mirkori.inplacex.ui.screens.race

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.model.AnalysisBoardState
import com.mirkori.inplacex.core.model.AnalysisCellState
import com.mirkori.inplacex.core.model.GameConfig
import com.mirkori.inplacex.core.model.GameStatus
import com.mirkori.inplacex.core.model.MatchState
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.shell.AppBottomAd
import com.mirkori.inplacex.ui.utils.buildKnownDigitsFromAnalysis
import kotlinx.coroutines.delay

@Composable
fun RaceGameScreen(
    paddingValues: PaddingValues,
    config: GameConfig,
    matchState: MatchState,
    currentGuess: String,
    analysisBoard: AnalysisBoardState,
    elapsedSeconds: Long,
    onBack: () -> Unit,
    onGuessChange: (String) -> Unit,
    onAnalysisCellClick: (digit: Int, position: Int) -> Unit,
    onRemoveLastDigit: () -> Unit,
    onClearGuess: () -> Unit,
    onAppendDigit: (Int) -> Unit,
    onSubmitGuess: () -> Unit,
    onTick: () -> Unit,
    onRestart: () -> Unit
) {
    val safeDrawing = WindowInsets.safeDrawing.asPaddingValues()
    val navBar = WindowInsets.navigationBars.asPaddingValues()
    val knownDigits = buildKnownDigitsFromAnalysis(analysisBoard)

    LaunchedEffect(matchState.status, elapsedSeconds) {
        if (matchState.status == GameStatus.IN_PROGRESS) {
            delay(1000)
            onTick()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(
                top = safeDrawing.calculateTopPadding(),
                bottom = navBar.calculateBottomPadding()
            )
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    )
                )
            )
    ) {
        val topPanelHeight = maxHeight * 0.10f
        val guessPanelHeight = maxHeight * 0.12f
        val middleHeight = maxHeight * 0.46f
        val actionPanelHeight = maxHeight * 0.11f
        val numpadPanelHeight = maxHeight * 0.11f

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topPanelHeight),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 2.dp
            ) {
                RaceTopPanel(
                    config = config,
                    matchState = matchState,
                    elapsedSeconds = elapsedSeconds,
                    knownDigits = knownDigits,
                    onBack = onBack
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(guessPanelHeight),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 2.dp
            ) {
                RaceCurrentGuessPanel(
                    codeLength = config.codeLength,
                    currentGuess = currentGuess
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(middleHeight),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 2.dp
                ) {
                    RaceHistoryPanel(matchState = matchState)
                }

                Surface(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 2.dp
                ) {
                    RaceMatrixPanel(
                        board = analysisBoard,
                        onAnalysisCellClick = onAnalysisCellClick
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(actionPanelHeight),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 2.dp
            ) {
                RaceActionPanel(
                    currentGuess = currentGuess,
                    codeLength = config.codeLength,
                    isSubmitEnabled = currentGuess.length == config.codeLength &&
                        matchState.status == GameStatus.IN_PROGRESS,
                    onRemoveLastDigit = onRemoveLastDigit,
                    onClearGuess = onClearGuess,
                    onSubmitGuess = onSubmitGuess,
                    onRestart = onRestart
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(numpadPanelHeight),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 2.dp
            ) {
                RaceNumpadPanel(
                    currentGuess = currentGuess,
                    codeLength = config.codeLength,
                    onAppendDigit = onAppendDigit
                )
            }

            AppBottomAd()
        }
    }
}

@Composable
private fun RaceTopPanel(
    config: GameConfig,
    matchState: MatchState,
    elapsedSeconds: Long,
    knownDigits: List<Char?>,
    onBack: () -> Unit
) {
    val strings = LocalAppStrings.current
    val statusText = raceStatusText(matchState.status, strings::text)
    val duplicatesText = strings.text(
        if (config.allowDuplicates) {
            "game.race.duplicates.enabled"
        } else {
            "game.race.duplicates.disabled"
        },
    )

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(onClick = onBack) {
            Text(strings.text("top.back"))
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(strings.text("game.race.title"), style = MaterialTheme.typography.titleMedium)
            Text(
                strings.text("game.race.status_line")
                    .replace("{status}", statusText)
                    .replace("{duplicates}", duplicatesText),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                strings.text("game.race.stats_line")
                    .replace("{attempts}", "${matchState.attempts.size}/${config.attemptLimit}")
                    .replace("{time}", formatElapsed(elapsedSeconds)),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(
            modifier = Modifier.weight(1.25f),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            knownDigits.forEach { value ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(value?.toString() ?: "_")
                }
            }
        }
    }
}

@Composable
private fun RaceCurrentGuessPanel(
    codeLength: Int,
    currentGuess: String
) {
    val strings = LocalAppStrings.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(strings.text("game.race.current_attempt"), style = MaterialTheme.typography.titleSmall)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(codeLength) { index ->
                val value = currentGuess.getOrNull(index)?.toString().orEmpty()

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (value.isEmpty()) " " else value,
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun RaceHistoryPanel(
    matchState: MatchState
) {
    val strings = LocalAppStrings.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text(strings.text("game.race.history"), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        if (matchState.attempts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(strings.text("game.race.history_empty"))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                matchState.attempts.asReversed().forEach { attempt ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(attempt.guess)
                            Text("→ ${attempt.exactMatches}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RaceMatrixPanel(
    board: AnalysisBoardState,
    onAnalysisCellClick: (digit: Int, position: Int) -> Unit
) {
    val strings = LocalAppStrings.current
    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text(strings.text("game.race.matrix"), style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(horizontalScroll)
                .verticalScroll(verticalScroll)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(board.codeLength) { position ->
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${position + 1}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            repeat(10) { digit ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(board.codeLength) { position ->
                        val state = board.cells[digit][position]
                        val cellDescription = strings.text("game.race.matrix.cell")
                            .replace("{digit}", digit.toString())
                            .replace("{position}", (position + 1).toString())
                            .replace("{state}", raceAnalysisCellStateText(state, strings::text))
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(colorForCell(state), RoundedCornerShape(8.dp))
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onAnalysisCellClick(digit, position) }
                                .semantics { contentDescription = cellDescription },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = digit.toString(),
                                color = Color.Black
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun RaceActionPanel(
    currentGuess: String,
    codeLength: Int,
    isSubmitEnabled: Boolean,
    onRemoveLastDigit: () -> Unit,
    onClearGuess: () -> Unit,
    onSubmitGuess: () -> Unit,
    onRestart: () -> Unit
) {
    val strings = LocalAppStrings.current
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(
            onClick = onRemoveLastDigit,
            modifier = Modifier
                .weight(1f)
                .semantics {
                    contentDescription = strings.text("game.race.action.remove_last")
                }
        ) {
            Text("⌫")
        }

        FilledTonalButton(
            onClick = onClearGuess,
            modifier = Modifier
                .weight(1f)
                .semantics {
                    contentDescription = strings.text("game.race.action.clear")
                }
        ) {
            Text("X")
        }

        Button(
            onClick = onSubmitGuess,
            enabled = isSubmitEnabled,
            modifier = Modifier.weight(1.4f)
        ) {
            Text(strings.text("game.race.action.check"))
        }

        FilledTonalButton(
            onClick = onRestart,
            modifier = Modifier.weight(1.3f)
        ) {
            Text(strings.text("game.race.action.restart"))
        }

        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("${currentGuess.length}/$codeLength")
            }
        }
    }
}

@Composable
private fun RaceNumpadPanel(
    currentGuess: String,
    codeLength: Int,
    onAppendDigit: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(10) { digit ->
            Button(
                onClick = {
                    if (currentGuess.length < codeLength) {
                        onAppendDigit(digit)
                    }
                }
            ) {
                Text(digit.toString())
            }
        }
    }
}

private fun colorForCell(state: AnalysisCellState): Color {
    return when (state) {
        AnalysisCellState.EMPTY -> Color.Transparent
        AnalysisCellState.NO -> Color(0xFFE57373)
        AnalysisCellState.MAYBE -> Color(0xFFFFF176)
        AnalysisCellState.YES -> Color(0xFF81C784)
    }
}

private fun formatElapsed(seconds: Long): String {
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

internal fun raceStatusText(
    status: GameStatus,
    text: (String) -> String,
): String = when (status) {
    GameStatus.IN_PROGRESS -> text("game.race.status.in_progress")
    GameStatus.WON -> text("game.race.status.won")
    GameStatus.LOST -> text("game.race.status.lost")
}

internal fun raceAnalysisCellStateText(
    state: AnalysisCellState,
    text: (String) -> String,
): String = when (state) {
    AnalysisCellState.EMPTY -> text("game.race.matrix.state.empty")
    AnalysisCellState.NO -> text("game.race.matrix.state.no")
    AnalysisCellState.MAYBE -> text("game.race.matrix.state.maybe")
    AnalysisCellState.YES -> text("game.race.matrix.state.yes")
}
