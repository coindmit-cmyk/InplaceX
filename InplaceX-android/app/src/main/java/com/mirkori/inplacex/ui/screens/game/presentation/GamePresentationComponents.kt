package com.mirkori.inplacex.ui.screens.game.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.screens.game.state.GameFieldEvent
import com.mirkori.inplacex.ui.screens.game.state.GameFieldHintMode
import com.mirkori.inplacex.ui.screens.game.state.GameFieldManualMark
import com.mirkori.inplacex.ui.screens.game.state.GameFieldManualMarkType
import com.mirkori.inplacex.ui.screens.game.state.GameFieldMode
import com.mirkori.inplacex.ui.screens.game.state.GameFieldTool
import com.mirkori.inplacex.ui.screens.game.state.GameFieldUiState
import com.mirkori.inplacex.ui.theme.InplaceXColors

/** Callbacks supplied by the route that owns the state and external game services. */
data class GamePresentationCallbacks(
    val onEvent: (GameFieldEvent) -> Unit,
    val onBack: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onExtraMovesBoostRequested: () -> Unit = {},
    val onExtraTimeBoostRequested: () -> Unit = {},
)

@Composable
fun GamePresentationLayout(
    uiState: GameFieldUiState,
    callbacks: GamePresentationCallbacks,
    modifier: Modifier = Modifier,
    debugSlot: (@Composable () -> Unit)? = null,
) {
    val active = isInputEnabled(uiState.match.phase)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PresentationCard {
            GameTopPanel(
                uiState = uiState,
                onBack = callbacks.onBack,
                onOpenSettings = callbacks.onOpenSettings,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PresentationCard(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight(),
            ) {
                GameAttemptsPanel(uiState = uiState)
            }
            PresentationCard(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight(),
            ) {
                GameAnalysisPanel(
                    uiState = uiState,
                    enabled = active,
                    onCellClick = { digit, position ->
                        callbacks.onEvent(
                            GameFieldEvent.ManualMarkChanged(
                                position = position,
                                symbol = digit,
                                type = uiState.tools.selectedTool.toManualMarkType(),
                            ),
                        )
                    },
                )
            }
        }

        if (uiState.parameters.hintsEnabled || uiState.parameters.boostsEnabled) {
            PresentationCard(modifier = Modifier.height(48.dp)) {
                GameHelpersPanel(
                    uiState = uiState,
                    onHintSelected = { callbacks.onEvent(GameFieldEvent.HintSelected(it)) },
                    onExtraMovesBoostRequested = callbacks.onExtraMovesBoostRequested,
                    onExtraTimeBoostRequested = callbacks.onExtraTimeBoostRequested,
                )
            }
        }

        PresentationCard(modifier = Modifier.height(44.dp)) {
            GameToolsPanel(
                uiState = uiState,
                onToolSelected = { callbacks.onEvent(GameFieldEvent.ToolSelected(it)) },
                onAutoExcludeChanged = { callbacks.onEvent(GameFieldEvent.AutoExcludeChanged(it)) },
            )
        }

        PresentationCard {
            GameInputPanel(
                uiState = uiState,
                enabled = active,
                onEvent = callbacks.onEvent,
            )
        }

        debugSlot?.let { slot ->
            PresentationCard(modifier = Modifier.fillMaxWidth()) {
                slot()
            }
        }
    }
}

@Composable
private fun PresentationCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 2.dp,
    ) {
        content()
    }
}

@Composable
fun GameTopPanel(
    uiState: GameFieldUiState,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val parameters = uiState.parameters
    val mode = when (parameters.mode) {
        GameFieldMode.RACE -> strings.text("mode.pve.title")
        GameFieldMode.DUEL -> strings.text("mode.pvp.title")
    }

    Column(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(strings.text("top.back")) }
            Text(
                text = mode,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onOpenSettings) { Text(strings.text("top.settings")) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GameInfoChip(
                label = strings.text("game.top.moves"),
                value = "${uiState.match.attempts.size}/${parameters.attemptLimit + uiState.counters.bonusMoves}",
                modifier = Modifier.weight(1f),
            )
            GameInfoChip(
                label = strings.text("game.top.total"),
                value = timerValue(uiState.timers.elapsedSeconds, parameters.totalTimeLimitSeconds + uiState.timers.bonusTimeSeconds),
                modifier = Modifier.weight(1f),
            )
            GameInfoChip(
                label = strings.text("game.top.turn"),
                value = timerValue(uiState.timers.turnElapsedSeconds, parameters.turnTimeLimitSeconds),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = statusText(uiState),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("game-status"),
            style = MaterialTheme.typography.bodySmall,
            color = if (uiState.status is com.mirkori.inplacex.ui.screens.game.state.GameFieldStatus.EngineFeedback) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
        )
    }
}

