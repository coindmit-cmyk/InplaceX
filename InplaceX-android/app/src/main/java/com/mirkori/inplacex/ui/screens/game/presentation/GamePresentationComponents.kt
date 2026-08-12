package com.mirkori.inplacex.ui.screens.game.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.R
import com.mirkori.inplacex.core.analysis.ProvenFact
import com.mirkori.inplacex.core.engine.GuessValidationReason
import com.mirkori.inplacex.core.match.MatchFeedback
import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.feedback.AppHapticCue
import com.mirkori.inplacex.platform.feedback.AppSoundCue
import com.mirkori.inplacex.platform.feedback.LocalAppFeedbackRuntime
import com.mirkori.inplacex.ui.screens.game.state.GameFieldEvent
import com.mirkori.inplacex.ui.screens.game.state.GameFieldHintMode
import com.mirkori.inplacex.ui.screens.game.state.GameFieldManualMark
import com.mirkori.inplacex.ui.screens.game.state.GameFieldManualMarkType
import com.mirkori.inplacex.ui.screens.game.state.GameFieldMode
import com.mirkori.inplacex.ui.screens.game.state.GameFieldNotice
import com.mirkori.inplacex.ui.screens.game.state.GameFieldStatus
import com.mirkori.inplacex.ui.screens.game.state.GameFieldTool
import com.mirkori.inplacex.ui.screens.game.state.GameFieldUiState
import com.mirkori.inplacex.ui.common.AnalysisCellVisualState
import com.mirkori.inplacex.ui.common.CompactAttemptRow
import com.mirkori.inplacex.ui.common.WarmAnalysisCell
import com.mirkori.inplacex.ui.common.WarmPanel
import com.mirkori.inplacex.ui.common.WarmPrimaryButton
import com.mirkori.inplacex.ui.common.WarmSecondaryButton
import com.mirkori.inplacex.ui.common.WarmSegmentButton
import com.mirkori.inplacex.ui.theme.FinalUiColors
import com.mirkori.inplacex.ui.theme.FinalUiDimens
import com.mirkori.inplacex.ui.theme.GameFieldLayoutMetrics
import com.mirkori.inplacex.ui.theme.InplaceXColors
import com.mirkori.inplacex.ui.theme.finalGameFieldMetrics

/** Callbacks supplied by the route that owns external inventory, navigation and lifecycle effects. */
data class GamePresentationCallbacks(
    val onEvent: (GameFieldEvent) -> Unit,
    val onBack: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onHintRequested: (GameFieldHintMode) -> Unit = {},
    val onAnalysisCellPressed: (digit: Char, position: Int) -> Unit = { _, _ -> },
    val onGuessSlotPressed: (position: Int) -> Unit = {},
    val onDigitPressed: (digit: Char) -> Unit = {},
    val onExtraMovesBoostRequested: () -> Unit = {},
    val onExtraTimeBoostRequested: () -> Unit = {},
    val onRewardedHintConfirmed: (GameFieldHintMode) -> Unit = {},
    val onRewardedHintDismissed: () -> Unit = {},
)

