package com.mirkori.inplacex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.model.AnalysisBoardState
import com.mirkori.inplacex.core.model.AnalysisCellState
import com.mirkori.inplacex.core.model.GameTab

@Composable
fun GameScreen(
    paddingValues: PaddingValues,
    currentTab: GameTab,
    onTabChange: (GameTab) -> Unit,
    knownDigits: List<Char?>,
    currentGuess: String,
    onGuessChange: (String) -> Unit,
    attemptLimit: Int,
    attemptsUsed: Int,
    statusText: String,
    historyLines: List<String>,
    analysisBoard: AnalysisBoardState,
    onAnalysisCellClick: (digit: Int, position: Int) -> Unit,
    onSubmitGuess: () -> Unit,
    onRestart: () -> Unit,
    isSubmitEnabled: Boolean,
    debugSecret: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        KnownDigitsRow(
            knownDigits = knownDigits,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )

        when (currentTab) {
            GameTab.HISTORY -> {
                HistoryBlock(
                    attemptLimit = attemptLimit,
                    attemptsUsed = attemptsUsed,
                    statusText = statusText,
                    historyLines = historyLines,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                )
            }

            GameTab.ANALYSIS -> {
                AnalysisBlock(
                    board = analysisBoard,
                    onCellClick = onAnalysisCellClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                )
            }
        }

        InputBlock(
            currentGuess = currentGuess,
            onGuessChange = onGuessChange,
            onSubmitGuess = onSubmitGuess,
            isSubmitEnabled = isSubmitEnabled,
            onRestart = onRestart,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )

        DebugBlock(
            debugSecret = debugSecret,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )

        BottomNavBar(
            currentTab = currentTab,
            onTabChange = onTabChange
        )
    }
}

@Composable
private fun KnownDigitsRow(
    knownDigits: List<Char?>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            knownDigits.forEach { value ->
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = value?.toString() ?: "?")
                }
            }
        }
    }
}

@Composable
private fun HistoryBlock(
    attemptLimit: Int,
    attemptsUsed: Int,
    statusText: String,
    historyLines: List<String>,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("История")
            Text("Попытки: $attemptsUsed / $attemptLimit")
            Text("Статус: $statusText")

            HorizontalDivider()

            if (historyLines.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("История ходов пуста")
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    historyLines.forEach { line ->
                        Text(line)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalysisBlock(
    board: AnalysisBoardState,
    onCellClick: (digit: Int, position: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Text("Аналитика")
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .verticalScroll(rememberScrollState())
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .size(36.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("#")
                        }

                        repeat(board.codeLength) { position ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outline),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${position + 1}")
                            }
                        }
                    }

                    repeat(10) { digit ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outline),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("$digit")
                            }

                            repeat(board.codeLength) { position ->
                                val state = board.cells[digit][position]

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(cellColor(state))
                                        .border(1.dp, MaterialTheme.colorScheme.outline)
                                        .clickable {
                                            onCellClick(digit, position)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(cellLabel(state))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InputBlock(
    currentGuess: String,
    onGuessChange: (String) -> Unit,
    onSubmitGuess: () -> Unit,
    isSubmitEnabled: Boolean,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = currentGuess,
                onValueChange = onGuessChange,
                label = { Text("Ввод числа") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSubmitGuess,
                    enabled = isSubmitEnabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Проверить")
                }

                TextButton(
                    onClick = onRestart,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Новая игра")
                }
            }
        }
    }
}

@Composable
private fun DebugBlock(
    debugSecret: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        tonalElevation = 2.dp
    ) {
        Text(
            text = "DEBUG SECRET: $debugSecret",
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun BottomNavBar(
    currentTab: GameTab,
    onTabChange: (GameTab) -> Unit
) {
    NavigationBar {
        NavigationBarItem(
            selected = currentTab == GameTab.HISTORY,
            onClick = { onTabChange(GameTab.HISTORY) },
            icon = {},
            label = { Text("История") }
        )

        NavigationBarItem(
            selected = currentTab == GameTab.ANALYSIS,
            onClick = { onTabChange(GameTab.ANALYSIS) },
            icon = {},
            label = { Text("Аналитика") }
        )
    }
}

private fun cellColor(state: AnalysisCellState): Color {
    return when (state) {
        AnalysisCellState.EMPTY -> Color.Transparent
        AnalysisCellState.NO -> Color(0xFFFFCDD2)
        AnalysisCellState.MAYBE -> Color(0xFFFFF59D)
        AnalysisCellState.YES -> Color(0xFFC8E6C9)
    }
}

private fun cellLabel(state: AnalysisCellState): String {
    return when (state) {
        AnalysisCellState.EMPTY -> ""
        AnalysisCellState.NO -> "X"
        AnalysisCellState.MAYBE -> "?"
        AnalysisCellState.YES -> "✓"
    }
}