package com.mirkori.inplacex.ui.screens.social

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.data.local.LocalSocialRelationship
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.online.OnlineFriendInvite
import com.mirkori.inplacex.platform.online.OnlineRuntime
import com.mirkori.inplacex.platform.online.RemoteFriendPlayStyle
import com.mirkori.inplacex.platform.mirkori.MirkoriPlayerSearchResult
import com.mirkori.inplacex.platform.mirkori.MirkoriPublicPlayerProfile
import com.mirkori.inplacex.platform.mirkori.MirkoriFriendOperationResult
import com.mirkori.inplacex.platform.mirkori.MirkoriFriendRequest
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import com.mirkori.inplacex.ui.screens.shared.SceneActionTile
import com.mirkori.inplacex.ui.screens.shared.ScenePageColumn
import com.mirkori.inplacex.ui.screens.shared.PlayerAvatar
import com.mirkori.inplacex.ui.theme.InplaceXColors
import kotlinx.coroutines.launch

@Composable
fun SocialRootScreen(
    onlineRuntime: OnlineRuntime? = null,
    initialActiveSessionId: String? = null,
    onActiveSessionChange: (String?) -> Unit = {},
    friends: List<LocalSocialRelationship> = emptyList(),
    pendingFriendRequests: List<LocalSocialRelationship> = emptyList(),
    currentPlayerId: String? = null,
    onSearchPlayers: suspend (String) -> MirkoriPlayerSearchResult = {
        MirkoriPlayerSearchResult.Unavailable
    },
    onAddFriend: suspend (MirkoriPublicPlayerProfile) -> MirkoriFriendOperationResult = {
        MirkoriFriendOperationResult.Unavailable
    },
    showTestFriendBot: Boolean = false,
    incomingFriendRequests: List<MirkoriFriendRequest> = emptyList(),
    onAcceptFriendRequest: suspend (MirkoriFriendRequest) -> MirkoriFriendOperationResult = {
        MirkoriFriendOperationResult.Unavailable
    },
    incomingInvites: List<OnlineFriendInvite> = emptyList(),
    requestedQuickMatchPlayStyle: RemoteFriendPlayStyle? = null,
    requestedQuickMatchCodeLength: Int = 4,
    onQuickMatchRequestConsumed: () -> Unit = {},
    requestExitGame: Boolean = false,
    onExitGameConsumed: () -> Unit = {},
    onInGameChange: (Boolean) -> Unit = {},
    onNestedScreenChange: (Boolean) -> Unit = {},
) {
    val strings = LocalAppStrings.current
    var activeDestination by remember {
        mutableStateOf<SocialDestination?>(
            if (initialActiveSessionId == null) null else SocialDestination.ONLINE_MATCH,
        )
    }
    var selectedFriend by remember { mutableStateOf<LocalSocialRelationship?>(null) }
    var autoAcceptInviteCode by remember { mutableStateOf<String?>(null) }
    var quickMatchPlayStyle by remember { mutableStateOf(RemoteFriendPlayStyle.RACE) }
    var quickMatchCodeLength by remember { mutableStateOf(4) }

    LaunchedEffect(activeDestination) {
        onNestedScreenChange(activeDestination != null)
        onInGameChange(
            activeDestination == SocialDestination.FRIEND_MATCH ||
                activeDestination == SocialDestination.ONLINE_MATCH,
        )
    }
    LaunchedEffect(onlineRuntime, initialActiveSessionId) {
        if (initialActiveSessionId != null && onlineRuntime == null) {
            onActiveSessionChange(null)
            activeDestination = null
        }
    }
    LaunchedEffect(requestedQuickMatchPlayStyle, requestedQuickMatchCodeLength, onlineRuntime) {
        val requestedPlayStyle = requestedQuickMatchPlayStyle ?: return@LaunchedEffect

        if (onlineRuntime != null) {
            quickMatchPlayStyle = requestedPlayStyle
            quickMatchCodeLength = normalizeOnlineCodeLength(requestedQuickMatchCodeLength)
            activeDestination = SocialDestination.ONLINE_MATCH
        }
        onQuickMatchRequestConsumed()
    }
    LaunchedEffect(requestExitGame) {
        if (requestExitGame) {
            onActiveSessionChange(null)
            activeDestination = null
            onExitGameConsumed()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            onNestedScreenChange(false)
            onInGameChange(false)
        }
    }
    BackHandler(enabled = activeDestination == SocialDestination.FRIENDS) {
        activeDestination = null
    }

    if (activeDestination == SocialDestination.FRIENDS) {
        SocialFriendsScreen(
            friends = friends,
            pendingFriendRequests = pendingFriendRequests,
            currentPlayerId = currentPlayerId,
            onSearchPlayers = onSearchPlayers,
            onAddFriend = onAddFriend,
            showTestFriendBot = showTestFriendBot,
            incomingFriendRequests = incomingFriendRequests,
            onAcceptFriendRequest = onAcceptFriendRequest,
            onlineConfigured = onlineRuntime != null,
            incomingInvites = incomingInvites,
            onAcceptInvite = { invite ->
                selectedFriend = null
                autoAcceptInviteCode = invite.inviteCode
                activeDestination = SocialDestination.FRIEND_MATCH
            },
            onPlayTestFriend = {
                selectedFriend = null
                autoAcceptInviteCode = null
                quickMatchPlayStyle = RemoteFriendPlayStyle.RACE
                activeDestination = SocialDestination.ONLINE_MATCH
            },
            onPlayFriend = { friend ->
                selectedFriend = friend
                autoAcceptInviteCode = null
                activeDestination = SocialDestination.FRIEND_MATCH
            },
        )
        return
    }

    if (
        activeDestination in setOf(
            SocialDestination.INVITES,
            SocialDestination.FRIEND_MATCH,
            SocialDestination.ONLINE_MATCH,
        ) &&
        onlineRuntime != null
    ) {
        OnlineDuelScreen(
            runtime = onlineRuntime,
            initialSessionId = initialActiveSessionId,
            onActiveSessionChange = onActiveSessionChange,
            entryPoint = when (activeDestination) {
                SocialDestination.INVITES -> OnlineDuelEntryPoint.INVITES
                SocialDestination.FRIEND_MATCH -> OnlineDuelEntryPoint.FRIEND
                else -> OnlineDuelEntryPoint.QUICK_MATCH
            },
            initialPlayStyle = quickMatchPlayStyle,
            initialCodeLength = quickMatchCodeLength,
            targetPlayerId = selectedFriend?.targetPlayerId
                .takeIf { activeDestination == SocialDestination.FRIEND_MATCH },
            targetDisplayName = selectedFriend?.targetDisplayName
                .takeIf { activeDestination == SocialDestination.FRIEND_MATCH },
            autoAcceptInviteCode = autoAcceptInviteCode
                .takeIf { activeDestination == SocialDestination.FRIEND_MATCH },
        )
        return
    }

    FriendsReferenceScreen(
        friends = friends,
        incomingFriendRequests = incomingFriendRequests,
        onlineConfigured = onlineRuntime != null,
        incomingInviteCount = incomingInvites.size,
        onOpenFriends = { activeDestination = SocialDestination.FRIENDS },
        onInvite = {
            selectedFriend = null
            autoAcceptInviteCode = null
            activeDestination = SocialDestination.INVITES
        },
        onFindMatch = {
            quickMatchPlayStyle = RemoteFriendPlayStyle.RACE
            activeDestination = SocialDestination.ONLINE_MATCH
        },
        onAcceptFriendRequest = onAcceptFriendRequest,
    )
}

