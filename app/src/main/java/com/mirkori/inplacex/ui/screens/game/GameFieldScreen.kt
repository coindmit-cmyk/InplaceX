package com.mirkori.inplacex.ui.screens.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

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
        List(10) {
            mutableStateListOf<CellMark>().apply {
                repeat(params.lenSecret) { add(CellMark.EMPTY) }
            }
        }
    }

    var secret by remember(params.lenSecret, params.typeGame) {
        mutableStateOf(generateSecret(params.lenSecret))
    }
    var statusText by remember { mutableStateOf("Выбери инструмент и отмечай таблицу") }

    fun clearBoard() {
        repeat(10) { digit ->
            repeat(params.lenSecret) { position ->
                board[digit][position] = CellMark.EMPTY
            }
        }
    }

    fun rebuildGuessFromBoard() {
        val chars = MutableList(params.lenSecret) { ' ' }
        repeat(params.lenSecret) { position ->
            val yesDigit = (0..9).firstOrNull { digit ->
                board[digit][position] == CellMark.YES
            }
            if (yesDigit != null) {
                chars[position] = ('0'.code + yesDigit).toChar()
            }
        }
        currentGuess = chars.joinToString("")
    }

    fun applyMarkAt(digit: Int, position: Int) {
        when (selectedTool) {
            TableTool.NO -> {
                board[digit][position] =
                    if (board[digit][position] == CellMark.NO) CellMark.EMPTY else CellMark.NO
            }

            TableTool.MAYBE -> {
                board[digit][position] =
                    if (board[digit][position] == CellMark.MAYBE) CellMark.EMPTY else CellMark.MAYBE
            }

            TableTool.YES -> {
                val alreadyYes = board[digit][position] == CellMark.YES
                repeat(10) { d ->
                    board[d][position] = CellMark.EMPTY
                }
                if (!alreadyYes) {
                    board[digit][position] = CellMark.YES
                }
                rebuildGuessFromBoard()
            }
        }
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

        val outerHorizontalPadding = screenWidth * 0.010f
        val outerVerticalPadding = screenHeight * 0.004f
        val blockGap = screenHeight * 0.003f
        val middleGap = screenWidth * 0.008f
        val topHeight = screenHeight * 0.095f
        val infoHeight = screenHeight * 0.068f
        val middleHeight = screenHeight * 0.505f
        val toolsHeight = screenHeight * 0.060f
        val inputHeight = screenHeight * 0.085f
        val digitsHeight = screenHeight * 0.070f
        val checkHeight = screenHeight * 0.075f
        var autoExclude by remember { mutableStateOf(true) }

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
                    onBack = onBack,
                    debugSecret = secret,
                    statusText = statusText
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
                GameInfoModule(
                    params = params,
                    movesDone = attempts.size
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(middleHeight),
                horizontalArrangement = Arrangement.spacedBy(middleGap)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(0.43f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 2.dp,
                    color = Color.White.copy(alpha = 0.92f)
                ) {
                    AttemptsModule(attempts = attempts)
                }

                Surface(
                    modifier = Modifier
                        .weight(0.57f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 2.dp,
                    color = Color.White.copy(alpha = 0.92f)
                ) {
                    VariantsModule(
                        lenSecret = params.lenSecret,
                        board = board,
                        onCellClick = { digit, position ->
                            applyMarkAt(digit, position)
                        }
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
                    onToolSelect = {
                        selectedTool = it
                        statusText = when (it) {
                            TableTool.NO -> "N: цифры нет в позиции"
                            TableTool.MAYBE -> "M: цифра возможна в позиции"
                            TableTool.YES -> "Y: точная цифра в позиции"
                        }
                    }
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
                    currentGuess = currentGuess,
                    containerHeight = inputHeight
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

                        val chars = currentGuess.padEnd(params.lenSecret, ' ').toCharArray()

                        // ищем первую пустую позицию
                        val index = chars.indexOfFirst { it == ' ' }

                        if (index != -1) {
                            chars[index] = digit[0]
                        }

                        currentGuess = String(chars)
                    },
                    onBackspace = {
                        val chars = currentGuess.toCharArray()

                        val index = chars.indexOfLast { it != ' ' }

                        if (index != -1) {
                            chars[index] = ' '
                        }

                        currentGuess = String(chars)
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
                            val score = exactMatches(secret, currentGuess)
                            attempts.add("${currentGuess} → ${score}")
                            statusText = if (score == params.lenSecret) {
                                "Угадал. Новое число сгенерировано"
                            } else {
                                "Совпадений: $score"
                            }

                            if (score == params.lenSecret) {
                                secret = generateSecret(params.lenSecret)
                                attempts.clear()
                                clearBoard()
                            }

                            currentGuess = ""
                        } else {
                            statusText = "Введите ${params.lenSecret} цифр"
                        }
                    },
                    onReset = {
                        secret = generateSecret(params.lenSecret)
                        attempts.clear()
                        currentGuess = ""
                        clearBoard()
                        statusText = "Сгенерировано новое число"
                    }
                )
            }
        }
    }
}

