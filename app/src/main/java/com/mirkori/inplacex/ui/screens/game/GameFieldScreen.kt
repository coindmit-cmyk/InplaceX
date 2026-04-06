
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
    YES(Color(0xFF81C784)),
    AUTO_NO(Color(0xFFB71C1C)),
    LOCK_YES(Color(0xFF1B5E20))
}

@Composable
fun GameFieldScreen(
    params: GameFieldParams,
    title: String,
    onBack: () -> Unit
) {
    val attempts = remember { mutableStateListOf<String>() }
    val evaluatedAttempts = remember { mutableStateListOf<Pair<String, Int>>() }
    var currentGuess by remember { mutableStateOf("") }
    var selectedTool by remember { mutableStateOf(TableTool.NO) }
    var autoExclude by remember { mutableStateOf(true) }

    val board = remember(params.lenSecret) {
        List(10) {
            mutableStateListOf<CellMark>().apply {
                repeat(params.lenSecret) { add(CellMark.EMPTY) }
            }
        }
    }

    var debugSecret by remember(params.lenSecret, params.typeGame) {
        mutableStateOf(generateSecret(params.lenSecret))
    }
    var statusText by remember { mutableStateOf("Введите комбинацию или отмечай таблицу") }

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
                board[digit][position] == CellMark.YES ||
                    board[digit][position] == CellMark.LOCK_YES
            }
            if (yesDigit != null) {
                chars[position] = ('0'.code + yesDigit).toChar()
            }
        }
        currentGuess = chars.joinToString("")
    }

    fun setLockedYes(digit: Int, position: Int) {
        repeat(10) { d ->
            when {
                d == digit -> board[d][position] = CellMark.LOCK_YES
                board[d][position] != CellMark.LOCK_YES -> board[d][position] = CellMark.AUTO_NO
            }
        }

        rebuildGuessFromBoard()
    }

    fun inferFromSingleChange(
        prevGuess: String,
        prevScore: Int,
        newGuess: String,
        newScore: Int
    ) {
        val changedPositions = prevGuess.indices.filter { prevGuess[it] != newGuess[it] }
        if (changedPositions.size != 1) return

        val position = changedPositions.first()
        val oldDigit = prevGuess[position].digitToInt()
        val newDigit = newGuess[position].digitToInt()
        val delta = newScore - prevScore

        when (delta) {
            1 -> {
                if (board[oldDigit][position] != CellMark.LOCK_YES) {
                    board[oldDigit][position] = CellMark.AUTO_NO
                }
                setLockedYes(newDigit, position)
            }

            -1 -> {
                if (board[newDigit][position] != CellMark.LOCK_YES) {
                    board[newDigit][position] = CellMark.AUTO_NO
                }
                setLockedYes(oldDigit, position)
            }

            0 -> {
                if (board[oldDigit][position] != CellMark.LOCK_YES) {
                    board[oldDigit][position] = CellMark.AUTO_NO
                }
                if (board[newDigit][position] != CellMark.LOCK_YES) {
                    board[newDigit][position] = CellMark.AUTO_NO
                }
            }
        }

        rebuildGuessFromBoard()
    }

    fun tryAutoSolve() {
        repeat(params.lenSecret) { position ->
            val possible = (0..9).filter { digit ->
                board[digit][position] != CellMark.NO &&
                        board[digit][position] != CellMark.AUTO_NO
            }

            if (possible.size == 1) {
                setLockedYes(possible.first(), position)
            }
        }
    }

    fun addDigitLeftToRight(digit: Char) {
        val chars = currentGuess.padEnd(params.lenSecret, ' ').toCharArray()
        val index = chars.indexOfFirst { it == ' ' }
        if (index != -1) {
            chars[index] = digit
            currentGuess = String(chars)
        }
    }

    fun backspaceGuess() {
        val chars = currentGuess.padEnd(params.lenSecret, ' ').toCharArray()
        val index = chars.indexOfLast { it != ' ' }
        if (index != -1) {
            chars[index] = ' '
            currentGuess = String(chars)
        }
    }

    fun normalizedGuess(): String = currentGuess.filter { it.isDigit() }

    fun applyMarkAt(digit: Int, position: Int) {
        if (board[digit][position] == CellMark.AUTO_NO ||
            board[digit][position] == CellMark.LOCK_YES
        ) {
            return
        }

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
                    if (board[d][position] == CellMark.YES) {
                        board[d][position] = CellMark.EMPTY
                    }
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
        val topHeight = screenHeight * 0.080f
        val infoHeight = screenHeight * 0.068f
        val middleHeight = screenHeight * 0.520f
        val toolsHeight = screenHeight * 0.060f
        val inputHeight = screenHeight * 0.085f
        val digitsHeight = screenHeight * 0.070f
        val checkHeight = screenHeight * 0.065f

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
                    debugSecret = debugSecret,
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
                    movesDone = attempts.size,
                    autoExclude = autoExclude
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
                    autoExclude = autoExclude,
                    onToolSelect = { selectedTool = it },
                    onToggleAutoExclude = { autoExclude = !autoExclude }
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
                        addDigitLeftToRight(digit.first())
                    },
                    onBackspace = {
                        backspaceGuess()
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
                    canCheck = normalizedGuess().length == params.lenSecret,
                    onCheck = {
                        val guess = normalizedGuess()

                        if (guess.length == params.lenSecret) {
                            val score = exactMatches(debugSecret, guess)

                            if (score == 0 && autoExclude) {
                                guess.forEachIndexed { index, ch ->
                                    val digit = ch.digitToInt()
                                    if (board[digit][index] != CellMark.LOCK_YES) {
                                        board[digit][index] = CellMark.AUTO_NO
                                    }
                                }
                            }

                            if (evaluatedAttempts.isNotEmpty()) {
                                val (prevGuess, prevScore) = evaluatedAttempts.last()
                                inferFromSingleChange(
                                    prevGuess = prevGuess,
                                    prevScore = prevScore,
                                    newGuess = guess,
                                    newScore = score
                                )
                            }

                            tryAutoSolve()

                            attempts.add("$guess → $score")
                            evaluatedAttempts.add(guess to score)

                            if (score == params.lenSecret) {
                                statusText = "Угадал. Новое число сгенерировано"
                                debugSecret = generateSecret(params.lenSecret)
                                attempts.clear()
                                evaluatedAttempts.clear()
                                clearBoard()
                                currentGuess = ""
                            } else {
                                statusText = "Совпадений: $score"
                                rebuildGuessFromBoard()
                            }
                        } else {
                            statusText = "Введите ${params.lenSecret} цифр"
                        }
                    },
                    onReset = {
                        debugSecret = generateSecret(params.lenSecret)
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
    movesDone: Int,
    autoExclude: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        InfoChip("Len", params.lenSecret.toString(), Modifier.weight(1f))
        InfoChip("Hints", if (params.useHints) "ON" else "OFF", Modifier.weight(1f))
        InfoChip("Auto", if (autoExclude) "ON" else "OFF", Modifier.weight(1f))
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
                        val cell = board[digit][position]

                        Box(
                            modifier = Modifier
                                .size(cellSize)
                                .background(cell.color, RoundedCornerShape(5.dp))
                                .border(
                                    width = if (cell == CellMark.AUTO_NO || cell == CellMark.LOCK_YES) 2.dp else 1.dp,
                                    color = when (cell) {
                                        CellMark.AUTO_NO -> Color.Black
                                        CellMark.LOCK_YES -> Color(0xFF1B5E20)
                                        else -> MaterialTheme.colorScheme.outline
                                    },
                                    shape = RoundedCornerShape(5.dp)
                                )
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
    autoExclude: Boolean,
    onToolSelect: (TableTool) -> Unit,
    onToggleAutoExclude: () -> Unit
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

        TextButton(
            onClick = onToggleAutoExclude,
            modifier = Modifier.weight(1f)
        ) {
            Text(if (autoExclude) "A*" else "A", style = MaterialTheme.typography.labelSmall)
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
                        val value = currentGuess.padEnd(lenSecret, ' ').getOrNull(index)?.toString().orEmpty()

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(containerHeight * 0.42f)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (value.isBlank()) " " else value,
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
                    if (currentGuess.filter { it.isDigit() }.length < lenSecret) {
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
