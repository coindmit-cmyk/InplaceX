package com.mirkori.inplacex.ui.screens.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.engine.GameEngine
import com.mirkori.inplacex.core.match.MatchHintResult
import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.core.model.GameConfig
import kotlinx.coroutines.delay

private enum class TableTool { NO, MAYBE, YES }

private enum class CellMark(val color: Color) {
    EMPTY(Color.Transparent),
    NO(Color(0xFFFFCDD2)),
    MAYBE(Color(0xFFFFF59D)),
    YES(Color(0xFFC8E6C9)),
    AUTO_NO(Color(0xFFEF9A9A)),
    LOCK_YES(Color(0xFFA5D6A7))
}

private enum class HintMode(
    val icon: ImageVector,
    val contentDescription: String,
) {
    OPEN_POSITION(Icons.Outlined.Visibility, "Открыть позицию"),
    CHECK_DIGIT(Icons.Outlined.Tag, "Проверить цифру"),
    CHECK_POSITION(Icons.Outlined.Pin, "Проверить позицию"),
}

private enum class BoostMode(
    val icon: ImageVector,
    val contentDescription: String,
) {
    EXTRA_MOVES(Icons.Outlined.Add, "Add moves"),
    EXTRA_TIME(Icons.Outlined.Schedule, "Add time"),
}