private fun generateSecret(len: Int): String {
    return buildString {
        repeat(len) {
            append(Random.nextInt(0, 10))
        }
    }
}

private fun exactMatches(secret: String, guess: String): Int {
    return secret.indices.count { index -> secret[index] == guess[index] }
}

@Composable
private fun TopModule(
    title: String,
    params: GameFieldParams,
    onBack: () -> Unit,
    debugSecret: String,
    statusText: String
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(
            onClick = onBack,
            modifier = Modifier.fillMaxHeight(0.84f)
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
            Text(
                text = "DEBUG: $debugSecret",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB00020)
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun GameInfoModule(
    params: GameFieldParams,
    movesDone: Int
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        InfoChip("Len", params.lenSecret.toString(), Modifier.weight(1f))
        InfoChip("Hints", if (params.useHints) "ON" else "OFF", Modifier.weight(1f))
        InfoChip("T.All", if (params.timeAll == 0) "∞" else params.timeAll.toString(), Modifier.weight(1f))
        InfoChip("T.Move", if (params.timeMove == 0) "∞" else params.timeMove.toString(), Modifier.weight(1f))
        InfoChip("Moves", movesDone.toString(), Modifier.weight(1f))
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
                .padding(horizontal = 1.dp, vertical = 1.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall)
            Text(text = value, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AttemptsModule(attempts: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp)
    ) {
        Text(text = "Попытки", style = MaterialTheme.typography.titleSmall)
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        if (attempts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "Пока нет", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                attempts.asReversed().take(10).forEach { line ->
                    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp) {
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
    board: List<List<CellMark>>,
    onCellClick: (digit: Int, position: Int) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp)
    ) {
        val verticalGap = 2.dp
        val horizontalGap = 2.dp
        val rawCellWidth = (maxWidth - horizontalGap * (lenSecret - 1)) / lenSecret
        val rawCellHeight = (maxHeight - verticalGap * 9) / 10
        val cellSize = minOf(rawCellWidth, rawCellHeight)

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(verticalGap)
        ) {
            repeat(10) { digit ->
                Row(horizontalArrangement = Arrangement.spacedBy(horizontalGap)) {
                    repeat(lenSecret) { position ->
                        Box(
                            modifier = Modifier
                                .size(cellSize)
                                .background(board[digit][position].color, RoundedCornerShape(5.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
                                .clickable { onCellClick(digit, position) },
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
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Инстр.",
            modifier = Modifier.weight(0.9f),
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
    currentGuess: String,
    containerHeight: Dp
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        val rowWidthFraction = 0.84f

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = "Комбинация", style = MaterialTheme.typography.bodySmall)

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(rowWidthFraction),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    repeat(lenSecret) { index ->
                        val value = currentGuess.getOrNull(index)?.toString().orEmpty()

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(containerHeight * 0.42f)
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
    }
}

@Composable
private fun DigitsModule(
    currentGuess: String,
    lenSecret: Int,
    onDigitClick: (String) -> Unit,
    onBackspace: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        "1234567890".forEach { digit ->
            FilledTonalButton(
                onClick = {
                    if (currentGuess.length < lenSecret) {
                        onDigitClick(digit.toString())
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(0.86f)
            ) {
                Text(
                    text = digit.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
            }
        }

        TextButton(
            onClick = onBackspace,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(0.86f)
                .widthIn(min = 24.dp)
        ) {
            Text("⌫", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CheckModule(
    canCheck: Boolean,
    onCheck: () -> Unit,
    onReset: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(0.92f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onCheck,
                enabled = canCheck,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Проверить",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            FilledTonalButton(
                onClick = onReset,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Сброс",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