@Composable
fun GamePresentationLayout(
    uiState: GameFieldUiState,
    callbacks: GamePresentationCallbacks,
    modifier: Modifier = Modifier,
    debugSlot: (@Composable () -> Unit)? = null,
) {
    val active = uiState.route.inputEnabled && isInputEnabled(uiState.match.phase)
    val feedback = LocalAppFeedbackRuntime.current

    LaunchedEffect(uiState.match.phase) {
        when (uiState.match.phase) {
            MatchPhase.WON -> {
                feedback.playSound(AppSoundCue.SUCCESS)
                feedback.performHaptic(AppHapticCue.SUCCESS)
            }
            MatchPhase.LOST -> {
                feedback.playSound(AppSoundCue.FAILURE)
                feedback.performHaptic(AppHapticCue.FAILURE)
            }
            else -> Unit
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val metrics = finalGameFieldMetrics(
            codeLength = uiState.parameters.codeLength,
            compactHeight = maxHeight < 760.dp,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(FinalUiDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(FinalUiDimens.SectionGap),
        ) {
            PresentationCard(modifier = Modifier.defaultMinSize(minHeight = metrics.topPanelMinHeight)) {
                GameTopPanel(uiState = uiState)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(FinalUiDimens.SectionGap),
            ) {
                PresentationCard(
                    modifier = Modifier
                        .weight(metrics.attemptsWeight)
                        .fillMaxHeight(),
                ) {
                    GameAttemptsPanel(uiState = uiState, metrics = metrics)
                }
                PresentationCard(
                    modifier = Modifier
                        .weight(metrics.matrixWeight)
                        .fillMaxHeight(),
                ) {
                    GameAnalysisPanel(
                        uiState = uiState,
                        metrics = metrics,
                        enabled = active,
                        onCellClick = { digit, position ->
                            feedback.playSound(AppSoundCue.TAP)
                            feedback.performHaptic(AppHapticCue.SELECTION)
                            callbacks.onAnalysisCellPressed(digit, position)
                        },
                    )
                }
            }

            if (uiState.parameters.hintsEnabled || uiState.parameters.boostsEnabled) {
                PresentationCard(modifier = Modifier.height(metrics.helpersHeight)) {
                    GameHelpersPanel(
                        uiState = uiState,
                        enabled = active,
                        onHintSelected = { hint ->
                            feedback.playSound(AppSoundCue.TAP)
                            feedback.performHaptic(AppHapticCue.SELECTION)
                            callbacks.onHintRequested(hint)
                        },
                        onExtraMovesBoostRequested = callbacks.onExtraMovesBoostRequested,
                        onExtraTimeBoostRequested = callbacks.onExtraTimeBoostRequested,
                    )
                }
            }

            PresentationCard(modifier = Modifier.height(metrics.toolsHeight)) {
                GameToolsPanel(
                    uiState = uiState,
                    onToolSelected = {
                        feedback.playSound(AppSoundCue.TAP)
                        feedback.performHaptic(AppHapticCue.SELECTION)
                        callbacks.onEvent(GameFieldEvent.ToolSelected(it))
                    },
                    onAutoExcludeChanged = {
                        feedback.playSound(AppSoundCue.CONFIRM)
                        feedback.performHaptic(AppHapticCue.CONFIRM)
                        callbacks.onEvent(GameFieldEvent.AutoExcludeChanged(it))
                    },
                )
            }

            PresentationCard {
                GameInputPanel(
                    uiState = uiState,
                    metrics = metrics,
                    enabled = active,
                    onEvent = { event ->
                        when (event) {
                            GameFieldEvent.GuessSubmitted -> {
                                feedback.playSound(AppSoundCue.CONFIRM)
                                feedback.performHaptic(AppHapticCue.CONFIRM)
                            }
                            else -> {
                                feedback.playSound(AppSoundCue.TAP)
                                feedback.performHaptic(AppHapticCue.SELECTION)
                            }
                        }
                        callbacks.onEvent(event)
                    },
                    onGuessSlotClick = callbacks.onGuessSlotPressed,
                    onDigitClick = { digit ->
                        feedback.playSound(AppSoundCue.TAP)
                        feedback.performHaptic(AppHapticCue.SELECTION)
                        callbacks.onDigitPressed(digit)
                    },
                )
            }

            debugSlot?.let { slot ->
                PresentationCard(modifier = Modifier.fillMaxWidth()) {
                    slot()
                }
            }
        }
    }

    GameDialogs(uiState = uiState, callbacks = callbacks)
}

@Composable
private fun PresentationCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    WarmPanel(modifier = modifier.fillMaxWidth()) {
        content()
    }
}

@Composable
fun GameTopPanel(
    uiState: GameFieldUiState,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val parameters = uiState.parameters
    val defaultMode = when (parameters.mode) {
        GameFieldMode.RACE -> strings.text("mode.pve.title")
        GameFieldMode.DUEL -> strings.text("mode.pvp.title")
    }
    val mode = uiState.route.modeLabel?.takeIf(String::isNotBlank) ?: defaultMode

    Column(
        modifier = modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        uiState.route.turnLabel?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = mode,
                modifier = Modifier.weight(1.15f),
                style = MaterialTheme.typography.titleSmall,
                color = FinalUiColors.WarmText,
                maxLines = 1,
            )
            VerticalDivider(
                modifier = Modifier.height(30.dp),
                color = FinalUiColors.WarmDivider.copy(alpha = 0.46f),
            )
            GameInfoMetric(
                label = strings.text("game.top.moves"),
                value = moveValue(
                    movesDone = uiState.match.attempts.size,
                    configuredLimit = if (uiState.route.movesUnlimited) {
                        null
                    } else {
                        uiState.route.configuredMoveLimit ?: parameters.attemptLimit
                    },
                    bonusMoves = uiState.counters.bonusMoves,
                ),
                modifier = Modifier.weight(0.82f),
            )
            VerticalDivider(
                modifier = Modifier.height(30.dp),
                color = FinalUiColors.WarmDivider.copy(alpha = 0.46f),
            )
            GameInfoMetric(
                label = strings.text("game.top.total"),
                value = timerValue(
                    uiState.timers.elapsedSeconds,
                    if (parameters.totalTimeLimitSeconds > 0) {
                        parameters.totalTimeLimitSeconds + uiState.timers.bonusTimeSeconds
                    } else {
                        0
                    },
                ),
                modifier = Modifier.weight(0.82f),
            )
            VerticalDivider(
                modifier = Modifier.height(30.dp),
                color = FinalUiColors.WarmDivider.copy(alpha = 0.46f),
            )
            GameInfoMetric(
                label = strings.text("game.top.turn"),
                value = timerValue(uiState.timers.turnElapsedSeconds, parameters.turnTimeLimitSeconds),
                modifier = Modifier.weight(0.82f),
            )
        }
        uiState.route.secondaryStatusText?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        Text(
            text = statusText(uiState),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("game-status"),
            style = MaterialTheme.typography.bodySmall,
            color = if (isErrorStatus(uiState.status)) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (isErrorStatus(uiState.status)) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun GameInfoMetric(label: String, value: String, modifier: Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = FinalUiColors.WarmTextMuted,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = FinalUiColors.WarmText,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
fun GameAttemptsPanel(
    uiState: GameFieldUiState,
    metrics: GameFieldLayoutMetrics,
    modifier: Modifier = Modifier,
) {
    GameAttemptList(
        attempts = uiState.match.attempts.map { "${it.guess} -> ${it.score}" },
        textSize = metrics.attemptTextSize,
        rowHeight = metrics.attemptRowHeight,
        modifier = modifier,
    )
}

@Composable
internal fun GameAttemptList(
    attempts: List<String>,
    textSize: androidx.compose.ui.unit.TextUnit = MaterialTheme.typography.bodySmall.fontSize,
    rowHeight: androidx.compose.ui.unit.Dp = 30.dp,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val listState = rememberLazyListState()

    LaunchedEffect(attempts.size) {
        if (attempts.isNotEmpty()) {
            listState.animateScrollToItem(attempts.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(FinalUiDimens.CompactPanelPadding)) {
        Text(strings.text("game.attempts.title"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(3.dp))
        HorizontalDivider(color = FinalUiColors.WarmDivider.copy(alpha = 0.46f))
        Spacer(Modifier.height(3.dp))
        if (attempts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = strings.text("game.attempts.empty"),
                    style = MaterialTheme.typography.bodySmall,
                    color = InplaceXColors.ToyBrown.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(attempts) { index, line ->
                    val parsed = parseAttemptLine(line)
                    CompactAttemptRow(
                        guess = parsed.first,
                        score = parsed.second,
                        latest = index == attempts.lastIndex,
                        contentDescription = line,
                        textSize = textSize,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .testTag("game-attempt-${index + 1}"),
                    )
                }
            }
        }
    }
}

@Composable
fun GameAnalysisPanel(
    uiState: GameFieldUiState,
    metrics: GameFieldLayoutMetrics,
    enabled: Boolean,
    onCellClick: (digit: Char, position: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    BoxWithConstraints(modifier = modifier.padding(6.dp)) {
        val columns = uiState.parameters.codeLength
        val verticalGap = metrics.matrixGap
        val horizontalGap = metrics.matrixGap
        val cellSize = minOf(
            (maxWidth - horizontalGap * (columns - 1)) / columns,
            (maxHeight - verticalGap * 9) / 10,
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(10) { digit ->
                Row(horizontalArrangement = Arrangement.spacedBy(horizontalGap)) {
                    repeat(columns) { position ->
                        val symbol = digit.digitToChar()
                        val editable = enabled && analysisCellEditable(uiState, symbol, position)
                        val cellState = analysisCellStateFor(uiState, symbol, position, enabled)
                        val stateDescription = analysisCellStateText(cellState, strings::text)
                        WarmAnalysisCell(
                            digit = symbol,
                            state = cellState,
                            stateDescription = stateDescription,
                            enabled = editable,
                            contentDescription = strings.text("game.race.matrix.cell")
                                .replace("{digit}", symbol.toString())
                                .replace("{position}", (position + 1).toString())
                                .replace("{state}", stateDescription),
                            digitSize = metrics.matrixDigitSize,
                            radius = metrics.matrixRadius,
                            onClick = { onCellClick(symbol, position) },
                            modifier = Modifier
                                .size(cellSize)
                                .testTag("game-analysis-$digit-${position + 1}"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GameHelpersPanel(
    uiState: GameFieldUiState,
    enabled: Boolean,
    onHintSelected: (GameFieldHintMode) -> Unit,
    onExtraMovesBoostRequested: () -> Unit,
    onExtraTimeBoostRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (uiState.parameters.hintsEnabled) {
            GameHintButton(
                mode = GameFieldHintMode.OPEN_POSITION,
                count = uiState.route.openPositionHints,
                infinite = uiState.route.infiniteHintsEnabled,
                selected = uiState.tools.selectedHint == GameFieldHintMode.OPEN_POSITION,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = onHintSelected,
            )
            GameHintButton(
                mode = GameFieldHintMode.CHECK_DIGIT,
                count = uiState.route.checkDigitHints,
                infinite = uiState.route.infiniteHintsEnabled,
                selected = uiState.tools.selectedHint == GameFieldHintMode.CHECK_DIGIT,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = onHintSelected,
            )
            GameHintButton(
                mode = GameFieldHintMode.CHECK_POSITION,
                count = uiState.route.checkPositionHints,
                infinite = uiState.route.infiniteHintsEnabled,
                selected = uiState.tools.selectedHint == GameFieldHintMode.CHECK_POSITION,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = onHintSelected,
            )
        }
        if (uiState.parameters.boostsEnabled) {
            GameBoostButton(
                iconRes = R.drawable.ic_boost_extra_moves,
                contentDescription = LocalAppStrings.current.text("game.boost.add_moves"),
                label = "+${uiState.route.extraMovesPerBoost}",
                count = uiState.route.extraMovesBoosts,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = onExtraMovesBoostRequested,
            )
            GameBoostButton(
                iconRes = R.drawable.ic_boost_extra_time,
                contentDescription = LocalAppStrings.current.text("game.boost.add_time"),
                label = "+${formatDuration(uiState.route.extraTimeSecondsPerBoost)}",
                count = uiState.route.extraTimeBoosts,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = onExtraTimeBoostRequested,
            )
        }
    }
}

@Composable
private fun GameHintButton(
    mode: GameFieldHintMode,
    count: Int,
    infinite: Boolean,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: (GameFieldHintMode) -> Unit,
) {
    val strings = LocalAppStrings.current
    val label = when (mode) {
        GameFieldHintMode.OPEN_POSITION -> strings.text("game.hint.open_position")
        GameFieldHintMode.CHECK_DIGIT -> strings.text("game.hint.check_digit")
        GameFieldHintMode.CHECK_POSITION -> strings.text("game.hint.check_position")
    }
    val icon = when (mode) {
        GameFieldHintMode.OPEN_POSITION -> R.drawable.ic_hint_open_position
        GameFieldHintMode.CHECK_DIGIT -> R.drawable.ic_hint_check_digit
        GameFieldHintMode.CHECK_POSITION -> R.drawable.ic_hint_check_position
    }
    CompactHelperButton(
        enabled = enabled,
        selected = selected,
        modifier = modifier.fillMaxHeight(),
        onClick = { onClick(mode) },
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            modifier = Modifier.size(18.dp),
            tint = Color.Unspecified,
        )
        Text(
            text = if (infinite) "∞" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun GameBoostButton(
    iconRes: Int,
    contentDescription: String,
    label: String,
    count: Int,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    CompactHelperButton(
        enabled = enabled,
        selected = false,
        modifier = modifier.fillMaxHeight(),
        onClick = onClick,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = Color.Unspecified,
        )
        Text("$label $count", style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun CompactHelperButton(
    enabled: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .background(
                color = if (selected) {
                    FinalUiColors.Primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                },
                shape = shape,
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    FinalUiColors.Primary
                } else {
                    FinalUiColors.WarmBorder.copy(alpha = 0.62f)
                },
                shape = shape,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
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
        GameToolButton(
            strings.text("game.tool.no"),
            GameFieldTool.NO,
            uiState.tools.selectedTool,
            Modifier.weight(1f),
            onToolSelected,
        )
        GameToolButton(
            strings.text("game.tool.maybe"),
            GameFieldTool.MAYBE,
            uiState.tools.selectedTool,
            Modifier.weight(1f),
            onToolSelected,
        )
        GameToolButton(
            strings.text("game.tool.yes"),
            GameFieldTool.YES,
            uiState.tools.selectedTool,
            Modifier.weight(1f),
            onToolSelected,
        )
        WarmSegmentButton(
            label = when {
                !uiState.parameters.autoModeAvailable -> strings.text("game.auto_mode.pro")
                uiState.tools.autoExcludeEnabled -> strings.text("game.auto_mode.auto")
                else -> strings.text("game.auto_mode.manual")
            },
            selected = uiState.tools.autoExcludeEnabled,
            accent = FinalUiColors.StatePro,
            enabled = uiState.parameters.autoModeAvailable,
            onClick = { onAutoExcludeChanged(!uiState.tools.autoExcludeEnabled) },
            modifier = Modifier.weight(1f),
        )
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
    WarmSegmentButton(
        label = label,
        selected = tool == selectedTool,
        accent = tool.color,
        enabled = true,
        onClick = { onToolSelected(tool) },
        modifier = modifier,
    )
}

@Composable
fun GameInputPanel(
    uiState: GameFieldUiState,
    metrics: GameFieldLayoutMetrics,
    enabled: Boolean,
    onEvent: (GameFieldEvent) -> Unit,
    onGuessSlotClick: (Int) -> Unit,
    onDigitClick: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val shownSlots = displayedGuessSlots(uiState)
    val openPositionSelected = uiState.tools.selectedHint == GameFieldHintMode.OPEN_POSITION
    val checkDigitSelected = uiState.tools.selectedHint == GameFieldHintMode.CHECK_DIGIT

    Column(
        modifier = modifier.padding(FinalUiDimens.CompactPanelPadding),
        verticalArrangement = Arrangement.spacedBy(FinalUiDimens.InnerGap),
    ) {
        Text(strings.text("game.combination"), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(metrics.inputSlotGap)) {
            shownSlots.forEachIndexed { index, value ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(metrics.inputSlotHeight)
                        .border(
                            width = if (openPositionSelected) 2.dp else 1.dp,
                            color = if (openPositionSelected) {
                                InplaceXColors.Cobalt
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = RoundedCornerShape(FinalUiDimens.ButtonRadius),
                        )
                        .testTag("game-guess-slot-${index + 1}")
                        .clickable(enabled = enabled && openPositionSelected) {
                            onGuessSlotClick(index)
                        },
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (checkDigitSelected) 2.dp else 0.dp,
                    color = if (checkDigitSelected) InplaceXColors.Cobalt else Color.Transparent,
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            "1234567890".forEach { digit ->
                FilledTonalButton(
                    onClick = { onDigitClick(digit) },
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .height(metrics.keypadHeight)
                        .testTag("game-digit-$digit"),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(digit.toString(), style = MaterialTheme.typography.labelMedium)
                }
            }
            FilledTonalButton(
                onClick = { onEvent(GameFieldEvent.BackspacePressed) },
                enabled = enabled,
                modifier = Modifier.weight(1f).height(metrics.keypadHeight),
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Backspace,
                    contentDescription = strings.text("game.action.delete"),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WarmSecondaryButton(
                label = strings.text("game.action.reset"),
                onClick = { onEvent(GameFieldEvent.MatchRestarted) },
                enabled = uiState.route.inputEnabled,
                modifier = Modifier.weight(1f),
            )
            WarmPrimaryButton(
                label = strings.text("game.action.confirm"),
                onClick = { onEvent(GameFieldEvent.GuessSubmitted) },
                enabled = enabled && shownSlots.all { it != null },
                modifier = Modifier.weight(1.3f),
            )
        }
    }
}

@Composable
private fun GameDialogs(
    uiState: GameFieldUiState,
    callbacks: GamePresentationCallbacks,
) {
    val strings = LocalAppStrings.current
    uiState.route.pendingRewardedHint?.let { mode ->
        AlertDialog(
            onDismissRequest = {
                if (!uiState.route.rewardedHintInFlight) {
                    callbacks.onRewardedHintDismissed()
                }
            },
            title = { Text(strings.text("game.dialog.bonus_hint.title")) },
            text = { Text(strings.text("game.dialog.bonus_hint.text")) },
            confirmButton = {
                TextButton(
                    onClick = { callbacks.onRewardedHintConfirmed(mode) },
                    enabled = !uiState.route.rewardedHintInFlight,
                ) {
                    Text(strings.text("game.action.watch"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = callbacks.onRewardedHintDismissed,
                    enabled = !uiState.route.rewardedHintInFlight,
                ) {
                    Text(strings.text("game.action.cancel"))
                }
            },
        )
    }

    (uiState.status as? GameFieldStatus.HintDigitCount)?.let { status ->
        AlertDialog(
            onDismissRequest = { callbacks.onEvent(GameFieldEvent.NoticeChanged(null)) },
            confirmButton = {
                TextButton(onClick = { callbacks.onEvent(GameFieldEvent.NoticeChanged(null)) }) {
                    Text(strings.text("game.action.ok"))
                }
            },
            title = { Text(strings.text("game.dialog.hint.title")) },
            text = {
                Text(
                    strings.text("game.status.hint_digit_count")
                        .replace("{digit}", status.digit.toString())
                        .replace("{count}", status.count.toString()),
                )
            },
        )
    }
}

internal fun analysisMarkFor(
    marks: List<GameFieldManualMark>,
    symbol: Char,
    position: Int,
): GameFieldManualMarkType? = marks.lastOrNull {
    it.symbol == symbol && it.position == position
}?.type

internal fun parseAttemptLine(line: String): Pair<String, Int> {
    val separator = line.lastIndexOf("->")
    if (separator < 0) return line.trim() to 0
    val guess = line.substring(0, separator).trim()
    val score = line.substring(separator + 2).trim().toIntOrNull() ?: 0
    return guess to score
}

internal fun analysisCellStateText(
    state: AnalysisCellVisualState,
    text: (String) -> String,
): String = when (state) {
    AnalysisCellVisualState.EMPTY -> text("game.race.matrix.state.empty")
    AnalysisCellVisualState.NO -> text("game.race.matrix.state.no")
    AnalysisCellVisualState.MAYBE -> text("game.race.matrix.state.maybe")
    AnalysisCellVisualState.EXACT -> text("game.race.matrix.state.yes")
    AnalysisCellVisualState.LOCKED_NO -> text("game.race.matrix.state.locked_no")
    AnalysisCellVisualState.LOCKED_EXACT -> text("game.race.matrix.state.locked_yes")
    AnalysisCellVisualState.DISABLED -> text("game.race.matrix.state.disabled")
}

internal fun isInputEnabled(phase: MatchPhase): Boolean = phase == MatchPhase.ACTIVE

internal fun displayedGuessSlots(uiState: GameFieldUiState): List<Char?> {
    val exactMatches = effectiveFacts(uiState)
        .asSequence()
        .filter(ProvenFact::isExactMatch)
        .associate { it.position to it.symbol }
    return uiState.input.slots.mapIndexed { position, symbol -> exactMatches[position] ?: symbol }
}

private fun effectiveFacts(uiState: GameFieldUiState): Set<ProvenFact> {
    val inferred = if (uiState.tools.autoExcludeEnabled) {
        uiState.evidence.deduction.provenFacts
    } else {
        emptySet()
    }
    return uiState.evidence.provenFacts + inferred
}

private fun analysisCellStateFor(
    uiState: GameFieldUiState,
    symbol: Char,
    position: Int,
    enabled: Boolean,
): AnalysisCellVisualState {
    lockedCellState(uiState.evidence.provenFacts, symbol, position)?.let { return it }
    if (uiState.tools.autoExcludeEnabled) {
        lockedCellState(uiState.evidence.deduction.provenFacts, symbol, position)?.let { return it }
    }
    analysisMarkFor(uiState.manualMarks, symbol, position)?.let { mark ->
        return when (mark) {
            GameFieldManualMarkType.NO -> AnalysisCellVisualState.NO
            GameFieldManualMarkType.MAYBE -> AnalysisCellVisualState.MAYBE
            GameFieldManualMarkType.YES -> AnalysisCellVisualState.EXACT
        }
    }
    return if (enabled) AnalysisCellVisualState.EMPTY else AnalysisCellVisualState.DISABLED
}

private fun lockedCellState(
    facts: Collection<ProvenFact>,
    symbol: Char,
    position: Int,
): AnalysisCellVisualState? {
    val exact = facts.lastOrNull { it.position == position && it.isExactMatch }
    if (exact != null) {
        return if (exact.symbol == symbol) {
            AnalysisCellVisualState.LOCKED_EXACT
        } else {
            AnalysisCellVisualState.LOCKED_NO
        }
    }
    return if (facts.any { it.position == position && it.symbol == symbol && !it.isExactMatch }) {
        AnalysisCellVisualState.LOCKED_NO
    } else {
        null
    }
}

private fun analysisCellEditable(
    uiState: GameFieldUiState,
    symbol: Char,
    position: Int,
): Boolean {
    if (lockedCellState(uiState.evidence.provenFacts, symbol, position) != null) return false
    if (
        uiState.tools.autoExcludeEnabled &&
        lockedCellState(uiState.evidence.deduction.provenFacts, symbol, position) != null
    ) {
        return false
    }
    if (analysisMarkFor(uiState.manualMarks, symbol, position) != null) return true
    if (
        uiState.tools.selectedTool == GameFieldTool.YES &&
        uiState.manualMarks.any {
            it.position == position && it.type == GameFieldManualMarkType.YES
        }
    ) {
        return true
    }
    return true
}

private val GameFieldTool.color: Color
    get() = when (this) {
        GameFieldTool.NO -> InplaceXColors.Coral
        GameFieldTool.MAYBE -> InplaceXColors.Amber
        GameFieldTool.YES -> InplaceXColors.Mint
    }

@Composable
private fun statusText(uiState: GameFieldUiState): String {
    val strings = LocalAppStrings.current
    if (!uiState.route.inputEnabled && uiState.match.phase == MatchPhase.ACTIVE) {
        return strings.text("game.status.wait_opponent")
    }
    return when (val status = uiState.status) {
        is GameFieldStatus.AttemptAccepted -> when (uiState.match.phase) {
            MatchPhase.WON -> strings.text("game.status.solved_new_secret")
            MatchPhase.LOST -> strings.text("game.status.no_attempts_left")
            else -> strings.text("game.status.matches")
                .replace("{count}", status.attempt.score.toString())
        }

        is GameFieldStatus.EngineFeedback -> feedbackText(status.feedback, strings::text)
        is GameFieldStatus.HintDigitCount -> strings.text("game.status.hint_applied")
        is GameFieldStatus.HintPositionChecked -> strings.text(
            if (status.isMatch) {
                "game.status.hint_match_here"
            } else {
                "game.status.hint_no_match_here"
            },
        ).replace("{digit}", status.digit.toString())

        is GameFieldStatus.HintPositionOpened -> strings.text("game.status.hint_position_contains")
            .replace("{position}", (status.position + 1).toString())
            .replace("{digit}", status.digit.toString())

        is GameFieldStatus.Notice -> noticeText(status.notice, strings::text)
        GameFieldStatus.InputIncomplete -> strings.text("game.status.enter_digits")
            .replace("{count}", uiState.parameters.codeLength.toString())

        GameFieldStatus.TimedOut -> strings.text("game.status.time_over")
        GameFieldStatus.Idle -> when (uiState.tools.selectedHint) {
            GameFieldHintMode.OPEN_POSITION -> strings.text("game.status.select_slot")
            GameFieldHintMode.CHECK_DIGIT -> strings.text("game.status.select_digit")
            GameFieldHintMode.CHECK_POSITION -> strings.text("game.status.select_table_cell")
            null -> when (uiState.match.phase) {
                MatchPhase.WON -> strings.text("game.status.solved_new_secret")
                MatchPhase.LOST -> strings.text("game.status.no_attempts_left")
                else -> strings.text("game.status.default")
            }
        }
    }
}

internal fun feedbackText(
    feedback: MatchFeedback?,
    text: (String) -> String,
): String = when (feedback) {
    is MatchFeedback.ValidationRejected -> when (feedback.reason) {
        GuessValidationReason.INVALID_LENGTH -> text("game.status.enter_digits")
        GuessValidationReason.NON_DIGIT -> text("game.validation.only_digits")
        GuessValidationReason.DUPLICATE_DIGITS -> text("game.validation.duplicate_digits")
        GuessValidationReason.ALL_SAME_DIGITS -> text("game.validation.all_same_digits")
        GuessValidationReason.ADJACENT_DUPLICATES -> text("game.validation.adjacent_duplicates")
        GuessValidationReason.TRIPLE_DUPLICATES -> text("game.validation.triple_duplicates")
        GuessValidationReason.TOO_MANY_CONSECUTIVE_DUPLICATES ->
            text("game.validation.too_many_consecutive_duplicates")
    }

    is MatchFeedback.ExtraMovesGranted -> text("game.status.moves_added")
        .replace("{count}", feedback.amount.toString())

    is MatchFeedback.MatchFinished -> when (feedback.phase) {
        MatchPhase.WON -> text("game.status.solved_new_secret")
        MatchPhase.LOST -> text("game.status.no_attempts_left")
        else -> text("game.status.attempt_not_accepted")
    }

    is MatchFeedback.ActionRejected,
    null,
    -> text("game.status.attempt_not_accepted")
}

private fun noticeText(
    notice: GameFieldNotice,
    text: (String) -> String,
): String = when (notice) {
    GameFieldNotice.NoHints -> text("game.status.no_hints")
    GameFieldNotice.WatchAdForHint -> text("game.status.watch_ad_for_hint")
    GameFieldNotice.BonusHintReady -> text("game.status.bonus_hint_ready")
    GameFieldNotice.BonusNotGranted -> text("game.status.bonus_not_granted")
    GameFieldNotice.HintUnavailable -> text("game.status.hint_unavailable")
    GameFieldNotice.NoMoveBoosts -> text("game.status.no_move_boosts")
    is GameFieldNotice.MovesAdded -> text("game.status.moves_added")
        .replace("{count}", notice.count.toString())

    GameFieldNotice.NoTimeBoosts -> text("game.status.no_time_boosts")
    is GameFieldNotice.TimeAdded -> text("game.status.time_added")
        .replace("{minutes}", (notice.seconds / 60).toString())

    GameFieldNotice.NewSecret -> text("game.status.new_secret")
    GameFieldNotice.AutoEnabled -> text("game.status.auto_enabled")
    GameFieldNotice.AutoDisabled -> text("game.status.auto_disabled")
}

private fun isErrorStatus(status: GameFieldStatus): Boolean = when (status) {
    is GameFieldStatus.EngineFeedback -> status.feedback is MatchFeedback.ValidationRejected
    is GameFieldStatus.Notice -> status.notice in setOf(
        GameFieldNotice.NoHints,
        GameFieldNotice.BonusNotGranted,
        GameFieldNotice.HintUnavailable,
        GameFieldNotice.NoMoveBoosts,
        GameFieldNotice.NoTimeBoosts,
    )

    GameFieldStatus.TimedOut -> true
    else -> false
}

private fun timerValue(elapsedSeconds: Int, limitSeconds: Int): String {
    val shown = if (limitSeconds > 0) {
        (limitSeconds - elapsedSeconds).coerceAtLeast(0)
    } else {
        elapsedSeconds
    }
    return "%02d:%02d".format(shown / 60, shown % 60)
}

private fun moveValue(movesDone: Int, configuredLimit: Int?, bonusMoves: Int): String =
    if (configuredLimit != null && configuredLimit > 0) {
        "$movesDone/${configuredLimit + bonusMoves}"
    } else {
        movesDone.toString()
    }

private fun formatDuration(seconds: Int): String =
    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
