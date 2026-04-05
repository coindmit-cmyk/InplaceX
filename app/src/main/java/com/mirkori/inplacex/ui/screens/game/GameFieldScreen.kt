package com.mirkori.inplacex.ui.screens.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width

private enum class TableTool { NO, MAYBE, YES }

private enum class CellMark(val color: Color) {
    EMPTY(Color.Transparent),
    NO(Color(0xFFE57373)),
    MAYBE(Color(0xFFFFF176)),
    YES(Color(0xFF81C784))
}

@Composable
fun GameFieldScreen(
    params: GameFieldParams,
    title: String,
    onBack: () -> Unit
) {
    val attempts = remember { mutableStateListOf<String>() }
    var currentGuess by remember { mutableStateOf("") }
    var selectedTool by remember { mutableStateOf(TableTool.NO) }
    val board = remember(params.lenSecret) {
        List(10) { MutableList(params.lenSecret) { CellMark.EMPTY } }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF7AA7FF),
                        Color(0xFF5F8EF0),
                        Color(0xFF4A73D9)
                    )
                )
            )
    ) {
        val screenWidth = maxWidth
        val screenHeight = maxHeight

        val outerHorizontalPadding = screenWidth * 0.015f
        val outerVerticalPadding = screenHeight * 0.006f
        val blockGap = screenHeight * 0.005f
        val middleGap = screenWidth * 0.010f

        val topHeight = screenHeight * 0.082f
        val infoHeight = screenHeight * 0.068f
        val middleHeight = screenHeight * 0.405f
        val toolsHeight = screenHeight * 0.060f
        val inputHeight = screenHeight * 0.078f
        val digitsHeight = screenHeight * 0.070f
        val checkHeight = screenHeight * 0.058f

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = outerHorizontalPadding,
                    vertical = outerVerticalPadding
                ),
            verticalArrangement = Arrangement.spacedBy(blockGap)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topHeight),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp,
                color = Color.White.copy(alpha = 0.92f)
            ) {
                TopModule(
                    title = title,
                    params = params,
                    onBack = onBack
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(infoHeight),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp,
                color = Color.White.copy(alpha = 0.92f)
            ) {
                GameInfoModule(params = params)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(middleHeight),
                horizontalArrangement = Arrangement.spacedBy(middleGap)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 2.dp,
                    color = Color.White.copy(alpha = 0.92f)
                ) {
                    AttemptsModule(attempts = attempts)
                }

                Surface(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 2.dp,
                    color = Color.White.copy(alpha = 0.92f)
                ) {
                    VariantsModule(
                        lenSecret = params.lenSecret,
                        board = board
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(toolsHeight),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp,
                color = Color.White.copy(alpha = 0.92f)
            ) {
                ToolsModule(
                    useHints = params.useHints,
                    selectedTool = selectedTool,
                    onToolSelect = { selectedTool = it }
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(inputHeight),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp,
                color = Color.White.copy(alpha = 0.92f)
            ) {
                InputModule(
                    lenSecret = params.lenSecret,
                    currentGuess = currentGuess
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(digitsHeight),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp,
                color = Color.White.copy(alpha = 0.92f)
            ) {
                DigitsModule(
                    currentGuess = currentGuess,
                    lenSecret = params.lenSecret,
                    onDigitClick = { digit ->
                        if (currentGuess.length < params.lenSecret) {
                            currentGuess += digit
                        }
                    },
                    onBackspace = {
                        if (currentGuess.isNotEmpty()) {
                            currentGuess = currentGuess.dropLast(1)
                        }
                    },
                    onApplyToolToBoard = { digit ->
                        val mark = when (selectedTool) {
                            TableTool.NO -> CellMark.NO
                            TableTool.MAYBE -> CellMark.MAYBE
                            TableTool.YES -> CellMark.YES
                        }
                        repeat(params.lenSecret) { position ->
                            board[digit.digitToInt()][position] = mark
                        }
                    }
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(checkHeight),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp,
                color = Color.White.copy(alpha = 0.92f)
            ) {
                CheckModule(
                    canCheck = currentGuess.length == params.lenSecret,
                    onCheck = {
                        if (currentGuess.length == params.lenSecret) {
                            attempts.add("${currentGuess} → ?")
                            currentGuess = ""
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun TopModule(
    title: String,
    params: GameFieldParams,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(
            onClick = onBack,
            modifier = Modifier.fillMaxHeight(0.82f)
        ) {
            Text("Назад", style = MaterialTheme.typography.labelMedium)
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = if (params.typeGame == TypeGame.RaceMatch) "RaceMatch" else "DuelMatch",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun GameInfoModule(
    params: GameFieldParams
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        InfoChip("Len", params.lenSecret.toString(), Modifier.weight(1f))
        InfoChip("Hints", if (params.useHints) "ON" else "OFF", Modifier.weight(1f))
        InfoChip("T.All", if (params.timeAll == 0) "∞" else params.timeAll.toString(), Modifier.weight(1f))
        InfoChip("T.Move", if (params.timeMove == 0) "∞" else params.timeMove.toString(), Modifier.weight(1f))
        InfoChip("Moves", if (params.limitMoves == 0) "∞" else params.limitMoves.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun InfoChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun AttemptsModule(
    attempts: List<String>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp)
    ) {
        Text(
            text = "Попытки",
            style = MaterialTheme.typography.titleSmall
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        if (attempts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Пока нет",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                attempts.asReversed().take(8).forEach { line ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 1.dp
                    ) {
                        Text(
                            text = line,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VariantsModule(
    lenSecret: Int,
    board: List<MutableList<CellMark>>
) {
    val scroll = rememberScrollState()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp)
    ) {
        val cellSize = ((maxWidth - 18.dp) / lenSecret).coerceAtMost(maxHeight / 10)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            repeat(10) { digit ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(lenSecret) { position ->
                        Box(
                            modifier = Modifier
                                .size(cellSize)
                                .background(board[digit][position].color, RoundedCornerShape(5.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = digit.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Black
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsModule(
    useHints: Boolean,
    selectedTool: TableTool,
    onToolSelect: (TableTool) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Инстр.",
            modifier = Modifier.weight(0.8f),
            style = MaterialTheme.typography.bodySmall
        )

        FilledTonalButton(
            onClick = { onToolSelect(TableTool.NO) },
            modifier = Modifier.weight(1f)
        ) {
            Text(if (selectedTool == TableTool.NO) "N*" else "N", style = MaterialTheme.typography.labelSmall)
        }

        FilledTonalButton(
            onClick = { onToolSelect(TableTool.MAYBE) },
            modifier = Modifier.weight(1f)
        ) {
            Text(if (selectedTool == TableTool.MAYBE) "M*" else "M", style = MaterialTheme.typography.labelSmall)
        }

        FilledTonalButton(
            onClick = { onToolSelect(TableTool.YES) },
            modifier = Modifier.weight(1f)
        ) {
            Text(if (selectedTool == TableTool.YES) "Y*" else "Y", style = MaterialTheme.typography.labelSmall)
        }

        if (useHints) {
            TextButton(
                onClick = { },
                modifier = Modifier.weight(1f)
            ) {
                Text("Hint", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun InputModule(
    lenSecret: Int,
    currentGuess: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Комбинация",
            style = MaterialTheme.typography.bodySmall
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            repeat(lenSecret) { index ->
                val value = currentGuess.getOrNull(index)?.toString().orEmpty()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (value.isEmpty()) " " else value,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun DigitsModule(
    currentGuess: String,
    lenSecret: Int,
    onDigitClick: (String) -> Unit,
    onBackspace: () -> Unit,
    onApplyToolToBoard: (Char) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        val buttonGap = 2.dp
        val buttonWidth = ((maxWidth - buttonGap * 9 - 18.dp) / 11).coerceAtLeast(20.dp)

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(buttonGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            "1234567890".forEach { digit ->
                FilledTonalButton(
                    onClick = {
                        if (currentGuess.length < lenSecret) {
                            onDigitClick(digit.toString())
                        }
                        onApplyToolToBoard(digit)
                    },
                    modifier = Modifier.width(buttonWidth)
                ) {
                    Text(digit.toString(), style = MaterialTheme.typography.labelSmall)
                }
            }

            TextButton(
                onClick = onBackspace,
                modifier = Modifier.width(buttonWidth)
            ) {
                Text("⌫", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun CheckModule(
    canCheck: Boolean,
    onCheck: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Button(
            onClick = onCheck,
            enabled = canCheck,
            modifier = Modifier.fillMaxWidth(0.62f)
        ) {
            Text(
                text = "Проверить",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
