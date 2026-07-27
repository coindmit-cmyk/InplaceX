package com.mirkori.inplacex.ui.screens.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.online.OnlineClientResult
import com.mirkori.inplacex.platform.online.OnlineDuelSnapshotState
import com.mirkori.inplacex.platform.online.OnlineRuntime
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import com.mirkori.inplacex.ui.screens.shared.ScenePageColumn
import com.mirkori.inplacex.ui.theme.InplaceXColors
import kotlinx.coroutines.launch

@Composable
internal fun OnlineDuelScreen(
    runtime: OnlineRuntime,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<OnlineDuelUiState>(OnlineDuelUiState.Ready) }
    var digits by remember { mutableStateOf("") }

    fun accept(result: OnlineClientResult<OnlineDuelSnapshotState>) {
        state = when (result) {
            is OnlineClientResult.Success -> OnlineDuelUiState.Playing(result.value)
            OnlineClientResult.AuthenticationRequired -> OnlineDuelUiState.Error("Не удалось восстановить гостевую сессию")
            OnlineClientResult.MembershipRejected -> OnlineDuelUiState.Error("Сервер отклонил участие в этом матче")
            OnlineClientResult.RevisionConflict -> OnlineDuelUiState.Error("Матч изменился. Откройте его заново")
            OnlineClientResult.Offline -> OnlineDuelUiState.Error("Нет подключения к сети")
            OnlineClientResult.TemporarilyUnavailable -> OnlineDuelUiState.Error("Сервер временно недоступен")
            OnlineClientResult.InvalidResponse -> OnlineDuelUiState.Error("Сервер вернул некорректный ответ")
        }
    }

    ScenePageColumn(
        modifier = Modifier.fillMaxSize(),
        scrollable = true,
    ) {
        SceneCard(
            accentColor = InplaceXColors.ToyBlue,
            contentColor = androidx.compose.ui.graphics.Color.White,
        ) {
            Text(
                text = "Онлайн-дуэль",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Сервер проверяет каждый ход. Пока живой соперник ищется, матч продолжит серверный бот.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        when (val current = state) {
            OnlineDuelUiState.Ready -> SceneCard {
                Text("Матч готов к поиску", fontWeight = FontWeight.SemiBold)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        state = OnlineDuelUiState.Loading
                        scope.launch {
                            when (val ticket = runtime.createMatch()) {
                                is OnlineClientResult.Success -> accept(
                                    runtime.readSession(requireNotNull(ticket.value.sessionId)),
                                )
                                OnlineClientResult.AuthenticationRequired ->
                                    state = OnlineDuelUiState.Error("Не удалось войти как гость")
                                OnlineClientResult.MembershipRejected ->
                                    state = OnlineDuelUiState.Error("Участие в матче отклонено")
                                OnlineClientResult.RevisionConflict ->
                                    state = OnlineDuelUiState.Error("Конфликт состояния матча")
                                OnlineClientResult.Offline ->
                                    state = OnlineDuelUiState.Error("Нет подключения к сети")
                                OnlineClientResult.TemporarilyUnavailable ->
                                    state = OnlineDuelUiState.Error("Сервер временно недоступен")
                                OnlineClientResult.InvalidResponse ->
                                    state = OnlineDuelUiState.Error("Сервер вернул некорректный ответ")
                            }
                        }
                    },
                ) {
                    Text("Найти матч")
                }
            }

            OnlineDuelUiState.Loading -> SceneCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Подключаемся к серверу…")
                }
            }

            is OnlineDuelUiState.Error -> SceneCard {
                Text(current.message, color = MaterialTheme.colorScheme.error)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { state = OnlineDuelUiState.Ready },
                ) {
                    Text("Попробовать снова")
                }
            }

            is OnlineDuelUiState.Playing -> {
                val snapshot = current.snapshot
                SceneCard {
                    Text(
                        text = when (snapshot.phase) {
                            "setup" -> "Задайте свой секретный код"
                            "active" -> if (snapshot.currentTurn == "player") "Ваш ход" else "Ход соперника"
                            "finished" -> if (snapshot.winner == "player") "Вы победили!" else "Соперник победил"
                            else -> "Матч"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Код: ${snapshot.codeLength} цифры · " +
                            "попыток: ${snapshot.attempts.count { it.actor == "player" }}/${snapshot.attemptLimit}",
                    )
                    snapshot.attempts.takeLast(8).forEach { attempt ->
                        Text(
                            if (attempt.actor == "player") {
                                "Ваш ход #${attempt.number}: точно ${attempt.exactMatches}"
                            } else {
                                "Соперник #${attempt.number}: точно ${attempt.exactMatches}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                if (snapshot.phase != "finished" && snapshot.currentTurn != "opponent") {
                    SceneCard {
                        OutlinedTextField(
                            value = digits,
                            onValueChange = { value ->
                                digits = value.filter(Char::isDigit).take(snapshot.codeLength)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(if (snapshot.phase == "setup") "Ваш секрет" else "Ваша комбинация")
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                        )
                        Button(
                            enabled = digits.length == snapshot.codeLength,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val submitted = digits
                                digits = ""
                                state = OnlineDuelUiState.Loading
                                scope.launch {
                                    val result = if (snapshot.phase == "setup") {
                                        runtime.submitSecret(snapshot.sessionId, snapshot.revision, submitted)
                                    } else {
                                        runtime.submitGuess(snapshot.sessionId, snapshot.revision, submitted)
                                    }
                                    accept(result)
                                }
                            },
                        ) {
                            Text(if (snapshot.phase == "setup") "Сохранить секрет" else "Подтвердить ход")
                        }
                    }
                }

                if (snapshot.phase == "finished") {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { state = OnlineDuelUiState.Ready },
                    ) {
                        Text("Новый матч")
                    }
                }
            }
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onBack,
        ) {
            Text("Назад")
        }
    }
}

private sealed interface OnlineDuelUiState {
    data object Ready : OnlineDuelUiState
    data object Loading : OnlineDuelUiState
    data class Playing(val snapshot: OnlineDuelSnapshotState) : OnlineDuelUiState
    data class Error(val message: String) : OnlineDuelUiState
}