private enum class SocialDestination {
    FRIENDS,
    FRIEND_MATCH,
    INVITES,
    ONLINE_MATCH,
}

@Composable
private fun SocialFriendsScreen(
    friends: List<LocalSocialRelationship>,
    pendingFriendRequests: List<LocalSocialRelationship>,
    currentPlayerId: String?,
    onSearchPlayers: suspend (String) -> MirkoriPlayerSearchResult,
    onAddFriend: suspend (MirkoriPublicPlayerProfile) -> MirkoriFriendOperationResult,
    showTestFriendBot: Boolean,
    incomingFriendRequests: List<MirkoriFriendRequest>,
    onAcceptFriendRequest: suspend (MirkoriFriendRequest) -> MirkoriFriendOperationResult,
    onlineConfigured: Boolean,
    incomingInvites: List<OnlineFriendInvite>,
    onAcceptInvite: (OnlineFriendInvite) -> Unit,
    onPlayTestFriend: () -> Unit,
    onPlayFriend: (LocalSocialRelationship) -> Unit,
) {
    val strings = LocalAppStrings.current
    val coroutineScope = rememberCoroutineScope()
    var addFriendDialogOpen by remember { mutableStateOf(false) }
    var friendQuery by remember { mutableStateOf("") }
    var searchInProgress by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<MirkoriPublicPlayerProfile>>(emptyList()) }
    var addFriendResultKey by remember { mutableStateOf<String?>(null) }
    var friendOperationInProgress by remember { mutableStateOf(false) }

    if (addFriendDialogOpen) {
        AlertDialog(
            onDismissRequest = { if (!searchInProgress) addFriendDialogOpen = false },
            title = { Text(strings.text("social.friend.add.title")) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = friendQuery,
                        onValueChange = {
                            friendQuery = it.take(64)
                            addFriendResultKey = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(strings.text("social.friend.search.query")) },
                    )
                    Button(
                        onClick = {
                            val query = friendQuery.trim()
                            if (query.isEmpty()) {
                                addFriendResultKey = "social.friend.search.invalid"
                            } else {
                                searchInProgress = true
                                addFriendResultKey = null
                                coroutineScope.launch {
                                    when (val result = onSearchPlayers(query)) {
                                        is MirkoriPlayerSearchResult.Success -> {
                                            searchResults = result.players.filterNot { player ->
                                                player.gamePlayerId == currentPlayerId
                                            }
                                            addFriendResultKey = if (searchResults.isEmpty()) {
                                                "social.friend.search.empty"
                                            } else {
                                                null
                                            }
                                        }
                                        MirkoriPlayerSearchResult.Rejected ->
                                            addFriendResultKey = "social.friend.search.invalid"
                                        MirkoriPlayerSearchResult.Unavailable ->
                                            addFriendResultKey = "social.friend.search.unavailable"
                                    }
                                    searchInProgress = false
                                }
                            }
                        },
                        enabled = !searchInProgress && currentPlayerId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (searchInProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(strings.text("social.friend.search.action"))
                        }
                    }
                    addFriendResultKey?.let { Text(strings.text(it)) }
                    searchResults.forEach { player ->
                        PlayerSearchResultCard(
                            player = player,
                            alreadyAdded = friends.any { it.targetPlayerId == player.gamePlayerId },
                            requestPending = pendingFriendRequests.any {
                                it.targetPlayerId == player.gamePlayerId
                            },
                            enabled = !friendOperationInProgress,
                            onAdd = {
                                friendOperationInProgress = true
                                coroutineScope.launch {
                                    addFriendResultKey = when (onAddFriend(player)) {
                                        is MirkoriFriendOperationResult.Success -> "social.friend.request.sent"
                                        MirkoriFriendOperationResult.Rejected -> "social.friend.request.rejected"
                                        MirkoriFriendOperationResult.Unavailable -> "social.friend.request.unavailable"
                                    }
                                    friendOperationInProgress = false
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = {
                OutlinedButton(
                    onClick = { addFriendDialogOpen = false },
                    enabled = !searchInProgress,
                ) {
                    Text(strings.text("social.friend.search.close"))
                }
            },
        )
    }
    ScenePageColumn(
        modifier = Modifier.fillMaxSize(),
        scrollable = true,
    ) {
        SceneCard(
            accentColor = InplaceXColors.ToyPurple,
            contentColor = Color.White,
        ) {
            Text(
                text = strings.text("social.friends"),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(strings.text("social.friends.subtitle"))
        }

        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            onClick = {
                friendQuery = ""
                searchResults = emptyList()
                addFriendResultKey = null
                addFriendDialogOpen = true
            },
            enabled = currentPlayerId != null,
        ) {
            Icon(Icons.Outlined.PersonAdd, contentDescription = null)
            Text(strings.text("social.friend.add.title"), modifier = Modifier.padding(start = 8.dp))
        }

        incomingFriendRequests.forEach { request ->
            FriendCard(
                title = request.player.displayName,
                subtitle = strings.text("social.friend.request.incoming"),
                actionLabelKey = "social.friend.request.accept",
                showPlay = true,
                playEnabled = !friendOperationInProgress,
                onPlay = {
                    friendOperationInProgress = true
                    coroutineScope.launch {
                        addFriendResultKey = when (onAcceptFriendRequest(request)) {
                            is MirkoriFriendOperationResult.Success -> "social.friend.request.accepted"
                            MirkoriFriendOperationResult.Rejected -> "social.friend.request.rejected"
                            MirkoriFriendOperationResult.Unavailable -> "social.friend.request.unavailable"
                        }
                        friendOperationInProgress = false
                    }
                },
            )
        }

        pendingFriendRequests.forEach { request ->
            FriendCard(
                title = request.targetDisplayName,
                subtitle = strings.text("social.friend.request.sent"),
            )
        }

        incomingInvites.forEach { invite ->
            FriendCard(
                title = strings.text("social.invites.incoming.title"),
                subtitle = strings.text(
                    if (invite.playStyle == RemoteFriendPlayStyle.RACE) {
                        "social.match.timed"
                    } else {
                        "social.match.turn_based"
                    },
                ),
                actionLabelKey = "social.invites.accept",
                showPlay = true,
                playEnabled = onlineConfigured,
                onPlay = { onAcceptInvite(invite) },
            )
        }

        if (showTestFriendBot) {
            FriendCard(
                title = strings.text("social.test_friend.title"),
                subtitle = strings.text(
                    if (onlineConfigured) {
                        "social.test_friend.subtitle"
                    } else {
                        "social.test_friend.offline"
                    },
                ),
                showPlay = true,
                playEnabled = onlineConfigured,
                onPlay = onPlayTestFriend,
            )
        }

        friends.forEach { friend ->
            FriendCard(
                title = friend.targetDisplayName,
                subtitle = friend.note?.takeIf(String::isNotBlank)?.let { "@$it" }
                    ?: strings.text("social.friend.id_fallback")
                        .replace("{id}", friend.targetPlayerId.takeLast(8)),
                showPlay = true,
                playEnabled = onlineConfigured,
                onPlay = { onPlayFriend(friend) },
            )
        }

        if (
            friends.isEmpty() &&
            pendingFriendRequests.isEmpty() &&
            incomingFriendRequests.isEmpty() &&
            !showTestFriendBot
        ) {
            SocialEmptyCard(
                title = strings.text("social.friends"),
                message = strings.text("social.friends.empty"),
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Group,
                        contentDescription = null,
                    )
                },
            )
        }
    }
}

@Composable
private fun PlayerSearchResultCard(
    player: MirkoriPublicPlayerProfile,
    alreadyAdded: Boolean,
    requestPending: Boolean,
    enabled: Boolean,
    onAdd: () -> Unit,
) {
    val strings = LocalAppStrings.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = InplaceXColors.SurfaceMuted,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PlayerAvatar(
                displayName = player.displayName,
                avatarUrl = player.avatarUrl,
                modifier = Modifier.size(44.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(player.displayName, fontWeight = FontWeight.Bold)
                Text(
                    player.handle?.let { "@$it" }
                        ?: strings.text("social.friend.id_fallback")
                            .replace("{id}", player.gamePlayerId.takeLast(8)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onAdd,
                enabled = enabled && !alreadyAdded && !requestPending,
            ) {
                Text(
                    strings.text(
                        when {
                            alreadyAdded -> "social.friend.add.added"
                            requestPending -> "social.friend.request.sent.short"
                            else -> "social.friend.add.action"
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun FriendCard(
    title: String,
    subtitle: String,
    showPlay: Boolean = false,
    playEnabled: Boolean = false,
    onPlay: () -> Unit = {},
    actionLabelKey: String = "social.test_friend.play",
) {
    val strings = LocalAppStrings.current
    SceneCard(accentColor = InplaceXColors.ToyCream.copy(alpha = 0.96f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = InplaceXColors.ToyPurple.copy(alpha = 0.16f),
            ) {
                Icon(
                    imageVector = Icons.Outlined.SmartToy,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                    tint = InplaceXColors.ToyPurple,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showPlay) {
                Button(onClick = onPlay, enabled = playEnabled) {
                    Text(strings.text(actionLabelKey))
                }
            }
        }
    }
}

@Composable
private fun SocialAvailabilityBanner(
    onlineConfigured: Boolean,
) {
    val strings = LocalAppStrings.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(16.dp),
        color = InplaceXColors.SurfaceMuted,
        border = BorderStroke(1.dp, InplaceXColors.Cyan.copy(alpha = 0.42f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (onlineConfigured) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                contentDescription = null,
                tint = InplaceXColors.Cobalt,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (onlineConfigured) {
                        strings.text("social.status.available")
                    } else {
                        strings.text("social.status.preparing")
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (onlineConfigured) {
                        strings.text("social.status.available.description")
                    } else {
                        strings.text("social.status.preparing.description")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SocialEmptyCard(
    title: String,
    message: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    SceneCard(
        modifier = modifier,
        accentColor = InplaceXColors.ToyCream.copy(alpha = 0.94f),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = InplaceXColors.SurfaceMuted,
            contentColor = InplaceXColors.Cobalt,
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
        }
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