@Composable
fun GameFieldScreen(
    params: GameFieldParams,
    title: String,
    modeLabel: String = title,
    turnLabel: String? = null,
    secondaryStatusText: String? = null,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onDebugSecretChange: (String?) -> Unit = {},
    fixedSecret: String? = null,
    inputEnabled: Boolean = true,
    openPositionHints: Int = 0,
    checkDigitHints: Int = 0,
    checkPositionHints: Int = 0,
    extraMovesBoosts: Int = 0,
    extraTimeBoosts: Int = 0,
    onConsumeOpenPositionHint: () -> Boolean = { false },
    onConsumeCheckDigitHint: () -> Boolean = { false },
    onConsumeCheckPositionHint: () -> Boolean = { false },
    onConsumeExtraMovesBoost: () -> Boolean = { false },
    onConsumeExtraTimeBoost: () -> Boolean = { false },
    onMatchStarted: () -> Unit = {},
    onMatchWon: () -> Unit = {},
    onMatchFinished: (MatchSessionSummary) -> Unit = {},
    onGuessResolved: (guess: String, score: Int, isWin: Boolean) -> Unit = { _, _, _ -> },
    autoRestartOnWin: Boolean = true,
    extraMovesPerBoost: Int = 0,
    extraTimeSecondsPerBoost: Int = 0,
) {
    val engine = remember(params.lenSecret, params.limitMoves) {
        GameEngine(
            config = GameConfig(
                codeLength = params.lenSecret,
                attemptLimit = if (params.limitMoves > 0) params.limitMoves else 999,
            )
        )
    }
    val board = remember(params.lenSecret) {
        List(10) {
            mutableStateListOf<CellMark>().apply {
                repeat(params.lenSecret) { add(CellMark.EMPTY) }
            }
        }
    }
    var snapshot by remember(engine, fixedSecret) { mutableStateOf(engine.start(fixedSecret)) }
    var currentGuess by remember { mutableStateOf("") }
    var selectedTool by remember { mutableStateOf(TableTool.NO) }
    var selectedHintMode by remember { mutableStateOf<HintMode?>(null) }
    var autoExclude by remember { mutableStateOf(true) }
    var elapsedSeconds by remember { mutableStateOf(0) }
    var turnElapsedSeconds by remember { mutableStateOf(0) }
    var bonusMoves by remember { mutableStateOf(0) }
    var bonusTimeSeconds by remember { mutableStateOf(0) }
    var statusText by remember { mutableStateOf("Введите комбинацию или отметьте таблицу") }
    var digitHintDialogText by remember { mutableStateOf<String?>(null) }
    var openPositionHintUses by remember { mutableStateOf(0) }
    var checkDigitHintUses by remember { mutableStateOf(0) }
    var checkPositionHintUses by remember { mutableStateOf(0) }
    var extraMovesBoostUses by remember { mutableStateOf(0) }
    var extraTimeBoostUses by remember { mutableStateOf(0) }
    var completionReported by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsedSeconds += 1
            turnElapsedSeconds += 1
        }
    }

    LaunchedEffect(snapshot.debugSecret) {
        onDebugSecretChange(snapshot.debugSecret)
    }

    LaunchedEffect(engine) {
        onMatchStarted()
    }

    val effectiveTotalTimeLimit = if (params.timeAll > 0) params.timeAll + bonusTimeSeconds else 0

    fun reportMatchFinishedIfNeeded(won: Boolean) {
        if (completionReported) return
        completionReported = true
        onMatchFinished(
            MatchSessionSummary(
                won = won,
                attemptsUsed = snapshot.attempts.size,
                elapsedSeconds = elapsedSeconds,
                hintUses = openPositionHintUses + checkDigitHintUses + checkPositionHintUses,
                boostUses = extraMovesBoostUses + extraTimeBoostUses,
                openPositionHintUses = openPositionHintUses,
                checkDigitHintUses = checkDigitHintUses,
                checkPositionHintUses = checkPositionHintUses,
                extraMovesBoostUses = extraMovesBoostUses,
                extraTimeBoostUses = extraTimeBoostUses,
            )
        )
    }

    LaunchedEffect(elapsedSeconds, effectiveTotalTimeLimit, snapshot.phase) {
        if (
            effectiveTotalTimeLimit > 0 &&
            snapshot.phase == MatchPhase.ACTIVE &&
            elapsedSeconds >= effectiveTotalTimeLimit
        ) {
            snapshot = engine.fail("Time is over")
            statusText = "Time is over"
            reportMatchFinishedIfNeeded(won = false)
        }
    }

    LaunchedEffect(snapshot.phase) {
        if (snapshot.phase == MatchPhase.LOST) {
            reportMatchFinishedIfNeeded(won = false)
        }
    }

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
                board[digit][position] == CellMark.YES || board[digit][position] == CellMark.LOCK_YES
            }
            if (yesDigit != null) {
                chars[position] = ('0'.code + yesDigit).toChar()
            }
        }
        currentGuess = chars.joinToString("")
    }

    fun lockNo(digit: Int, position: Int) {
        if (board[digit][position] != CellMark.LOCK_YES) {
            board[digit][position] = CellMark.AUTO_NO
        }
    }

    fun lockDigitAsImpossibleEverywhere(digit: Int) {
        repeat(params.lenSecret) { position ->
            lockNo(digit, position)
        }
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

    fun inferFromSingleChange(prevGuess: String, prevScore: Int, newGuess: String, newScore: Int) {
        val changedPositions = prevGuess.indices.filter { prevGuess[it] != newGuess[it] }
        if (changedPositions.size != 1) return

        val position = changedPositions.first()
        val oldDigit = prevGuess[position].digitToInt()
        val newDigit = newGuess[position].digitToInt()
        val delta = newScore - prevScore

        when (delta) {
            1 -> {
                lockNo(oldDigit, position)
                setLockedYes(newDigit, position)
            }

            -1 -> {
                lockNo(newDigit, position)
                setLockedYes(oldDigit, position)
            }

            0 -> {
                lockNo(oldDigit, position)
                lockNo(newDigit, position)
            }
        }

        rebuildGuessFromBoard()
    }

    fun inferFromDoubleChange(prevGuess: String, prevScore: Int, newGuess: String, newScore: Int) {
        val changedPositions = prevGuess.indices.filter { prevGuess[it] != newGuess[it] }
        if (changedPositions.size != 2) return

        val delta = newScore - prevScore
        if (delta != 2 && delta != -2) return

        changedPositions.forEach { position ->
            val oldDigit = prevGuess[position].digitToInt()
            val newDigit = newGuess[position].digitToInt()

            if (delta == 2) {
                lockNo(oldDigit, position)
                setLockedYes(newDigit, position)
            } else {
                lockNo(newDigit, position)
                setLockedYes(oldDigit, position)
            }
        }

        rebuildGuessFromBoard()
    }

    fun inferFromLockedMatches(guess: String, score: Int) {
        val lockedMatches = guess.indices.count { position ->
            val digit = guess[position].digitToInt()
            board[digit][position] == CellMark.LOCK_YES
        }
        if (score != lockedMatches) return

        guess.forEachIndexed { position, char ->
            val digit = char.digitToInt()
            if (board[digit][position] != CellMark.LOCK_YES) {
                lockNo(digit, position)
            }
        }
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

    fun hintCount(mode: HintMode): Int = when (mode) {
        HintMode.OPEN_POSITION -> openPositionHints
        HintMode.CHECK_DIGIT -> checkDigitHints
        HintMode.CHECK_POSITION -> checkPositionHints
    }

    fun consumeHintOrShowMessage(mode: HintMode): Boolean {
        if (hintCount(mode) <= 0) {
            statusText = "Подсказки этого типа закончились"
            return false
        }
        val consumed = when (mode) {
            HintMode.OPEN_POSITION -> onConsumeOpenPositionHint()
            HintMode.CHECK_DIGIT -> onConsumeCheckDigitHint()
            HintMode.CHECK_POSITION -> onConsumeCheckPositionHint()
        }
        if (!consumed) {
            statusText = "Подсказки этого типа закончились"
            return false
        }
        return true
    }

    fun applyManualMark(digit: Int, position: Int) {
        if (board[digit][position] == CellMark.AUTO_NO || board[digit][position] == CellMark.LOCK_YES) {
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

    fun handleTableCellClick(digit: Int, position: Int) {
        if (!inputEnabled) return
        when (selectedHintMode) {
            HintMode.CHECK_POSITION -> {
                if (!consumeHintOrShowMessage(HintMode.CHECK_POSITION)) return
                checkPositionHintUses += 1
                val outcome = engine.checkPosition(digit, position)
                val result = outcome.result as? MatchHintResult.PositionChecked
                if (result == null) {
                    statusText = outcome.message ?: "Подсказка недоступна"
                } else {
                    if (result.isMatch) {
                        setLockedYes(result.digit, result.position)
                        statusText = "Подсказка: цифра ${result.digit} стоит в этой позиции"
                    } else {
                        lockNo(result.digit, result.position)
                        statusText = "Подсказка: цифра ${result.digit} не стоит в этой позиции"
                    }
                }
                selectedHintMode = null
            }

            HintMode.CHECK_DIGIT -> {
                statusText = "Выберите цифру на клавиатуре ввода"
            }

            HintMode.OPEN_POSITION -> {
                statusText = "Выберите ячейку в комбинации, чтобы открыть позицию"
            }

            null -> applyManualMark(digit, position)
        }
    }

    fun handleGuessSlotClick(position: Int) {
        if (!inputEnabled) return
        if (selectedHintMode != HintMode.OPEN_POSITION) return
        if (!consumeHintOrShowMessage(HintMode.OPEN_POSITION)) return
        openPositionHintUses += 1

        val outcome = engine.openPosition(position)
        val result = outcome.result as? MatchHintResult.PositionOpened
        if (result == null) {
            statusText = outcome.message ?: "Подсказка недоступна"
        } else {
            setLockedYes(result.digit, result.position)
            statusText = "Подсказка: в позиции ${result.position + 1} стоит цифра ${result.digit}"
        }
        selectedHintMode = null
    }

    fun handleDigitPadInput(digit: Char) {
        if (!inputEnabled) return
        if (selectedHintMode == HintMode.CHECK_DIGIT) {
            if (!consumeHintOrShowMessage(HintMode.CHECK_DIGIT)) return
            checkDigitHintUses += 1
            val outcome = engine.checkDigitCount(digit.digitToInt())
            val result = outcome.result as? MatchHintResult.DigitCountChecked
            if (result == null) {
                statusText = outcome.message ?: "Подсказка недоступна"
            } else {
                if (result.count == 0 && autoExclude) {
                    lockDigitAsImpossibleEverywhere(result.digit)
                }
                digitHintDialogText = "Цифра ${result.digit} встречается ${result.count} раз"
                statusText = "Подсказка применена"
            }
            selectedHintMode = null
            return
        }

        if (normalizedGuess().length < params.lenSecret) {
            addDigitLeftToRight(digit)
        }
    }

    fun handleBoostUse(mode: BoostMode) {
        when (mode) {
            BoostMode.EXTRA_MOVES -> {
                if (extraMovesBoosts <= 0 || !onConsumeExtraMovesBoost()) {
                    statusText = "Бустеры ходов закончились"
                    return
                }
                snapshot = engine.grantExtraMoves(extraMovesPerBoost)
                bonusMoves += extraMovesPerBoost
                extraMovesBoostUses += 1
                statusText = "Добавлено ходов: $extraMovesPerBoost"
            }

            BoostMode.EXTRA_TIME -> {
                if (extraTimeBoosts <= 0 || !onConsumeExtraTimeBoost()) {
                    statusText = "Бустеры времени закончились"
                    return
                }
                bonusTimeSeconds += extraTimeSecondsPerBoost
                extraTimeBoostUses += 1
                statusText = "Добавлено времени: ${extraTimeSecondsPerBoost / 60} мин"
            }
        }
    }

    fun resetGame() {
        snapshot = engine.start(fixedSecret)
        onMatchStarted()
        clearBoard()
        currentGuess = ""
        elapsedSeconds = 0
        turnElapsedSeconds = 0
        bonusMoves = 0
        bonusTimeSeconds = 0
        selectedHintMode = null
        openPositionHintUses = 0
        checkDigitHintUses = 0
        checkPositionHintUses = 0
        extraMovesBoostUses = 0
        extraTimeBoostUses = 0
        completionReported = false
        statusText = "Сгенерировано новое число"
    }

    fun submitGuess() {
        if (!inputEnabled) {
            statusText = "Wait for opponent turn"
            return
        }
        val guess = normalizedGuess()
        if (guess.length != params.lenSecret) {
            statusText = "Введите ${params.lenSecret} цифр"
            return
        }

        val previousAttempts = snapshot.attempts
        val newSnapshot = engine.submit(guess)
        snapshot = newSnapshot

        if (newSnapshot.attempts.size == previousAttempts.size) {
            statusText = newSnapshot.message ?: "Попытка не принята"
            return
        }

        val lastAttempt = newSnapshot.attempts.last()
        onGuessResolved(guess, lastAttempt.score, lastAttempt.isWin)
        if (lastAttempt.score == 0 && autoExclude) {
            guess.forEachIndexed { index, ch ->
                lockNo(ch.digitToInt(), index)
            }
        }

        if (autoExclude) {
            inferFromLockedMatches(guess, lastAttempt.score)
        }

        if (newSnapshot.attempts.size >= 2) {
            val prevAttempt = newSnapshot.attempts[newSnapshot.attempts.lastIndex - 1]
            inferFromSingleChange(
                prevGuess = prevAttempt.guess,
                prevScore = prevAttempt.score,
                newGuess = lastAttempt.guess,
                newScore = lastAttempt.score
            )
            inferFromDoubleChange(
                prevGuess = prevAttempt.guess,
                prevScore = prevAttempt.score,
                newGuess = lastAttempt.guess,
                newScore = lastAttempt.score
            )
        }

        tryAutoSolve()

        when (newSnapshot.phase) {
            MatchPhase.WON -> {
                onMatchWon()
                statusText = "Угадано. Сгенерировано новое число"
                reportMatchFinishedIfNeeded(won = true)
                if (autoRestartOnWin) {
                    resetGame()
                }
            }

            MatchPhase.LOST -> {
                statusText = newSnapshot.message ?: "Попытки закончились"
            }

            else -> {
                statusText = "Совпадений: ${lastAttempt.score}"
                rebuildGuessFromBoard()
                turnElapsedSeconds = 0
            }
        }
    }

    val attemptLines = snapshot.attempts.map { "${it.guess} -> ${it.score}" }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 2.dp,
                color = Color.White.copy(alpha = 0.95f)
            ) {
                TopModule(
                    params = params,
                    title = modeLabel,
                    turnLabel = turnLabel,
                    secondaryStatusText = secondaryStatusText,
                    onBack = onBack,
                    onOpenSettings = onOpenSettings,
                    movesDone = snapshot.attempts.size,
                    totalMovesLimit = params.limitMoves + bonusMoves,
                    elapsedSeconds = elapsedSeconds,
                    turnElapsedSeconds = turnElapsedSeconds,
                    totalTimeLimitSeconds = effectiveTotalTimeLimit,
                    statusText = statusText
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 2.dp,
                    color = Color.White.copy(alpha = 0.95f)
                ) {
                    AttemptsModule(attempts = attemptLines)
                }

                Surface(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 2.dp,
                    color = Color.White.copy(alpha = 0.95f)
                ) {
                    VariantsModule(
                        lenSecret = params.lenSecret,
                        board = board,
                        onCellClick = ::handleTableCellClick
                    )
                }
            }

            if (params.useHints) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 2.dp,
                    color = Color.White.copy(alpha = 0.95f)
                ) {
                    HintsModule(
                        selectedHintMode = selectedHintMode,
                        autoExclude = autoExclude,
                        openPositionHints = openPositionHints,
                        checkDigitHints = checkDigitHints,
                        checkPositionHints = checkPositionHints,
                        onHintSelect = { hintMode ->
                            selectedHintMode = if (selectedHintMode == hintMode) null else hintMode
                            statusText = when (selectedHintMode) {
                                HintMode.CHECK_POSITION -> "Выберите клетку таблицы для проверки позиции"
                                HintMode.OPEN_POSITION -> "Выберите слот в комбинации, чтобы открыть позицию"
                                HintMode.CHECK_DIGIT -> "Выберите цифру на клавиатуре ввода"
                                null -> "Подсказка отменена"
                            }
                        },
                        onToggleAutoExclude = {
                            autoExclude = !autoExclude
                            statusText = if (autoExclude) "Авто режим включен" else "Авто режим выключен"
                        }
                    )
                }
            }

            if (params.useBoosts) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 2.dp,
                    color = Color.White.copy(alpha = 0.95f)
                ) {
                    BoostsModule(
                        extraMovesBoosts = extraMovesBoosts,
                        extraTimeBoosts = extraTimeBoosts,
                        extraMovesPerBoost = extraMovesPerBoost,
                        extraTimeSecondsPerBoost = extraTimeSecondsPerBoost,
                        onUseExtraMoves = { handleBoostUse(BoostMode.EXTRA_MOVES) },
                        onUseExtraTime = { handleBoostUse(BoostMode.EXTRA_TIME) }
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 2.dp,
                color = Color.White.copy(alpha = 0.95f)
            ) {
                ToolsModule(
                    selectedTool = selectedTool,
                    onToolSelect = { selectedTool = it }
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 2.dp,
                color = Color.White.copy(alpha = 0.95f)
            ) {
                InputComposerModule(
                    lenSecret = params.lenSecret,
                    currentGuess = currentGuess,
                    selectedHintMode = selectedHintMode,
                    checkDigitHintSelected = selectedHintMode == HintMode.CHECK_DIGIT,
                    inputEnabled = inputEnabled,
                    onGuessSlotClick = ::handleGuessSlotClick,
                    onDigitClick = ::handleDigitPadInput,
                    onBackspace = ::backspaceGuess,
                    onCheck = ::submitGuess,
                    onReset = ::resetGame,
                    canCheck = normalizedGuess().length == params.lenSecret
                )
            }
        }

        digitHintDialogText?.let { dialogText ->
            AlertDialog(
                onDismissRequest = { digitHintDialogText = null },
                confirmButton = {
                    TextButton(onClick = { digitHintDialogText = null }) {
                        Text("Ок")
                    }
                },
                title = {
                    Text("Подсказка")
                },
                text = {
                    Text(dialogText)
                }
            )
        }
    }
}

