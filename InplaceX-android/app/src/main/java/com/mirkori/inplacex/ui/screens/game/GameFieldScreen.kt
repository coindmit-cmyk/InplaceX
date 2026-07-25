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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import com.mirkori.inplacex.R
import com.mirkori.inplacex.core.engine.GameEngine
import com.mirkori.inplacex.core.match.MatchHintResult
import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.core.model.GameConfig
import com.mirkori.inplacex.data.local.HintStockType
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.logging.AppLog
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
    @param:DrawableRes val iconRes: Int,
    val contentDescription: String,
) {
    OPEN_POSITION(R.drawable.ic_hint_open_position, "Open position"),
    CHECK_DIGIT(R.drawable.ic_hint_check_digit, "Check digit"),
    CHECK_POSITION(R.drawable.ic_hint_check_position, "Check position"),
}

private enum class BoostMode(
    @param:DrawableRes val iconRes: Int,
    val contentDescription: String,
) {
    EXTRA_MOVES(R.drawable.ic_boost_extra_moves, "Add moves"),
    EXTRA_TIME(R.drawable.ic_boost_extra_time, "Add time"),
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
    autoModeAvailable: Boolean = true,
    infiniteHintsEnabled: Boolean = false,
    extraMovesBoosts: Int = 0,
    extraTimeBoosts: Int = 0,
    onConsumeOpenPositionHint: () -> Boolean = { false },
    onConsumeCheckDigitHint: () -> Boolean = { false },
    onConsumeCheckPositionHint: () -> Boolean = { false },
    onWatchRewardedHintAd: (HintStockType) -> Boolean = { false },
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
    val logTag = "GameFieldScreen"
    val strings = LocalAppStrings.current
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
    var autoExclude by remember(autoModeAvailable) { mutableStateOf(autoModeAvailable) }
    var elapsedSeconds by remember { mutableStateOf(0) }
    var turnElapsedSeconds by remember { mutableStateOf(0) }
    var bonusMoves by remember { mutableStateOf(0) }
    var bonusTimeSeconds by remember { mutableStateOf(0) }
    var statusText by remember { mutableStateOf(strings.text("game.status.default")) }
    var digitHintDialogText by remember { mutableStateOf<String?>(null) }
    var pendingRewardedHintMode by remember { mutableStateOf<HintMode?>(null) }
    var rewardedHintAllowance by remember { mutableStateOf<HintMode?>(null) }
    var openPositionHintUses by remember { mutableStateOf(0) }
    var checkDigitHintUses by remember { mutableStateOf(0) }
    var checkPositionHintUses by remember { mutableStateOf(0) }
    var extraMovesBoostUses by remember { mutableStateOf(0) }
    var extraTimeBoostUses by remember { mutableStateOf(0) }
    var completionReported by remember { mutableStateOf(false) }
    val effectiveAutoExclude = autoModeAvailable && autoExclude

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (pendingRewardedHintMode == null) {
                elapsedSeconds += 1
                turnElapsedSeconds += 1
            }
        }
    }

    LaunchedEffect(snapshot.debugSecret) {
        onDebugSecretChange(snapshot.debugSecret)
    }

    LaunchedEffect(engine) {
        AppLog.info(
            tag = logTag,
            message = "match started",
            attributes = mapOf(
                "length" to params.lenSecret.toString(),
                "limitMoves" to params.limitMoves.toString(),
                "timeAllSeconds" to params.timeAll.toString(),
                "fixedSecret" to (fixedSecret != null).toString(),
            ),
        )
        onMatchStarted()
    }

    val effectiveTotalTimeLimit = if (params.timeAll > 0) params.timeAll + bonusTimeSeconds else 0

    fun tr(key: String, vararg replacements: Pair<String, Any>): String {
        var value = strings.text(key)
        replacements.forEach { (name, replacement) ->
            value = value.replace("{$name}", replacement.toString())
        }
        return value
    }

    fun reportMatchFinishedIfNeeded(won: Boolean) {
        if (completionReported) return
        completionReported = true
        AppLog.info(
            tag = logTag,
            message = "match finished",
            attributes = mapOf(
                "won" to won.toString(),
                "attempts" to snapshot.attempts.size.toString(),
                "elapsedSeconds" to elapsedSeconds.toString(),
                "phase" to snapshot.phase.name,
            ),
        )
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
            AppLog.warn(
                tag = logTag,
                message = "match timed out",
                attributes = mapOf(
                    "elapsedSeconds" to elapsedSeconds.toString(),
                    "limitSeconds" to effectiveTotalTimeLimit.toString(),
                ),
            )
            snapshot = engine.fail("Time is over")
            statusText = strings.text("game.status.time_over")
            reportMatchFinishedIfNeeded(won = false)
        }
    }

    LaunchedEffect(snapshot.phase) {
        if (snapshot.phase == MatchPhase.LOST) {
            AppLog.warn(
                tag = logTag,
                message = "match phase became lost",
                attributes = mapOf("attempts" to snapshot.attempts.size.toString()),
            )
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

    fun isPositionLocked(position: Int): Boolean {
        return (0..9).any { digit -> board[digit][position] == CellMark.LOCK_YES }
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

    fun inferFromAttemptConstraints(guess: String, score: Int) {
        val lockedMatches = guess.indices.filter { position ->
            val digit = guess[position].digitToInt()
            board[digit][position] == CellMark.LOCK_YES
        }
        val hasConflictingLockedPosition = guess.indices.any { position ->
            isPositionLocked(position) && position !in lockedMatches
        }
        if (hasConflictingLockedPosition) return

        val unresolvedCandidates = guess.indices.filter { position ->
            val mark = board[guess[position].digitToInt()][position]
            !isPositionLocked(position) &&
                mark != CellMark.NO &&
                mark != CellMark.AUTO_NO
        }
        val unresolvedMatchCount = score - lockedMatches.size
        if (unresolvedMatchCount !in 0..unresolvedCandidates.size) return

        when {
            unresolvedMatchCount == 0 -> {
                unresolvedCandidates.forEach { position ->
                    lockNo(guess[position].digitToInt(), position)
                }
            }

            unresolvedMatchCount == unresolvedCandidates.size -> {
                unresolvedCandidates.forEach { position ->
                    setLockedYes(guess[position].digitToInt(), position)
                }
            }
        }
    }

    fun tryAutoSolve() {
        if (!effectiveAutoExclude) return
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

    fun guessSlots(): String = currentGuess.padEnd(params.lenSecret, ' ').take(params.lenSecret)

    fun completedGuessOrNull(): String? {
        val guess = guessSlots()
        return guess.takeIf { value -> value.all { it.isDigit() } }
    }

    fun addDigitLeftToRight(digit: Char) {
        val chars = guessSlots().toCharArray()
        val index = chars.indexOfFirst { it == ' ' }
        if (index != -1) {
            chars[index] = digit
            currentGuess = String(chars)
        }
    }

    fun backspaceGuess() {
        val chars = guessSlots().toCharArray()
        val index = chars.indices
            .reversed()
            .firstOrNull { position -> chars[position] != ' ' && !isPositionLocked(position) }
        if (index != null) {
            chars[index] = ' '
            currentGuess = String(chars)
        }
    }

    fun hintCount(mode: HintMode): Int = when (mode) {
        HintMode.OPEN_POSITION -> openPositionHints
        HintMode.CHECK_DIGIT -> checkDigitHints
        HintMode.CHECK_POSITION -> checkPositionHints
    }

    fun visibleHintCount(mode: HintMode): Int {
        val rewardedAllowance = if (rewardedHintAllowance == mode) 1 else 0
        return hintCount(mode) + rewardedAllowance
    }

    fun hintStockType(mode: HintMode): HintStockType = when (mode) {
        HintMode.OPEN_POSITION -> HintStockType.OPEN_POSITION
        HintMode.CHECK_DIGIT -> HintStockType.CHECK_DIGIT
        HintMode.CHECK_POSITION -> HintStockType.CHECK_POSITION
    }

    fun consumeHintOrShowMessage(mode: HintMode): Boolean {
        if (infiniteHintsEnabled) return true
        if (rewardedHintAllowance == mode) {
            rewardedHintAllowance = null
            return true
        }
        if (hintCount(mode) <= 0) {
            statusText = strings.text("game.status.no_hints")
            return false
        }
        val consumed = when (mode) {
            HintMode.OPEN_POSITION -> onConsumeOpenPositionHint()
            HintMode.CHECK_DIGIT -> onConsumeCheckDigitHint()
            HintMode.CHECK_POSITION -> onConsumeCheckPositionHint()
        }
        if (!consumed) {
            statusText = strings.text("game.status.no_hints")
            return false
        }
        return true
    }

    fun selectHintMode(mode: HintMode) {
        if (!infiniteHintsEnabled && visibleHintCount(mode) <= 0) {
            pendingRewardedHintMode = mode
            statusText = strings.text("game.status.watch_ad_for_hint")
            return
        }
        selectedHintMode = if (selectedHintMode == mode) null else mode
        statusText = when (selectedHintMode) {
            HintMode.CHECK_POSITION -> strings.text("game.status.select_table_cell")
            HintMode.OPEN_POSITION -> strings.text("game.status.select_slot")
            HintMode.CHECK_DIGIT -> strings.text("game.status.select_digit")
            null -> strings.text("game.status.default")
        }
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
                    statusText = outcome.message ?: strings.text("game.status.hint_unavailable")
                } else {
                    if (result.isMatch) {
                        setLockedYes(result.digit, result.position)
                        statusText = tr("game.status.hint_match_here", "digit" to result.digit)
                    } else {
                        lockNo(result.digit, result.position)
                        statusText = tr("game.status.hint_no_match_here", "digit" to result.digit)
                    }
                }
                selectedHintMode = null
            }

            HintMode.CHECK_DIGIT -> {
                statusText = strings.text("game.status.select_digit")
            }

            HintMode.OPEN_POSITION -> {
                statusText = strings.text("game.status.select_slot")
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
            statusText = outcome.message ?: strings.text("game.status.hint_unavailable")
        } else {
            setLockedYes(result.digit, result.position)
            statusText = tr(
                "game.status.hint_position_contains",
                "position" to (result.position + 1),
                "digit" to result.digit
            )
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
                statusText = outcome.message ?: strings.text("game.status.hint_unavailable")
            } else {
                if (result.count == 0 && effectiveAutoExclude) {
                    lockDigitAsImpossibleEverywhere(result.digit)
                }
                digitHintDialogText = tr(
                    "game.status.hint_digit_count",
                    "digit" to result.digit,
                    "count" to result.count
                )
                statusText = strings.text("game.status.hint_applied")
            }
            selectedHintMode = null
            return
        }

        if (guessSlots().any { it == ' ' }) {
            addDigitLeftToRight(digit)
        }
    }

    fun handleBoostUse(mode: BoostMode) {
        when (mode) {
            BoostMode.EXTRA_MOVES -> {
                if (extraMovesBoosts <= 0 || !onConsumeExtraMovesBoost()) {
                    statusText = strings.text("game.status.no_move_boosts")
                    return
                }
                snapshot = engine.grantExtraMoves(extraMovesPerBoost)
                bonusMoves += extraMovesPerBoost
                extraMovesBoostUses += 1
                statusText = tr("game.status.moves_added", "count" to extraMovesPerBoost)
            }

            BoostMode.EXTRA_TIME -> {
                if (extraTimeBoosts <= 0 || !onConsumeExtraTimeBoost()) {
                    statusText = strings.text("game.status.no_time_boosts")
                    return
                }
                bonusTimeSeconds += extraTimeSecondsPerBoost
                extraTimeBoostUses += 1
                statusText = tr("game.status.time_added", "minutes" to (extraTimeSecondsPerBoost / 60))
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
        statusText = strings.text("game.status.new_secret")
    }

    fun submitGuess() {
        if (!inputEnabled) {
            statusText = strings.text("game.status.wait_opponent")
            return
        }
        val guess = completedGuessOrNull()
        if (guess == null) {
            statusText = tr("game.status.enter_digits", "count" to params.lenSecret)
            return
        }

        val previousAttempts = snapshot.attempts
        val newSnapshot = engine.submit(guess)
        snapshot = newSnapshot

        if (newSnapshot.attempts.size == previousAttempts.size) {
            statusText = when (newSnapshot.message) {
                "All digits cannot be the same" -> strings.text("game.validation.all_same_digits")
                "Duplicate digits are forbidden" -> strings.text("game.validation.duplicate_digits")
                "Adjacent duplicates are forbidden" -> strings.text("game.validation.adjacent_duplicates")
                "Triple duplicates are forbidden" -> strings.text("game.validation.triple_duplicates")
                "Only digits are allowed" -> strings.text("game.validation.only_digits")
                else -> newSnapshot.message ?: strings.text("game.status.attempt_not_accepted")
            }
            return
        }

        val lastAttempt = newSnapshot.attempts.last()
        onGuessResolved(guess, lastAttempt.score, lastAttempt.isWin)
        if (lastAttempt.score == 0 && effectiveAutoExclude) {
            guess.forEachIndexed { index, ch ->
                lockNo(ch.digitToInt(), index)
            }
        }

        if (effectiveAutoExclude) {
            inferFromAttemptConstraints(guess, lastAttempt.score)
        }

        if (effectiveAutoExclude && newSnapshot.attempts.size >= 2) {
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
                statusText = strings.text("game.status.solved_new_secret")
                reportMatchFinishedIfNeeded(won = true)
                if (autoRestartOnWin) {
                    resetGame()
                }
            }

            MatchPhase.LOST -> {
                statusText = newSnapshot.message ?: strings.text("game.status.no_attempts_left")
            }

            else -> {
                statusText = tr("game.status.matches", "count" to lastAttempt.score)
                rebuildGuessFromBoard()
                turnElapsedSeconds = 0
            }
        }
    }

    val attemptLines = snapshot.attempts.map { "${it.guess} -> ${it.score}" }
    val statusIsError = statusText in setOf(
        strings.text("game.validation.all_same_digits"),
        strings.text("game.validation.duplicate_digits"),
        strings.text("game.validation.adjacent_duplicates"),
        strings.text("game.validation.triple_duplicates"),
        strings.text("game.validation.only_digits"),
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                tonalElevation = 2.dp,
                color = Color.White.copy(alpha = 0.82f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.76f))
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
                    statusText = statusText,
                    statusIsError = statusIsError,
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
                    shape = RoundedCornerShape(22.dp),
                    tonalElevation = 2.dp,
                    color = Color.White.copy(alpha = 0.82f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.76f))
                ) {
                    AttemptsModule(attempts = attemptLines)
                }

                Surface(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(22.dp),
                    tonalElevation = 2.dp,
                    color = Color.White.copy(alpha = 0.82f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.76f))
                ) {
                    VariantsModule(
                        lenSecret = params.lenSecret,
                        board = board,
                        onCellClick = ::handleTableCellClick
                    )
                }
            }

            if (params.useHints || params.useBoosts) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(22.dp),
                    tonalElevation = 2.dp,
                    color = Color.White.copy(alpha = 0.82f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.76f))
                ) {
                    HelpersModule(
                        selectedHintMode = selectedHintMode,
                        openPositionHints = visibleHintCount(HintMode.OPEN_POSITION),
                        checkDigitHints = visibleHintCount(HintMode.CHECK_DIGIT),
                        checkPositionHints = visibleHintCount(HintMode.CHECK_POSITION),
                        infiniteHintsEnabled = infiniteHintsEnabled,
                        extraMovesBoosts = extraMovesBoosts,
                        extraTimeBoosts = extraTimeBoosts,
                        extraMovesPerBoost = extraMovesPerBoost,
                        extraTimeSecondsPerBoost = extraTimeSecondsPerBoost,
                        onHintSelect = { hintMode ->
                            selectHintMode(hintMode)
                        },
                        onUseExtraMoves = { handleBoostUse(BoostMode.EXTRA_MOVES) },
                        onUseExtraTime = { handleBoostUse(BoostMode.EXTRA_TIME) }
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                shape = RoundedCornerShape(22.dp),
                tonalElevation = 2.dp,
                color = Color.White.copy(alpha = 0.82f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.76f))
            ) {
                ToolsModule(
                    selectedTool = selectedTool,
                    autoExclude = effectiveAutoExclude,
                    autoModeAvailable = autoModeAvailable,
                    onToolSelect = { selectedTool = it },
                    onToggleAutoExclude = {
                        if (autoModeAvailable) {
                            autoExclude = !autoExclude
                            statusText = if (autoExclude) {
                                strings.text("game.status.auto_enabled")
                            } else {
                                strings.text("game.status.auto_disabled")
                            }
                        } else {
                            statusText = strings.text("game.status.auto_requires_pro")
                        }
                    }
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                tonalElevation = 2.dp,
                color = Color.White.copy(alpha = 0.82f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.76f))
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
                    canCheck = completedGuessOrNull() != null
                )
            }
        }

        pendingRewardedHintMode?.let { mode ->
            AlertDialog(
                onDismissRequest = { pendingRewardedHintMode = null },
                title = { Text(strings.text("game.dialog.bonus_hint.title")) },
                text = { Text(strings.text("game.dialog.bonus_hint.text")) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingRewardedHintMode = null
                            if (onWatchRewardedHintAd(hintStockType(mode))) {
                                rewardedHintAllowance = mode
                                selectedHintMode = mode
                                statusText = strings.text("game.status.bonus_hint_ready")
                            } else {
                                statusText = strings.text("game.status.bonus_not_granted")
                            }
                        }
                    ) {
                        Text(strings.text("game.action.watch"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRewardedHintMode = null }) {
                        Text(strings.text("game.action.cancel"))
                    }
                }
            )
        }

        digitHintDialogText?.let { dialogText ->
            AlertDialog(
                onDismissRequest = { digitHintDialogText = null },
                confirmButton = {
                    TextButton(onClick = { digitHintDialogText = null }) {
                        Text(strings.text("game.action.ok"))
                    }
                },
                title = {
                    Text(strings.text("game.dialog.hint.title"))
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
    statusText: String,
    statusIsError: Boolean,
) {
    val strings = LocalAppStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
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
            InfoChip(strings.text("game.top.moves"), moveValue(movesDone, totalMovesLimit), Modifier.weight(1f))
            InfoChip(strings.text("game.top.total"), timerValue(elapsedSeconds, totalTimeLimitSeconds), Modifier.weight(1f))
            InfoChip(strings.text("game.top.turn"), timerValue(turnElapsedSeconds, params.timeMove), Modifier.weight(1f))
        }

        Text(
            text = statusText,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("game-status"),
            style = MaterialTheme.typography.bodySmall,
            color = if (statusIsError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (statusIsError) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
        )

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
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
        color = Color.White.copy(alpha = 0.84f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.74f))
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
internal fun AttemptsModule(
    attempts: List<String>,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val listState = rememberLazyListState()

    LaunchedEffect(attempts.size) {
        if (attempts.isNotEmpty()) {
            listState.animateScrollToItem(attempts.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        if (attempts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = strings.text("game.attempts.empty"),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(attempts) { index, line ->
                    Surface(
                        modifier = Modifier.testTag("game-attempt-${index + 1}"),
                        shape = RoundedCornerShape(14.dp),
                        tonalElevation = 1.dp,
                        color = Color.White.copy(alpha = 0.78f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.72f))
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
private fun HelpersModule(
    selectedHintMode: HintMode?,
    openPositionHints: Int,
    checkDigitHints: Int,
    checkPositionHints: Int,
    infiniteHintsEnabled: Boolean,
    extraMovesBoosts: Int,
    extraTimeBoosts: Int,
    extraMovesPerBoost: Int,
    extraTimeSecondsPerBoost: Int,
    onHintSelect: (HintMode) -> Unit,
    onUseExtraMoves: () -> Unit,
    onUseExtraTime: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HintButton(
            hintMode = HintMode.OPEN_POSITION,
            count = if (infiniteHintsEnabled) null else openPositionHints,
            selected = selectedHintMode == HintMode.OPEN_POSITION,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            onClick = { onHintSelect(HintMode.OPEN_POSITION) }
        )
        HintButton(
            hintMode = HintMode.CHECK_DIGIT,
            count = if (infiniteHintsEnabled) null else checkDigitHints,
            selected = selectedHintMode == HintMode.CHECK_DIGIT,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            onClick = { onHintSelect(HintMode.CHECK_DIGIT) }
        )
        HintButton(
            hintMode = HintMode.CHECK_POSITION,
            count = if (infiniteHintsEnabled) null else checkPositionHints,
            selected = selectedHintMode == HintMode.CHECK_POSITION,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            onClick = { onHintSelect(HintMode.CHECK_POSITION) }
        )
        BoostButton(
            mode = BoostMode.EXTRA_MOVES,
            count = extraMovesBoosts,
            label = "+$extraMovesPerBoost",
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            onClick = onUseExtraMoves
        )
        BoostButton(
            mode = BoostMode.EXTRA_TIME,
            count = extraTimeBoosts,
            label = "+${extraTimeSecondsPerBoost / 60}:${(extraTimeSecondsPerBoost % 60).toString().padStart(2, '0')}",
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            onClick = onUseExtraTime
        )
    }
}

@Composable
private fun HintButton(
    hintMode: HintMode,
    count: Int?,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val strings = LocalAppStrings.current
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) Color(0xFFD7E5FF) else Color.White.copy(alpha = 0.76f),
            contentColor = Color(0xFF23253A)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) Color(0xFF4C6FFF) else Color.White.copy(alpha = 0.72f)
        )
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(hintMode.iconRes),
                contentDescription = when (hintMode) {
                    HintMode.OPEN_POSITION -> strings.text("game.hint.open_position")
                    HintMode.CHECK_DIGIT -> strings.text("game.hint.check_digit")
                    HintMode.CHECK_POSITION -> strings.text("game.hint.check_position")
                },
                modifier = Modifier.size(18.dp),
                tint = Color.Unspecified
            )
            Text(
                text = count?.toString() ?: "∞",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun BoostButton(
    mode: BoostMode,
    count: Int,
    label: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = Color.White.copy(alpha = 0.76f),
            contentColor = Color(0xFF23253A)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.72f))
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(mode.iconRes),
                contentDescription = mode.contentDescription,
                modifier = Modifier.size(18.dp),
                tint = Color.Unspecified
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun AutoModeButton(
    autoExclude: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val strings = LocalAppStrings.current
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (autoExclude) Color(0xFFD7E5FF) else Color.White.copy(alpha = 0.76f),
            contentColor = Color(0xFF23253A)
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (autoExclude) 2.dp else 1.dp,
            color = if (autoExclude) Color(0xFF4C6FFF) else Color.White.copy(alpha = 0.72f)
        )
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = strings.text("game.auto_mode")
            )
            Text(
                text = when {
                    !enabled -> strings.text("game.auto_mode.pro")
                    autoExclude -> strings.text("game.auto_mode.auto")
                    else -> strings.text("game.auto_mode.manual")
                },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ToolsModule(
    selectedTool: TableTool,
    autoExclude: Boolean,
    autoModeAvailable: Boolean,
    onToggleAutoExclude: () -> Unit,
    onToolSelect: (TableTool) -> Unit
) {
    val strings = LocalAppStrings.current
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolButton(
            title = strings.text("game.tool.no"),
            color = Color(0xFFE57373),
            selected = selectedTool == TableTool.NO,
            modifier = Modifier.weight(1f),
            onClick = { onToolSelect(TableTool.NO) }
        )
        ToolButton(
            title = strings.text("game.tool.maybe"),
            color = Color(0xFFFFEE58),
            selected = selectedTool == TableTool.MAYBE,
            modifier = Modifier.weight(1.05f),
            onClick = { onToolSelect(TableTool.MAYBE) }
        )
        ToolButton(
            title = strings.text("game.tool.yes"),
            color = Color(0xFF81C784),
            selected = selectedTool == TableTool.YES,
            modifier = Modifier.weight(1f),
            onClick = { onToolSelect(TableTool.YES) }
        )
        AutoModeButton(
            autoExclude = autoExclude,
            enabled = autoModeAvailable,
            modifier = Modifier.weight(1f),
            onClick = onToggleAutoExclude
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
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.72f))
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
    val strings = LocalAppStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = strings.text("game.combination"), style = MaterialTheme.typography.titleSmall)
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
                Text(strings.text("game.action.reset"))
            }

            Button(
                onClick = onCheck,
                enabled = canCheck && inputEnabled,
                modifier = Modifier.weight(1.3f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(strings.text("game.action.confirm"))
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
                    .testTag("game-guess-slot-${index + 1}")
                    .clickable(enabled = hintOpenPositionSelected && inputEnabled) { onSlotClick(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (value.isBlank()) " " else value,
                    modifier = Modifier.testTag("game-guess-value-${index + 1}"),
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
    val strings = LocalAppStrings.current
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
                    .height(38.dp)
                    .testTag("game-digit-$digit"),
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
                        contentDescription = strings.text("game.action.delete")
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
    val strings = LocalAppStrings.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = strings.text("game.ad_slot"),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = strings.text("game.debug.secret").replace("{value}", debugSecret),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFB71C1C),
            textAlign = TextAlign.Center
        )
        Text(
            text = strings.text("game.debug.hints")
                .replace("{open}", openPositionHints.toString())
                .replace("{digit}", checkDigitHints.toString())
                .replace("{position}", checkPositionHints.toString()),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = strings.text("game.debug.boosts")
                .replace("{moves}", extraMovesBoosts.toString())
                .replace("{time}", extraTimeBoosts.toString()),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center
        )
        FilledTonalButton(
            onClick = onAddHintsClick,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(strings.text("game.debug.add_hints"))
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
