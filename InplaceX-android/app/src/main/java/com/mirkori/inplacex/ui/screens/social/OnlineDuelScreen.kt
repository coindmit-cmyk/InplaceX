package com.mirkori.inplacex.ui.screens.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import com.mirkori.inplacex.platform.online.OnlineClientResult
import com.mirkori.inplacex.platform.online.OnlineDuelSnapshotState
import com.mirkori.inplacex.platform.online.OnlineFriendInvite
import com.mirkori.inplacex.platform.online.OnlineFriendInviteStatus
import com.mirkori.inplacex.platform.online.OnlineRuntime
import com.mirkori.inplacex.platform.online.RemoteFriendPlayStyle
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import com.mirkori.inplacex.ui.screens.shared.ScenePageColumn
import com.mirkori.inplacex.ui.state.TransientOperationGate
import com.mirkori.inplacex.ui.theme.InplaceXColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class OnlineDuelEntryPoint {
    INVITES,
    QUICK_MATCH,
}

@Composable
internal fun OnlineDuelScreen(
    runtime: OnlineRuntime,
    entryPoint: OnlineDuelEntryPoint = OnlineDuelEntryPoint.QUICK_MATCH,
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<OnlineDuelUiState>(OnlineDuelUiState.Ready) }
    var digits by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var guessHistorySessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var guessHistoryEntries by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val guessSubmission = remember { TransientOperationGate() }
    var inviteNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPlayStyleName by rememberSaveable {
        mutableStateOf(RemoteFriendPlayStyle.RACE.name)
    }
    var selectedCodeLength by rememberSaveable { mutableStateOf(4) }
    val selectedPlayStyle = RemoteFriendPlayStyle.valueOf(selectedPlayStyleName)

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

    suspend fun startQuickMatch() {
        state = OnlineDuelUiState.Loading(strings.text("social.online.searching"))
        state = when (
            val ticket = runtime.createMatch(
                playStyle = selectedPlayStyle,
                codeLength = selectedCodeLength,
            )
        ) {
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
            submitting = guessSubmission.inProgress,
            onSubmitGuess = { submitted ->
                guessSubmission.start()?.let { operationId ->
                    scope.launch {
                        try {
                            val result = runtime.submitGuess(
                                snapshot.sessionId,
                                snapshot.revision,
                                submitted,
                            )
                            if (!guessSubmission.isCurrent(operationId)) return@launch
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
                        } finally {
                            guessSubmission.finish(operationId)
                        }
                    }
                }
            },
            onBack = {
                guessSubmission.cancel()
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
                text = strings.text(
                    if (entryPoint == OnlineDuelEntryPoint.INVITES) {
                        "social.invites"
                    } else {
                        "social.match.title"
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = strings.text(
                    if (entryPoint == OnlineDuelEntryPoint.INVITES) {
                        "social.invites.screen_description"
                    } else {
                        "social.online.screen_description"
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        when (val current = state) {
            OnlineDuelUiState.Ready -> SceneCard {
                Text("Настройки онлайн-матча", fontWeight = FontWeight.SemiBold)
                Text(
                    strings.text("social.match.format"),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FriendPlayStyleButton(
                        selected = selectedPlayStyle == RemoteFriendPlayStyle.RACE,
                        text = strings.text("social.match.timed"),
                        modifier = Modifier.weight(1f),
                        onClick = { selectedPlayStyleName = RemoteFriendPlayStyle.RACE.name },
                    )
                    FriendPlayStyleButton(
                        selected = selectedPlayStyle == RemoteFriendPlayStyle.TURN_BASED,
                        text = strings.text("social.match.turn_based"),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedPlayStyleName = RemoteFriendPlayStyle.TURN_BASED.name
                        },
                    )
                }
                Text(
                    if (selectedPlayStyle == RemoteFriendPlayStyle.RACE) {
                        strings.text("social.match.timed.description")
                    } else {
                        strings.text("social.match.turn_based.description")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Длина секрета", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        enabled = selectedCodeLength > MinimumOnlineCodeLength,
                        onClick = { selectedCodeLength -= 1 },
                    ) {
                        Text("−")
                    }
                    Text(
                        text = "$selectedCodeLength цифр",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedButton(
                        enabled = selectedCodeLength < MaximumOnlineCodeLength,
                        onClick = { selectedCodeLength += 1 },
                    ) {
                        Text("+")
                    }
                }
                Text(
                    "Цифры могут повторяться, но не больше трёх одинаковых подряд. " +
                        "Лимита ходов нет, время матча — 10 минут.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (entryPoint == OnlineDuelEntryPoint.QUICK_MATCH) {
                    Text("Найти соперника", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Если другого игрока с такими же настройками нет, сервер подключит бота.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                startQuickMatch()
                            }
                        },
                    ) {
                        Text("Найти матч")
                    }
                }

                if (entryPoint == OnlineDuelEntryPoint.INVITES) {
                    Text("Играть с другом", fontWeight = FontWeight.SemiBold)
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            state = OnlineDuelUiState.Loading("Создаём приглашение…")
                            scope.launch {
                                state = when (
                                    val result = runtime.createFriendInvite(
                                        playStyle = selectedPlayStyle,
                                        codeLength = selectedCodeLength,
                                    )
                                ) {
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
                            inviteCode = normalizeFriendInviteCode(value)
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
                Text(
                    "${current.invite.playStyle.displayName(strings)} · " +
                        "${current.invite.codeLength} цифр · без лимита ходов",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(
                                    ClipData.newPlainText(
                                        strings.text("social.invite.clipboard_label"),
                                        current.invite.inviteCode,
                                    ),
                                )
                            inviteNotice = strings.text("social.invite.copied")
                        },
                    ) {
                        Text(strings.text("social.invite.copy"))
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val shareText = formatFriendInviteShareText(
                                strings.text("social.invite.share_text"),
                                current.invite.inviteCode,
                            )
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    sendIntent,
                                    strings.text("social.invite.share_title"),
                                ),
                            )
                        },
                    ) {
                        Text(strings.text("social.invite.share"))
                    }
                }
                inviteNotice?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = InplaceXColors.Cobalt,
                    )
                }
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
                            "setup" -> if (snapshot.playerSecretConfigured) {
                                "Ждём секрет друга"
                            } else {
                                "Задайте свой секретный код"
                            }
                            "active" -> if (snapshot.playStyle == RemoteFriendPlayStyle.RACE) {
                                strings.text("social.match.timed.started")
                            } else if (snapshot.currentTurn == "player") {
                                "Ваш ход"
                            } else {
                                "Ход друга"
                            }
                            "finished" -> when {
                                snapshot.finishReason == "time_expired" -> "Время вышло"
                                snapshot.winner == "player" -> "Вы победили!"
                                snapshot.winner == "opponent" -> "Друг победил"
                                else -> "Матч завершён"
                            }
                            else -> "Матч"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${snapshot.playStyle.displayName(strings)} · ${snapshot.codeLength} цифр · " +
                            "ходов: ${snapshot.attempts.count { it.actor == "player" }}",
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

                if (
                    snapshot.phase != "finished" &&
                    !snapshot.playerSecretConfigured &&
                    snapshot.currentTurn != "opponent"
                ) {
                    val inputValid =
                        digits.length == snapshot.codeLength &&
                            digits.maximumConsecutiveRun() <=
                            (snapshot.maxConsecutiveDuplicateDigits ?: snapshot.codeLength)
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
                                if (digits.maximumConsecutiveRun() > 3) {
                                    Text("Нельзя вводить больше трёх одинаковых цифр подряд")
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
                            Text(
                                if (snapshot.phase == "setup") {
                                    "Друг задаёт свой секрет…"
                                } else {
                                    "Ожидаем ход друга…"
                                },
                            )
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

@Composable
private fun FriendPlayStyleButton(
    selected: Boolean,
    text: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(modifier = modifier, onClick = onClick) {
            Text(text)
        }
    } else {
        OutlinedButton(modifier = modifier, onClick = onClick) {
            Text(text)
        }
    }
}

private fun RemoteFriendPlayStyle.displayName(strings: LocalizationProvider): String = when (this) {
    RemoteFriendPlayStyle.RACE -> strings.text("social.match.timed")
    RemoteFriendPlayStyle.TURN_BASED -> strings.text("social.match.turn_based")
}

private fun String.maximumConsecutiveRun(): Int {
    var maximum = 0
    var current = 0
    var previous: Char? = null
    forEach { digit ->
        current = if (digit == previous) current + 1 else 1
        maximum = maxOf(maximum, current)
        previous = digit
    }
    return maximum
}

private const val SynchronizationPollMillis = 750L
private const val FriendInviteCodeLength = 8
private const val FriendInviteAlphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
private const val MinimumOnlineCodeLength = 4
private const val MaximumOnlineCodeLength = 10

internal fun normalizeFriendInviteCode(value: String): String =
    value
        .uppercase()
        .filter { it in FriendInviteAlphabet }
        .take(FriendInviteCodeLength)

internal fun formatFriendInviteShareText(template: String, code: String): String =
    template.replace("{code}", code)

private fun List<String>.toKnownGuessMap(): Map<Int, String> =
    mapNotNull { entry ->
        val number = entry.substringBefore('=').toIntOrNull() ?: return@mapNotNull null
        val guess = entry.substringAfter('=', missingDelimiterValue = "")
        if (guess.isEmpty() || !guess.all(Char::isDigit)) return@mapNotNull null
        number to guess
    }.toMap()