@Composable
private fun TopModule(
    params: GameFieldParams,
    title: String,
    turnLabel: String?,
    secondaryStatusText: String?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    movesDone: Int,
    totalMovesLimit: Int,
    elapsedSeconds: Int,
    turnElapsedSeconds: Int,
    totalTimeLimitSeconds: Int,
    statusText: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (title.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        turnLabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            InfoChip("Ходы", moveValue(movesDone, totalMovesLimit), Modifier.weight(1f))
            InfoChip("Общее", timerValue(elapsedSeconds, totalTimeLimitSeconds), Modifier.weight(1f))
            InfoChip("Ход", timerValue(turnElapsedSeconds, params.timeMove), Modifier.weight(1f))
        }

        secondaryStatusText?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF4C6FFF))
        }
        Text(text = statusText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InfoChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        color = Color(0xFFF2F1FB)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF595A72))
            Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AttemptsModule(attempts: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        Text(text = "Попытки", style = MaterialTheme.typography.titleSmall)
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        if (attempts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Пока нет", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(attempts) { line ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        tonalElevation = 1.dp,
                        color = Color(0xFFF7F7FD)
                    ) {
                        Text(
                            text = line,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
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
            .padding(6.dp)
    ) {
        val verticalGap = 3.dp
        val horizontalGap = 3.dp
        val rawCellWidth = (maxWidth - horizontalGap * (lenSecret - 1)) / lenSecret
        val rawCellHeight = (maxHeight - verticalGap * 9) / 10
        val cellSize = minOf(rawCellWidth, rawCellHeight)

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(verticalGap)
            ) {
                repeat(10) { digit ->
                    Row(horizontalArrangement = Arrangement.spacedBy(horizontalGap)) {
                        repeat(lenSecret) { position ->
                            val cell = board[digit][position]
                            Box(
                                modifier = Modifier
                                    .size(cellSize)
                                    .background(cell.color, RoundedCornerShape(6.dp))
                                    .border(
                                        width = if (cell == CellMark.AUTO_NO || cell == CellMark.LOCK_YES) 2.dp else 1.dp,
                                        color = when (cell) {
                                            CellMark.AUTO_NO -> Color(0xFFB71C1C)
                                            CellMark.LOCK_YES -> Color(0xFF1B5E20)
                                            else -> MaterialTheme.colorScheme.outline
                                        },
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable { onCellClick(digit, position) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = digit.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF23253A)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HintsModule(
    selectedHintMode: HintMode?,
    autoExclude: Boolean,
    openPositionHints: Int,
    checkDigitHints: Int,
    checkPositionHints: Int,
    onHintSelect: (HintMode) -> Unit,
    onToggleAutoExclude: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            HintButton(
                hintMode = HintMode.OPEN_POSITION,
                count = openPositionHints,
                selected = selectedHintMode == HintMode.OPEN_POSITION,
                modifier = Modifier.weight(1f),
                onClick = { onHintSelect(HintMode.OPEN_POSITION) }
            )
            HintButton(
                hintMode = HintMode.CHECK_DIGIT,
                count = checkDigitHints,
                selected = selectedHintMode == HintMode.CHECK_DIGIT,
                modifier = Modifier.weight(1f),
                onClick = { onHintSelect(HintMode.CHECK_DIGIT) }
            )
            HintButton(
                hintMode = HintMode.CHECK_POSITION,
                count = checkPositionHints,
                selected = selectedHintMode == HintMode.CHECK_POSITION,
                modifier = Modifier.weight(1f),
                onClick = { onHintSelect(HintMode.CHECK_POSITION) }
            )
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            AutoModeButton(
                autoExclude = autoExclude,
                modifier = Modifier.fillMaxWidth(),
                onClick = onToggleAutoExclude
            )
        }
    }
}

@Composable
private fun BoostsModule(
    extraMovesBoosts: Int,
    extraTimeBoosts: Int,
    extraMovesPerBoost: Int,
    extraTimeSecondsPerBoost: Int,
    onUseExtraMoves: () -> Unit,
    onUseExtraTime: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
    FilledTonalButton(
        onClick = onUseExtraMoves,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
        shape = RoundedCornerShape(12.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("+$extraMovesPerBoost ход.", style = MaterialTheme.typography.labelLarge)
                Text(extraMovesBoosts.toString(), style = MaterialTheme.typography.labelSmall)
            }
        }
    FilledTonalButton(
        onClick = onUseExtraTime,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
        shape = RoundedCornerShape(12.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("+${extraTimeSecondsPerBoost / 60}:${(extraTimeSecondsPerBoost % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.labelLarge)
                Text(extraTimeBoosts.toString(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun HintButton(
    hintMode: HintMode,
    count: Int,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) Color(0xFFCCE0FF) else Color(0xFFF0F1FF),
            contentColor = Color(0xFF23253A)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) Color(0xFF4C6FFF) else Color(0xFFD8DBEA)
        )
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = hintMode.icon,
                contentDescription = hintMode.contentDescription,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AutoModeButton(
    autoExclude: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (autoExclude) Color(0xFFDDE4FF) else Color(0xFFF0F1F6),
            contentColor = Color(0xFF23253A)
        )
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = "Авто режим"
            )
            Text(
                text = if (autoExclude) "Авто" else "Ручной",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ToolsModule(
    selectedTool: TableTool,
    onToolSelect: (TableTool) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolButton(
            title = "Нет",
            color = Color(0xFFE57373),
            selected = selectedTool == TableTool.NO,
            modifier = Modifier.weight(1f),
            onClick = { onToolSelect(TableTool.NO) }
        )
        ToolButton(
            title = "Возможно",
            color = Color(0xFFFFEE58),
            selected = selectedTool == TableTool.MAYBE,
            modifier = Modifier.weight(1.05f),
            onClick = { onToolSelect(TableTool.MAYBE) }
        )
        ToolButton(
            title = "Точно",
            color = Color(0xFF81C784),
            selected = selectedTool == TableTool.YES,
            modifier = Modifier.weight(1f),
            onClick = { onToolSelect(TableTool.YES) }
        )
    }
}

@Composable
private fun ToolButton(
    title: String,
    color: Color,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) color else color.copy(alpha = 0.30f),
            contentColor = Color(0xFF22253B)
        )
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun InputComposerModule(
    lenSecret: Int,
    currentGuess: String,
    selectedHintMode: HintMode?,
    checkDigitHintSelected: Boolean,
    inputEnabled: Boolean,
    onGuessSlotClick: (Int) -> Unit,
    onDigitClick: (Char) -> Unit,
    onBackspace: () -> Unit,
    onCheck: () -> Unit,
    onReset: () -> Unit,
    canCheck: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = "Комбинация", style = MaterialTheme.typography.titleSmall)
        GuessDisplayRow(
            lenSecret = lenSecret,
            currentGuess = currentGuess,
            hintOpenPositionSelected = selectedHintMode == HintMode.OPEN_POSITION,
            inputEnabled = inputEnabled,
            onSlotClick = onGuessSlotClick
        )
        DigitPadRow(
            digits = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0'),
            highlightForHint = checkDigitHintSelected,
            inputEnabled = inputEnabled,
            onDigitClick = onDigitClick,
            onBackspace = onBackspace
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onReset,
                enabled = inputEnabled,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Сброс")
            }

            Button(
                onClick = onCheck,
                enabled = canCheck && inputEnabled,
                modifier = Modifier.weight(1.3f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Подтвердить")
            }
        }
    }
}

@Composable
private fun GuessDisplayRow(
    lenSecret: Int,
    currentGuess: String,
    hintOpenPositionSelected: Boolean,
    inputEnabled: Boolean,
    onSlotClick: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        repeat(lenSecret) { index ->
            val value = currentGuess.padEnd(lenSecret, ' ').getOrNull(index)?.toString().orEmpty()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .border(
                        width = if (hintOpenPositionSelected) 2.dp else 1.dp,
                        color = if (hintOpenPositionSelected) Color(0xFF4C6FFF) else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable(enabled = hintOpenPositionSelected && inputEnabled) { onSlotClick(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (value.isBlank()) " " else value,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun DigitPadRow(
    digits: List<Char>,
    highlightForHint: Boolean,
    inputEnabled: Boolean,
    onDigitClick: (Char) -> Unit,
    onBackspace: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (highlightForHint) 2.dp else 0.dp,
                color = if (highlightForHint) Color(0xFF4C6FFF) else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        digits.forEach { digit ->
            FilledTonalButton(
                onClick = { onDigitClick(digit) },
                enabled = inputEnabled,
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = digit.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        if (onBackspace != null) {
            FilledTonalButton(
                onClick = onBackspace,
                enabled = inputEnabled,
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Backspace,
                        contentDescription = "Стереть"
                    )
                }
            }
        }
    }
}

@Composable
fun GameDebugAdSlot(
    debugSecret: String,
    openPositionHints: Int,
    checkDigitHints: Int,
    checkPositionHints: Int,
    extraMovesBoosts: Int,
    extraTimeBoosts: Int,
    onAddHintsClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Рекламный слот игрового экрана",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Секрет: $debugSecret",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFB71C1C),
            textAlign = TextAlign.Center
        )
        Text(
            text = "Подсказки: $openPositionHints / $checkDigitHints / $checkPositionHints",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Boosts: $extraMovesBoosts / $extraTimeBoosts",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center
        )
        FilledTonalButton(
            onClick = onAddHintsClick,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("+3 подсказки")
        }
    }
}

private fun moveValue(movesDone: Int, limitMoves: Int): String {
    return if (limitMoves > 0) "$movesDone/$limitMoves" else movesDone.toString()
}

private fun timerValue(elapsedSeconds: Int, limitSeconds: Int): String {
    val shown = if (limitSeconds > 0) {
        (limitSeconds - elapsedSeconds).coerceAtLeast(0)
    } else {
        elapsedSeconds
    }
    val minutes = shown / 60
    val seconds = shown % 60
    return "%02d:%02d".format(minutes, seconds)
}
