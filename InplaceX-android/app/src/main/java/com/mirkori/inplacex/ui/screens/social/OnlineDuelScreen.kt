package com.mirkori.inplacex.ui.screens.social

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
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
    FRIEND,
    QUICK_MATCH,
}

internal enum class OnlineDuelBackTarget {
    MATCH_SETUP,
    SOCIAL_ROOT,
}

internal fun onlineDuelBackTarget(activePhase: String?): OnlineDuelBackTarget =
    if (activePhase == "active") OnlineDuelBackTarget.MATCH_SETUP else OnlineDuelBackTarget.SOCIAL_ROOT

internal fun shouldAutoStartQuickMatch(
    entryPoint: OnlineDuelEntryPoint,
    initialSessionId: String?,
    requested: Boolean,
): Boolean = requested && entryPoint == OnlineDuelEntryPoint.QUICK_MATCH && initialSessionId == null

@Composable
internal fun OnlineDuelScreen(
    runtime: OnlineRuntime,
    initialSessionId: String? = null,
    onActiveSessionChange: (String?) -> Unit = {},
    initialPendingInviteCode: String? = null,
    onPendingInviteChange: (String?) -> Unit = {},
    entryPoint: OnlineDuelEntryPoint = OnlineDuelEntryPoint.QUICK_MATCH,
    initialPlayStyle: RemoteFriendPlayStyle = RemoteFriendPlayStyle.RACE,
    initialCodeLength: Int = 4,
    autoStartQuickMatch: Boolean = false,
    targetPlayerId: String? = null,
    targetDisplayName: String? = null,
    autoAcceptInviteCode: String? = null,
    requestBack: Boolean = false,
    onBackRequestConsumed: () -> Unit = {},
    onExitDestination: () -> Unit = {},
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    val recoveryPendingInviteCode = initialPendingInviteCode.takeIf {
        entryPoint == OnlineDuelEntryPoint.INVITES && autoAcceptInviteCode == null
    }
    var state by remember {
        mutableStateOf<OnlineDuelUiState>(
            if (
                (entryPoint != OnlineDuelEntryPoint.QUICK_MATCH || initialSessionId == null) &&
                recoveryPendingInviteCode == null
            ) {
                if (autoAcceptInviteCode == null && !autoStartQuickMatch) OnlineDuelUiState.Ready
                else if (autoAcceptInviteCode == null) {
                    OnlineDuelUiState.Loading(strings.text("social.online.searching"))
                }
                else OnlineDuelUiState.Loading(strings.text("social.online.joining_friend"))
            } else {
                OnlineDuelUiState.Loading(strings.text("social.online.restoring"))
            },
        )
    }
    var digits by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    val guessSubmission = remember { TransientOperationGate() }
    var inviteNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var joinCodeScreenOpen by rememberSaveable { mutableStateOf(false) }
    var selectedPlayStyleName by rememberSaveable {
        mutableStateOf(initialPlayStyle.name)
    }
    var selectedCodeLength by rememberSaveable {
        mutableStateOf(normalizeOnlineCodeLength(initialCodeLength))
    }
    var restoredSessionId by remember { mutableStateOf<String?>(null) }
    var restoredPendingInviteCode by remember { mutableStateOf<String?>(null) }
    var attemptedAutoAcceptCode by remember { mutableStateOf<String?>(null) }
    var completedAutoAcceptCode by remember { mutableStateOf<String?>(null) }
    var attemptedAutoStart by remember { mutableStateOf(false) }
    val selectedPlayStyle = RemoteFriendPlayStyle.valueOf(selectedPlayStyleName)

    fun snapshotState(result: OnlineClientResult<OnlineDuelSnapshotState>): OnlineDuelUiState =
        when (result) {
            is OnlineClientResult.Success -> OnlineDuelUiState.Playing(result.value)
            OnlineClientResult.AuthenticationRequired ->
                OnlineDuelUiState.Error(strings.text("social.online.error.restore_auth"))
            OnlineClientResult.MembershipRejected ->
                OnlineDuelUiState.Error(strings.text("social.online.error.membership"))
            OnlineClientResult.RevisionConflict ->
                OnlineDuelUiState.Error(strings.text("social.online.error.revision"))
            OnlineClientResult.Offline ->
                OnlineDuelUiState.Error(strings.text("social.online.error.offline"))
            OnlineClientResult.TemporarilyUnavailable ->
                OnlineDuelUiState.Error(strings.text("social.online.error.unavailable"))
            OnlineClientResult.InvalidResponse ->
                OnlineDuelUiState.Error(strings.text("social.online.error.invalid_response"))
        }

    fun applySnapshotResult(
        result: OnlineClientResult<OnlineDuelSnapshotState>,
    ): OnlineDuelUiState {
        when (result) {
            is OnlineClientResult.Success -> {
                onActiveSessionChange(result.value.sessionId.takeUnless { result.value.phase == "finished" })
                onPendingInviteChange(null)
            }
            OnlineClientResult.AuthenticationRequired,
            OnlineClientResult.MembershipRejected,
            OnlineClientResult.InvalidResponse,
            -> onActiveSessionChange(null)
            OnlineClientResult.RevisionConflict,
            OnlineClientResult.Offline,
            OnlineClientResult.TemporarilyUnavailable,
            -> Unit
        }
        return snapshotState(result)
    }

    fun inviteError(result: OnlineClientResult<*>): OnlineDuelUiState.Error =
        OnlineDuelUiState.Error(
            when (result) {
                OnlineClientResult.AuthenticationRequired -> strings.text("social.online.error.guest_auth")
                OnlineClientResult.MembershipRejected -> strings.text("social.online.error.invite_owner")
                OnlineClientResult.RevisionConflict -> strings.text("social.online.error.invite_used")
                OnlineClientResult.Offline -> strings.text("social.online.error.offline")
                OnlineClientResult.TemporarilyUnavailable -> strings.text("social.online.error.unavailable")
                OnlineClientResult.InvalidResponse -> strings.text("social.online.error.invite_invalid")
                is OnlineClientResult.Success<*> -> strings.text("social.online.error.invite_open")
            },
        )

    suspend fun openInviteSession(invite: OnlineFriendInvite) {
        val sessionId = invite.sessionId
        state = if (sessionId == null) {
            OnlineDuelUiState.Error(strings.text("social.online.error.room_missing"))
        } else {
            restoredSessionId = sessionId
            onActiveSessionChange(sessionId)
            onPendingInviteChange(null)
            applySnapshotResult(runtime.readSession(sessionId))
        }
    }

    suspend fun restoreInvite(code: String) {
        restoredPendingInviteCode = code
        when (val result = runtime.readFriendInvite(code)) {
            is OnlineClientResult.Success -> when (result.value.status) {
                OnlineFriendInviteStatus.WAITING -> state = OnlineDuelUiState.WaitingForFriend(result.value)
                OnlineFriendInviteStatus.MATCHED -> openInviteSession(result.value)
                OnlineFriendInviteStatus.EXPIRED -> {
                    onPendingInviteChange(null)
                    state = OnlineDuelUiState.Error(strings.text("social.online.error.invite_expired"))
                }
            }
            else -> {
                if (result == OnlineClientResult.MembershipRejected || result == OnlineClientResult.InvalidResponse) {
                    onPendingInviteChange(null)
                }
                state = inviteError(result)
            }
        }
    }

    suspend fun acceptIncomingInvite(code: String) {
        when (val result = runtime.acceptFriendInvite(code)) {
            is OnlineClientResult.Success -> {
                if (result.value.sessionId != null) completedAutoAcceptCode = code
                openInviteSession(result.value)
            }
            else -> state = inviteError(result)
        }
    }

    suspend fun resumeSession(sessionId: String) {
        restoredSessionId = sessionId
        state = applySnapshotResult(runtime.readSession(sessionId))
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
                    OnlineDuelUiState.Error(strings.text("social.online.error.match_missing"))
                } else {
                    restoredSessionId = sessionId
                    onActiveSessionChange(sessionId)
                    applySnapshotResult(runtime.readSession(sessionId))
                }
            }
            else -> inviteError(ticket)
        }
    }

    fun createPrivateInvite() {
        if (state != OnlineDuelUiState.Ready) return
        state = OnlineDuelUiState.Loading(strings.text("social.online.creating_invite"))
        scope.launch {
            state = when (
                val result = runtime.createFriendInvite(
                    playStyle = selectedPlayStyle,
                    codeLength = selectedCodeLength,
                    targetPlayerId = targetPlayerId,
                )
            ) {
                is OnlineClientResult.Success -> {
                    restoredPendingInviteCode = result.value.inviteCode
                    onPendingInviteChange(result.value.inviteCode)
                    OnlineDuelUiState.WaitingForFriend(result.value)
                }
                else -> inviteError(result)
            }
        }
    }

    fun acceptPrivateInvite() {
        if (state != OnlineDuelUiState.Ready || inviteCode.length != FriendInviteCodeLength) return
        val submittedCode = inviteCode
        state = OnlineDuelUiState.Loading(strings.text("social.online.joining_friend"))
        scope.launch {
            when (val result = runtime.acceptFriendInvite(submittedCode)) {
                is OnlineClientResult.Success -> openInviteSession(result.value)
                else -> state = inviteError(result)
            }
        }
    }

    fun retryCurrentOperation() {
        val incomingCode = autoAcceptInviteCode
        val sessionId = initialSessionId
        val pendingCode = recoveryPendingInviteCode
        when {
            incomingCode != null && completedAutoAcceptCode != incomingCode -> {
                attemptedAutoAcceptCode = incomingCode
                state = OnlineDuelUiState.Loading(strings.text("social.online.joining_friend"))
                scope.launch { acceptIncomingInvite(incomingCode) }
            }
            sessionId != null -> {
                state = OnlineDuelUiState.Loading(strings.text("social.online.restoring"))
                scope.launch { resumeSession(sessionId) }
            }
            pendingCode != null -> {
                state = OnlineDuelUiState.Loading(strings.text("social.online.restoring"))
                scope.launch { restoreInvite(pendingCode) }
            }
            else -> state = OnlineDuelUiState.Ready
        }
    }

    fun returnFromActiveGame() {
        guessSubmission.cancel()
        onActiveSessionChange(null)
        state = OnlineDuelUiState.Ready
    }

    LaunchedEffect(autoStartQuickMatch) {
        if (shouldAutoStartQuickMatch(entryPoint, initialSessionId, autoStartQuickMatch) && !attemptedAutoStart) {
            attemptedAutoStart = true
            startQuickMatch()
        }
    }

    LaunchedEffect(requestBack) {
        if (!requestBack) return@LaunchedEffect
        val current = state
        when (onlineDuelBackTarget((current as? OnlineDuelUiState.Playing)?.snapshot?.phase)) {
            OnlineDuelBackTarget.MATCH_SETUP -> returnFromActiveGame()
            OnlineDuelBackTarget.SOCIAL_ROOT -> onExitDestination()
        }
        onBackRequestConsumed()
    }

    BackHandler(
        enabled = entryPoint == OnlineDuelEntryPoint.INVITES &&
            joinCodeScreenOpen && state == OnlineDuelUiState.Ready,
    ) {
        joinCodeScreenOpen = false
    }

    LaunchedEffect(initialSessionId, recoveryPendingInviteCode, autoAcceptInviteCode) {
        val incomingCode = autoAcceptInviteCode
        val sessionId = initialSessionId
        when {
            incomingCode != null && attemptedAutoAcceptCode != incomingCode -> {
                attemptedAutoAcceptCode = incomingCode
                state = OnlineDuelUiState.Loading(strings.text("social.online.joining_friend"))
                scope.launch { acceptIncomingInvite(incomingCode) }
            }
            entryPoint == OnlineDuelEntryPoint.QUICK_MATCH &&
                sessionId != null && restoredSessionId != sessionId -> {
                state = OnlineDuelUiState.Loading(strings.text("social.online.restoring"))
                scope.launch { resumeSession(sessionId) }
            }
            recoveryPendingInviteCode != null &&
                restoredPendingInviteCode != recoveryPendingInviteCode -> {
                state = OnlineDuelUiState.Loading(strings.text("social.online.restoring"))
                scope.launch { restoreInvite(recoveryPendingInviteCode) }
            }
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
                            OnlineFriendInviteStatus.EXPIRED -> {
                                onPendingInviteChange(null)
                                state = OnlineDuelUiState.Error(
                                    strings.text("social.online.error.invite_expired"),
                                )
                            }
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
                            onActiveSessionChange(
                                result.value.sessionId.takeUnless { result.value.phase == "finished" },
                            )
                            if (result.value.revision != current.snapshot.revision) {
                                state = OnlineDuelUiState.Playing(result.value)
                            }
                        }
                        OnlineClientResult.Offline,
                        OnlineClientResult.TemporarilyUnavailable,
                        -> Unit
                        else -> state = applySnapshotResult(result)
                    }
                }
            }

            else -> Unit
        }
    }

    val playing = state as? OnlineDuelUiState.Playing
    if (playing?.snapshot?.phase == "active") {
        val snapshot = playing.snapshot
        val knownPlayerGuesses = snapshot.knownPlayerGuesses()
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
                            state = if (result == OnlineClientResult.RevisionConflict) {
                                applySnapshotResult(runtime.readSession(snapshot.sessionId))
                            } else {
                                applySnapshotResult(result)
                            }
                        } finally {
                            guessSubmission.finish(operationId)
                        }
                    }
                }
            },
            onBack = ::returnFromActiveGame,
        )
        return
    }

    if (entryPoint != OnlineDuelEntryPoint.QUICK_MATCH) {
        val screenTitle = if (entryPoint == OnlineDuelEntryPoint.FRIEND) {
            targetDisplayName
                ?.takeIf(String::isNotBlank)
                ?.let { name -> strings.text("social.friend.match.title").replace("{name}", name) }
                ?: strings.text("social.friend.match.title.generic")
        } else {
            strings.text("social.invites")
        }
        when (val current = state) {
            OnlineDuelUiState.Ready -> {
                if (entryPoint == OnlineDuelEntryPoint.INVITES && joinCodeScreenOpen) {
                    SocialJoinCodeReferenceContent(
                        inviteCode = inviteCode,
                        busy = false,
                        onInviteCodeChange = { inviteCode = normalizeFriendInviteCode(it) },
                        onJoin = ::acceptPrivateInvite,
                        onCreateOwnCode = {
                            joinCodeScreenOpen = false
                            createPrivateInvite()
                        },
                    )
                } else {
                    SocialInvitationsReferenceContent(
                        selectedPlayStyle = selectedPlayStyle,
                        selectedCodeLength = selectedCodeLength,
                        targetDisplayName = targetDisplayName,
                        friendEntryPoint = entryPoint == OnlineDuelEntryPoint.FRIEND,
                        onPlayStyleChange = { selectedPlayStyleName = it.name },
                        onCodeLengthChange = { selectedCodeLength = normalizeOnlineCodeLength(it) },
                        onCreateCode = ::createPrivateInvite,
                        onOpenJoinCode = { joinCodeScreenOpen = true },
                    )
                }
                return
            }

            is OnlineDuelUiState.Loading -> {
                SocialInviteOperationReferenceContent(
                    title = screenTitle,
                    message = current.message,
                    error = false,
                    onRetry = null,
                )
                return
            }

            is OnlineDuelUiState.WaitingForFriend -> {
                SocialInviteWaitingReferenceContent(
                    invite = current.invite,
                    title = screenTitle,
                    notice = inviteNotice,
                    onCopy = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(
                                ClipData.newPlainText(
                                    strings.text("social.invite.clipboard_label"),
                                    current.invite.inviteCode,
                                ),
                            )
                        inviteNotice = strings.text("social.invite.copied")
                    },
                    onShare = {
                        val shareText = formatFriendInviteShareText(
                            strings.text("social.invite.share_text"),
                            current.invite.inviteCode,
                        )
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(
                            Intent.createChooser(sendIntent, strings.text("social.invite.share_title")),
                        )
                    },
                    onCancel = {
                        onPendingInviteChange(null)
                        joinCodeScreenOpen = false
                        state = OnlineDuelUiState.Ready
                    },
                )
                return
            }

            is OnlineDuelUiState.Error -> {
                SocialInviteOperationReferenceContent(
                    title = screenTitle,
                    message = current.message,
                    error = true,
                    onRetry = ::retryCurrentOperation,
                )
                return
            }

            is OnlineDuelUiState.Playing -> Unit
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
                text = when (entryPoint) {
                    OnlineDuelEntryPoint.FRIEND -> targetDisplayName
                        ?.takeIf(String::isNotBlank)
                        ?.let { name ->
                            strings.text("social.friend.match.title").replace("{name}", name)
                        }
                        ?: strings.text("social.friend.match.title.generic")
                    OnlineDuelEntryPoint.INVITES -> strings.text("social.invites")
                    OnlineDuelEntryPoint.QUICK_MATCH -> strings.text("social.match.title")
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = strings.text(
                    when (entryPoint) {
                        OnlineDuelEntryPoint.FRIEND -> "social.friend.match.description"
                        OnlineDuelEntryPoint.INVITES -> "social.invites.screen_description"
                        OnlineDuelEntryPoint.QUICK_MATCH -> "social.online.screen_description"
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        when (val current = state) {
            OnlineDuelUiState.Ready -> SceneCard {
                Text(strings.text("social.online.settings"), fontWeight = FontWeight.SemiBold)
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
                Text(strings.text("social.online.secret_length"), style = MaterialTheme.typography.labelLarge)
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
                        text = formatOnlineCodeLength(strings, selectedCodeLength),
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
                    strings.text("social.online.rules"),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (entryPoint == OnlineDuelEntryPoint.QUICK_MATCH) {
                    Text(strings.text("social.online.find_opponent"), fontWeight = FontWeight.SemiBold)
                    Text(
                        strings.text("social.online.bot_fallback"),
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
                        Text(strings.text("social.online.find_match"))
                    }
                }

                if (entryPoint != OnlineDuelEntryPoint.QUICK_MATCH) {
                    Text(strings.text("social.online.play_friend"), fontWeight = FontWeight.SemiBold)
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            state = OnlineDuelUiState.Loading(strings.text("social.online.creating_invite"))
                            scope.launch {
                                state = when (
                                    val result = runtime.createFriendInvite(
                                        playStyle = selectedPlayStyle,
                                        codeLength = selectedCodeLength,
                                        targetPlayerId = targetPlayerId,
                                    )
                                ) {
                                    is OnlineClientResult.Success -> {
                                        onPendingInviteChange(result.value.inviteCode)
                                        OnlineDuelUiState.WaitingForFriend(result.value)
                                    }
                                    else -> inviteError(result)
                                }
                            }
                        },
                    ) {
                        Text(
                            strings.text(
                                if (entryPoint == OnlineDuelEntryPoint.FRIEND) {
                                    "social.online.send_invite"
                                } else {
                                    "social.online.create_code"
                                },
                            ),
                        )
                    }
                    if (entryPoint == OnlineDuelEntryPoint.INVITES) {
                        OutlinedTextField(
                            value = inviteCode,
                            onValueChange = { value ->
                                inviteCode = normalizeFriendInviteCode(value)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(strings.text("social.online.friend_code")) },
                            singleLine = true,
                        )
                        OutlinedButton(
                            enabled = inviteCode.length == FriendInviteCodeLength,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val submittedCode = inviteCode
                                state = OnlineDuelUiState.Loading(
                                    strings.text("social.online.joining_friend"),
                                )
                                scope.launch {
                                    when (val result = runtime.acceptFriendInvite(submittedCode)) {
                                        is OnlineClientResult.Success -> openInviteSession(result.value)
                                        else -> state = inviteError(result)
                                    }
                                }
                            },
                        ) {
                            Text(strings.text("social.online.join_code"))
                        }
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
                Text(
                    strings.text(
                        if (entryPoint == OnlineDuelEntryPoint.FRIEND) {
                            "social.online.invite_sent"
                        } else {
                            "social.online.share_code"
                        },
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = current.invite.inviteCode,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = InplaceXColors.Cobalt,
                )
                Text(strings.text("social.online.waiting_phone"))
                Text(
                    formatOnlineText(
                        strings,
                        "social.online.invite_summary",
                        "style" to current.invite.playStyle.displayName(strings),
                        "length" to formatOnlineCodeLength(strings, current.invite.codeLength),
                    ),
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
                    onClick = {
                        onPendingInviteChange(null)
                        state = OnlineDuelUiState.Ready
                    },
                ) {
                    Text(strings.text("social.online.cancel"))
                }
            }

            is OnlineDuelUiState.Error -> SceneCard {
                Text(current.message, color = MaterialTheme.colorScheme.error)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = ::retryCurrentOperation,
                ) {
                    Text(strings.text("social.online.retry"))
                }
            }

            is OnlineDuelUiState.Playing -> {
                val snapshot = current.snapshot
                SceneCard {
                    Text(
                        text = when (snapshot.phase) {
                            "setup" -> if (snapshot.playerSecretConfigured) {
                                strings.text("social.online.setup.wait_opponent")
                            } else {
                                strings.text("social.online.setup.enter_secret")
                            }
                            "active" -> if (snapshot.playStyle == RemoteFriendPlayStyle.RACE) {
                                strings.text("social.match.timed.started")
                            } else if (snapshot.currentTurn == "player") {
                                strings.text("social.duel.your_turn")
                            } else {
                                strings.text("social.online.opponent_turn")
                            }
                            "finished" -> when {
                                snapshot.finishReason == "time_expired" ->
                                    strings.text("social.online.result.time_expired")
                                snapshot.winner == "player" -> strings.text("social.online.result.player_won")
                                snapshot.winner == "opponent" -> strings.text("social.online.result.opponent_won")
                                else -> strings.text("social.online.result.finished")
                            }
                            else -> strings.text("social.online.match")
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        formatOnlineText(
                            strings,
                            "social.online.match_summary",
                            "style" to snapshot.playStyle.displayName(strings),
                            "length" to formatOnlineCodeLength(strings, snapshot.codeLength),
                            "attempts" to snapshot.attempts.count { it.actor == "player" }.toString(),
                        ),
                    )
                    snapshot.attempts.takeLast(8).forEach { attempt ->
                        Text(
                            if (attempt.actor == "player") {
                                formatOnlineText(
                                    strings,
                                    "social.online.attempt.player",
                                    "number" to attempt.number.toString(),
                                    "exact" to attempt.exactMatches.toString(),
                                )
                            } else {
                                formatOnlineText(
                                    strings,
                                    "social.online.attempt.opponent",
                                    "number" to attempt.number.toString(),
                                    "exact" to attempt.exactMatches.toString(),
                                )
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
                                Text(
                                    strings.text(
                                        if (snapshot.phase == "setup") {
                                            "social.online.secret"
                                        } else {
                                            "social.online.combination"
                                        },
                                    ),
                                )
                            },
                            supportingText = {
                                if (digits.maximumConsecutiveRun() > 3) {
                                    Text(strings.text("social.online.duplicate_error"))
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
                                state = OnlineDuelUiState.Loading(strings.text("social.online.syncing"))
                                scope.launch {
                                    val result = if (snapshot.phase == "setup") {
                                        runtime.submitSecret(snapshot.sessionId, snapshot.revision, submitted)
                                    } else {
                                        runtime.submitGuess(snapshot.sessionId, snapshot.revision, submitted)
                                    }
                                    state = if (result == OnlineClientResult.RevisionConflict) {
                                        applySnapshotResult(runtime.readSession(snapshot.sessionId))
                                    } else {
                                        applySnapshotResult(result)
                                    }
                                }
                            },
                        ) {
                            Text(
                                strings.text(
                                    if (snapshot.phase == "setup") {
                                        "social.online.save_secret"
                                    } else {
                                        "social.online.confirm_move"
                                    },
                                ),
                            )
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
                                    strings.text("social.online.opponent_setting_secret")
                                } else {
                                    strings.text("social.online.waiting_opponent_move")
                                },
                            )
                        }
                    }
                }

                if (snapshot.phase == "finished") {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onActiveSessionChange(null)
                            state = OnlineDuelUiState.Ready
                        },
                    ) {
                        Text(strings.text("social.online.new_match"))
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
internal const val FriendInviteCodeLength = 8
private const val FriendInviteAlphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
internal const val MinimumOnlineCodeLength = 4
internal const val MaximumOnlineCodeLength = 10

internal fun normalizeOnlineCodeLength(value: Int): Int =
    value.coerceIn(MinimumOnlineCodeLength, MaximumOnlineCodeLength)

internal fun OnlineDuelSnapshotState.knownPlayerGuesses(): Map<Int, String> =
    attempts.mapNotNull { attempt ->
        attempt.ownGuess?.let { attempt.number to it }
    }.toMap()

internal fun normalizeFriendInviteCode(value: String): String =
    value
        .uppercase()
        .filter { it in FriendInviteAlphabet }
        .take(FriendInviteCodeLength)

internal fun formatFriendInviteShareText(template: String, code: String): String =
    template.replace("{code}", code)

internal fun formatOnlineCodeLength(strings: LocalizationProvider, count: Int): String =
    formatOnlineText(
        strings,
        if (count == 4) "social.online.code_length.four" else "social.online.code_length.other",
        "count" to count.toString(),
    )

private fun formatOnlineText(
    strings: LocalizationProvider,
    key: String,
    vararg replacements: Pair<String, String>,
): String = replacements.fold(strings.text(key)) { text, (placeholder, value) ->
    text.replace("{$placeholder}", value)
}