@Composable
private fun GameInfoChip(label: String, value: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun GameAttemptsPanel(uiState: GameFieldUiState, modifier: Modifier = Modifier) {
    val strings = LocalAppStrings.current
    Column(modifier = modifier.padding(8.dp)) {
        Text(strings.text("game.attempts.title"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
        Spacer(Modifier.height(6.dp))
        if (uiState.match.attempts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(strings.text("game.attempts.empty"), textAlign = TextAlign.Center)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                uiState.match.attempts.forEachIndexed { index, attempt ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("game-attempt-${index + 1}"),
                        shape = RoundedCornerShape(14.dp),
                        tonalElevation = 1.dp,
                    ) {
                        Text(
                            text = "${attempt.guess} → ${attempt.score}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GameAnalysisPanel(
    uiState: GameFieldUiState,
    enabled: Boolean,
    onCellClick: (digit: Char, position: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.padding(6.dp)) {
        val columns = uiState.parameters.codeLength
        val verticalGap = 3.dp
        val horizontalGap = 3.dp
        val cellSize = minOf(
            (maxWidth - horizontalGap * (columns - 1)) / columns,
            (maxHeight - verticalGap * 9) / 10,
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(10) { digit ->
                Row(horizontalArrangement = Arrangement.spacedBy(horizontalGap)) {
                    repeat(columns) { position ->
                        val symbol = ('0'.code + digit).toChar()
                        val mark = analysisMarkFor(uiState.manualMarks, symbol, position)
                        Box(
                            modifier = Modifier
                                .size(cellSize)
                                .background(mark.color, RoundedCornerShape(6.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                                .clickable(enabled = enabled) { onCellClick(symbol, position) }
                                .testTag("game-analysis-$digit-${position + 1}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(symbol.toString(), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GameHelpersPanel(
    uiState: GameFieldUiState,
    onHintSelected: (GameFieldHintMode?) -> Unit,
    onExtraMovesBoostRequested: () -> Unit,
    onExtraTimeBoostRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val selected = uiState.tools.selectedHint
    Row(
        modifier = modifier.padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (uiState.parameters.hintsEnabled) {
            GameHelperButton(
                label = strings.text("game.hint.open_position"),
                selected = selected == GameFieldHintMode.OPEN_POSITION,
                modifier = Modifier.weight(1f),
            ) { onHintSelected(selected.toggle(GameFieldHintMode.OPEN_POSITION)) }
            GameHelperButton(
                label = strings.text("game.hint.check_digit"),
                selected = selected == GameFieldHintMode.CHECK_DIGIT,
                modifier = Modifier.weight(1f),
            ) { onHintSelected(selected.toggle(GameFieldHintMode.CHECK_DIGIT)) }
            GameHelperButton(
                label = strings.text("game.hint.check_position"),
                selected = selected == GameFieldHintMode.CHECK_POSITION,
                modifier = Modifier.weight(1f),
            ) { onHintSelected(selected.toggle(GameFieldHintMode.CHECK_POSITION)) }
        }
        if (uiState.parameters.boostsEnabled) {
            GameHelperButton(
                label = strings.text("game.boost.add_moves"),
                selected = false,
                modifier = Modifier.weight(1f),
                onClick = onExtraMovesBoostRequested,
            )
            GameHelperButton(
                label = strings.text("game.boost.add_time"),
                selected = false,
                modifier = Modifier.weight(1f),
                onClick = onExtraTimeBoostRequested,
            )
        }
    }
}

@Composable
private fun GameHelperButton(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) InplaceXColors.Cobalt.copy(alpha = 0.18f) else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
fun GameToolsPanel(
    uiState: GameFieldUiState,
    onToolSelected: (GameFieldTool) -> Unit,
    onAutoExcludeChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    Row(
        modifier = modifier.padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GameToolButton(strings.text("game.tool.no"), GameFieldTool.NO, uiState.tools.selectedTool, Modifier.weight(1f), onToolSelected)
        GameToolButton(strings.text("game.tool.maybe"), GameFieldTool.MAYBE, uiState.tools.selectedTool, Modifier.weight(1f), onToolSelected)
        GameToolButton(strings.text("game.tool.yes"), GameFieldTool.YES, uiState.tools.selectedTool, Modifier.weight(1f), onToolSelected)
        if (uiState.parameters.autoModeAvailable) {
            FilledTonalButton(
                onClick = { onAutoExcludeChanged(!uiState.tools.autoExcludeEnabled) },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (uiState.tools.autoExcludeEnabled) strings.text("game.auto_mode.auto") else strings.text("game.auto_mode.manual"),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun GameToolButton(
    label: String,
    tool: GameFieldTool,
    selectedTool: GameFieldTool,
    modifier: Modifier,
    onToolSelected: (GameFieldTool) -> Unit,
) {
    FilledTonalButton(
        onClick = { onToolSelected(tool) },
        modifier = modifier,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (tool == selectedTool) InplaceXColors.Cyan.copy(alpha = 0.24f) else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
}

@Composable
fun GameInputPanel(
    uiState: GameFieldUiState,
    enabled: Boolean,
    onEvent: (GameFieldEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    Column(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(strings.text("game.combination"), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            uiState.input.slots.forEachIndexed { index, value ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                        .testTag("game-guess-slot-${index + 1}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = value?.toString() ?: " ",
                        modifier = Modifier.testTag("game-guess-value-${index + 1}"),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            "1234567890".forEach { digit ->
                FilledTonalButton(
                    onClick = { onEvent(GameFieldEvent.DigitEntered(digit)) },
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .testTag("game-digit-$digit"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) { Text(digit.toString(), style = MaterialTheme.typography.labelMedium) }
            }
            FilledTonalButton(
                onClick = { onEvent(GameFieldEvent.BackspacePressed) },
                enabled = enabled,
                modifier = Modifier.weight(1f).height(38.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("←") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { onEvent(GameFieldEvent.MatchRestarted) },
                modifier = Modifier.weight(1f),
            ) { Text(strings.text("game.action.reset")) }
            Button(
                onClick = { onEvent(GameFieldEvent.GuessSubmitted) },
                enabled = enabled && uiState.input.isComplete,
                modifier = Modifier.weight(1.3f),
            ) { Text(strings.text("game.action.confirm")) }
        }
    }
}

internal fun analysisMarkFor(
    marks: List<GameFieldManualMark>,
    symbol: Char,
    position: Int,
): GameFieldManualMarkType? = marks.lastOrNull { it.symbol == symbol && it.position == position }?.type

internal fun isInputEnabled(phase: MatchPhase): Boolean = phase == MatchPhase.ACTIVE

private fun GameFieldTool.toManualMarkType(): GameFieldManualMarkType = when (this) {
    GameFieldTool.NO -> GameFieldManualMarkType.NO
    GameFieldTool.MAYBE -> GameFieldManualMarkType.MAYBE
    GameFieldTool.YES -> GameFieldManualMarkType.YES
}

private fun GameFieldHintMode?.toggle(target: GameFieldHintMode): GameFieldHintMode? =
    if (this == target) null else target

private val GameFieldManualMarkType?.color: Color
    get() = when (this) {
        GameFieldManualMarkType.NO -> InplaceXColors.Coral.copy(alpha = 0.30f)
        GameFieldManualMarkType.MAYBE -> InplaceXColors.Amber.copy(alpha = 0.35f)
        GameFieldManualMarkType.YES -> InplaceXColors.Mint.copy(alpha = 0.35f)
        null -> Color.Transparent
    }

@Composable
private fun statusText(uiState: GameFieldUiState): String {
    val strings = LocalAppStrings.current
    return when (val status = uiState.status) {
        is com.mirkori.inplacex.ui.screens.game.state.GameFieldStatus.AttemptAccepted ->
            strings.text("game.status.matches").replace("{count}", status.attempt.score.toString())
        is com.mirkori.inplacex.ui.screens.game.state.GameFieldStatus.EngineFeedback ->
            strings.text("game.status.attempt_not_accepted")
        com.mirkori.inplacex.ui.screens.game.state.GameFieldStatus.InputIncomplete ->
            strings.text("game.status.enter_digits").replace("{count}", uiState.parameters.codeLength.toString())
        com.mirkori.inplacex.ui.screens.game.state.GameFieldStatus.Idle -> strings.text("game.status.default")
    }
}

private fun timerValue(elapsedSeconds: Int, limitSeconds: Int): String {
    val shown = if (limitSeconds > 0) (limitSeconds - elapsedSeconds).coerceAtLeast(0) else elapsedSeconds
    return "%02d:%02d".format(shown / 60, shown % 60)
}
