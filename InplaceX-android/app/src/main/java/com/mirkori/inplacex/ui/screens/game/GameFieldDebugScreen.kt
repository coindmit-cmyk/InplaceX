package com.mirkori.inplacex.ui.screens.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.engine.GuessValidationReason
import com.mirkori.inplacex.core.match.MatchFeedback
import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.screens.game.presentation.feedbackText
import com.mirkori.inplacex.ui.viewmodel.GameFieldViewModel

private const val DEBUG_CODE_LENGTH = 6

/**
 * Версия без lifecycle-viewmodel-compose.
 * Для теста создаёт ViewModel через remember, чтобы убрать ошибку
 * Unresolved reference 'compose' на импорте:
 * androidx.lifecycle.viewmodel.compose.viewModel
 */
@Composable
fun GameFieldDebugScreen() {
    val strings = LocalAppStrings.current
    val vm = remember { GameFieldViewModel() }
    val state by vm.state.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = strings.text("game.debug_screen.title"),
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        strings.text("game.debug_screen.status")
                            .replace("{phase}", debugPhaseText(state.phase, strings::text)),
                    )
                    Text(
                        strings.text("game.debug_screen.attempts_left")
                            .replace("{count}", state.attemptsLeft.toString()),
                    )
                    Text(
                        strings.text("game.debug_screen.attempts_made")
                            .replace("{count}", state.attempts.size.toString()),
                    )
                }
            }

            Card(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(strings.text("game.debug_screen.debug"))
                    Text(strings.text("game.debug.secret").replace("{value}", state.debugSecret))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = input,
            onValueChange = { value ->
                input = value.filter { it.isDigit() }.take(DEBUG_CODE_LENGTH)
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("game-debug-input"),
            label = {
                Text(
                    strings.text("game.debug_screen.enter_digits")
                        .replace("{count}", DEBUG_CODE_LENGTH.toString()),
                )
            },
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    vm.submit(input)
                    input = ""
                }
            ) {
                Text(strings.text("game.debug_screen.action.check"))
            }

            Button(
                onClick = {
                    vm.restart()
                    input = ""
                }
            ) {
                Text(strings.text("game.debug_screen.action.restart"))
            }
        }

        state.feedback?.let { feedback ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = debugFeedbackText(
                    feedback = feedback,
                    codeLength = DEBUG_CODE_LENGTH,
                    text = strings::text,
                ),
                modifier = Modifier.testTag("game-debug-feedback"),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = strings.text("game.debug_screen.history_title"),
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(state.attempts.reversed()) { attempt ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("#${attempt.number}  ${attempt.guess}")
                        Text(
                            strings.text("game.debug_screen.matches")
                                .replace("{count}", attempt.score.toString()),
                        )
                    }
                }
            }
        }
    }
}

internal fun debugPhaseText(
    phase: MatchPhase,
    text: (String) -> String,
): String = when (phase) {
    MatchPhase.NOT_STARTED -> text("game.debug_screen.phase.not_started")
    MatchPhase.ACTIVE -> text("game.debug_screen.phase.active")
    MatchPhase.WON -> text("game.debug_screen.phase.won")
    MatchPhase.LOST -> text("game.debug_screen.phase.lost")
}

internal fun debugFeedbackText(
    feedback: MatchFeedback,
    codeLength: Int,
    text: (String) -> String,
): String = when (feedback) {
    is MatchFeedback.ValidationRejected -> when (feedback.reason) {
        GuessValidationReason.INVALID_LENGTH -> text("game.status.enter_digits")
            .replace("{count}", codeLength.toString())

        else -> feedbackText(feedback, text)
    }

    else -> feedbackText(feedback, text)
}
