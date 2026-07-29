package com.mirkori.inplacex.ui.screens.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.online.OnlineClientResult
import com.mirkori.inplacex.platform.online.OnlineDuelSnapshotState
import com.mirkori.inplacex.platform.online.OnlineFriendInvite
import com.mirkori.inplacex.platform.online.OnlineFriendInviteStatus
import com.mirkori.inplacex.platform.online.OnlineRuntime
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import com.mirkori.inplacex.ui.screens.shared.ScenePageColumn
import com.mirkori.inplacex.ui.theme.InplaceXColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun OnlineDuelScreen(
    runtime: OnlineRuntime,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<OnlineDuelUiState>(OnlineDuelUiState.Ready) }
    var digits by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var guessHistorySessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var guessHistoryEntries by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var guessSubmitting by rememberSaveable { mutableStateOf(false) }

    fun snapshotState(result: OnlineClientResult<OnlineDuelSnapshotState>): OnlineDuelUiState =
        when (result) {
            is OnlineClientResult.Success -> OnlineDuelUiState.Playing(result.value)
            OnlineClientResult.AuthenticationRequired ->
                OnlineDuelUiState.Error("Не удалось восстановить гостевую сессию")
            OnlineClientResult.MembershipRejected ->
                OnlineDuelUiState.Error("Сервер отклонил участие в этом матче")
            OnlineClientResult.RevisionConflict ->
                OnlineDuelUiState.Error("Матч изменился. Состояние будет загружено заново")
            OnlineClientResult.Offline -> OnlineDuelUiState.Error("Нет подключения к сети")
            OnlineClientResult.TemporarilyUnavailable ->
                OnlineDuelUiState.Error("Сервер временно недоступен")
            OnlineClientResult.InvalidResponse ->
                OnlineDuelUiState.Error("Сервер вернул некорректный ответ")
        }

    fun inviteError(result: OnlineClientResult<*>): OnlineDuelUiState.Error =
        OnlineDuelUiState.Error(
            when (result) {
                OnlineClientResult.AuthenticationRequired -> "Не удалось войти как гость"
                OnlineClientResult.MembershipRejected -> "Это приглашение принадлежит другому игроку"
                OnlineClientResult.RevisionConflict -> "Приглашение уже использовано"
                OnlineClientResult.Offline -> "Нет подключения к сети"
                OnlineClientResult.TemporarilyUnavailable -> "Сервер временно недоступен"
                OnlineClientResult.InvalidResponse -> "Код не найден или приглашение уже истекло"
                is OnlineClientResult.Success<*> -> "Не удалось открыть приглашение"
            },
        )

    suspend fun openInviteSession(invite: OnlineFriendInvite) {
        val sessionId = invite.sessionId
        state = if (sessionId == null) {
            OnlineDuelUiState.Error("Сервер не создал комнату")
        } else {
            snapshotState(runtime.readSession(sessionId))
        }
    }

    LaunchedEffect(state) {
        when (val current = state) {
            is OnlineDuelUiState.WaitingForFriend -> {
                while (state == current) {
                    delay(SynchronizationPollMillis)
                    when (val result = runtime.readFriendInvite(current.invite.inviteCode)) {
                        is OnlineClientResult.Success -> when (result.value.status) {
                            OnlineFriendInviteStatus.WAITING -> Unit
                            OnlineFriendInviteStatus.MATCHED -> openInviteSession(result.value)
                            OnlineFriendInviteStatus.EXPIRED ->
                                state = OnlineDuelUiState.Error("Время приглашения истекло")
                        }
                        OnlineClientResult.Offline,
                        OnlineClientResult.TemporarilyUnavailable,
                        -> Unit
                        else -> state = inviteError(result)
                    }
                }
            }

            is OnlineDuelUiState.Playing -> {
                while (state == current && current.snapshot.phase != "finished") {
                    delay(SynchronizationPollMillis)
                    when (val result = runtime.readSession(current.snapshot.sessionId)) {
                        is OnlineClientResult.Success -> {
                            if (result.value.revision != current.snapshot.revision) {
                                state = OnlineDuelUiState.Playing(result.value)
                            }
                        }
                        OnlineClientResult.Offline,
                        OnlineClientResult.TemporarilyUnavailable,
                        -> Unit
                        else -> state = snapshotState(result)
                    }
                }
            }

            else -> Unit
        }
    }

    val playing = state as? OnlineDuelUiState.Playing
    if (playing?.snapshot?.phase == "active") {
        val snapshot = playing.snapshot
        val knownPlayerGuesses = if (guessHistorySessionId == snapshot.sessionId) {
            guessHistoryEntries.toKnownGuessMap()
        } else {
            emptyMap()
        }
        OnlineDuelGameField(
            snapshot = snapshot,
            knownPlayerGuesses = knownPlayerGuesses,
            submitting = guessSubmitting,
            onSubmitGuess = { submitted ->
                if (!guessSubmitting) {
                    guessSubmitting = true
                    scope.launch {
                        val result = runtime.submitGuess(
                            snapshot.sessionId,
                            snapshot.revision,
                            submitted,
                        )
                        if (result is OnlineClientResult.Success) {
                            val previousNumbers = snapshot.attempts
                                .asSequence()
                                .filter { it.actor == "player" }
                                .mapTo(mutableSetOf()) { it.number }
                            result.value.attempts
                                .firstOrNull {
                                    it.actor == "player" && it.number !in previousNumbers
                                }
                                ?.let { accepted ->
                                    if (guessHistorySessionId != snapshot.sessionId) {
                                        guessHistoryEntries = emptyList()
                                    }
                                    guessHistorySessionId = snapshot.sessionId
                                    guessHistoryEntries = (
                                        guessHistoryEntries.filterNot {
                                            it.substringBefore('=') == accepted.number.toString()
                                        } + "${accepted.number}=$submitted"
                                    )
                                }
                        }
                        state = if (result == OnlineClientResult.RevisionConflict) {
                            snapshotState(runtime.readSession(snapshot.sessionId))
                        } else {
                            snapshotState(result)
                        }
                        guessSubmitting = false
                    }
                }
            },
            onBack = {
                guessSubmitting = false
                state = OnlineDuelUiState.Ready
            },
        )
        return
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
                text = "Создайте приватный код для друга или найдите общего соперника. " +
                    "Секреты и результаты проверяет сервер.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        when (val current = state) {
            OnlineDuelUiState.Ready -> SceneCard {
                Text("Быстрый матч", fontWeight = FontWeight.SemiBold)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        state = OnlineDuelUiState.Loading("Ищем соперника…")
                        scope.launch {
                            state = when (val ticket = runtime.createMatch()) {
                                is OnlineClientResult.Success -> {
                                    val sessionId = ticket.value.sessionId
                                    if (sessionId == null) {
                                        OnlineDuelUiState.Error("Сервер не создал матч")
                                    } else {
                                        snapshotState(runtime.readSession(sessionId))
                                    }
                                }
                                else -> inviteError(ticket)
                            }
                        }
                    },
                ) {
                    Text("Найти матч")
                }

                Text("Играть с другом", fontWeight = FontWeight.SemiBold)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        state = OnlineDuelUiState.Loading("Создаём приглашение…")
                        scope.launch {
                            state = when (val result = runtime.createFriendInvite()) {
                                is OnlineClientResult.Success ->
                                    OnlineDuelUiState.WaitingForFriend(result.value)
                                else -> inviteError(result)
                            }
                        }
                    },
                ) {
                    Text("Создать код")
                }
                OutlinedTextField(
                    value = inviteCode,
                    onValueChange = { value ->
                        inviteCode = value
                            .uppercase()
                            .filter { it in FriendInviteAlphabet }
                            .take(FriendInviteCodeLength)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Код друга") },
                    singleLine = true,
                )
                OutlinedButton(
                    enabled = inviteCode.length == FriendInviteCodeLength,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val submittedCode = inviteCode
                        state = OnlineDuelUiState.Loading("Подключаемся к другу…")
                        scope.launch {
                            when (val result = runtime.acceptFriendInvite(submittedCode)) {
                                is OnlineClientResult.Success -> openInviteSession(result.value)
                                else -> state = inviteError(result)
                            }
                        }
                    },
                ) {
                    Text("Войти по коду")
                }
            }

            is OnlineDuelUiState.Loading -> SceneCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(current.message)
                }
            }

            is OnlineDuelUiState.WaitingForFriend -> SceneCard {
                Text("Передайте код другу", fontWeight = FontWeight.SemiBold)
                Text(
                    text = current.invite.inviteCode,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = InplaceXColors.Cobalt,
                )
                Text("Ожидаем второй телефон. Комната действует 10 минут.")
                CircularProgressIndicator()
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { state = OnlineDuelUiState.Ready },
                ) {
                    Text("Отмена")
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
                            "active" -> if (snapshot.currentTurn == "player") "Ваш ход" else "Ход друга"
                            "finished" -> if (snapshot.winner == "player") "Вы победили!" else "Друг победил"
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
                                "Друг #${attempt.number}: точно ${attempt.exactMatches}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                if (snapshot.phase != "finished" && snapshot.currentTurn != "opponent") {
                    val inputValid =
                        digits.length == snapshot.codeLength &&
                            (snapshot.allowDuplicates || digits.toSet().size == digits.length)
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
                            supportingText = {
                                if (
                                    digits.length == snapshot.codeLength &&
                                    !snapshot.allowDuplicates &&
                                    digits.toSet().size != digits.length
                                ) {
                                    Text("Цифры не должны повторяться")
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true,
                        )
                        Button(
                            enabled = inputValid,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val submitted = digits
                                digits = ""
                                state = OnlineDuelUiState.Loading("Синхронизируем ход…")
                                scope.launch {
                                    val result = if (snapshot.phase == "setup") {
                                        runtime.submitSecret(snapshot.sessionId, snapshot.revision, submitted)
                                    } else {
                                        runtime.submitGuess(snapshot.sessionId, snapshot.revision, submitted)
                                    }
                                    state = if (result == OnlineClientResult.RevisionConflict) {
                                        snapshotState(runtime.readSession(snapshot.sessionId))
                                    } else {
                                        snapshotState(result)
                                    }
                                }
                            },
                        ) {
                            Text(if (snapshot.phase == "setup") "Сохранить секрет" else "Подтвердить ход")
                        }
                    }
                } else if (snapshot.phase != "finished") {
                    SceneCard {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text("Ожидаем ход друга…")
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

    }
}

private sealed interface OnlineDuelUiState {
    data object Ready : OnlineDuelUiState
    data class Loading(val message: String) : OnlineDuelUiState
    data class WaitingForFriend(val invite: OnlineFriendInvite) : OnlineDuelUiState
    data class Playing(val snapshot: OnlineDuelSnapshotState) : OnlineDuelUiState
    data class Error(val message: String) : OnlineDuelUiState
}

private const val SynchronizationPollMillis = 750L
private const val FriendInviteCodeLength = 8
private const val FriendInviteAlphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

private fun List<String>.toKnownGuessMap(): Map<Int, String> =
    mapNotNull { entry ->
        val number = entry.substringBefore('=').toIntOrNull() ?: return@mapNotNull null
        val guess = entry.substringAfter('=', missingDelimiterValue = "")
        if (guess.isEmpty() || !guess.all(Char::isDigit)) return@mapNotNull null
        number to guess
    }.toMap()
