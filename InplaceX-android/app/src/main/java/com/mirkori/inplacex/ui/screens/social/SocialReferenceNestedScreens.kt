package com.mirkori.inplacex.ui.screens.social

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.mirkori.inplacex.R
import com.mirkori.inplacex.data.local.LocalSocialRelationship
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.mirkori.MirkoriFriendRequest
import com.mirkori.inplacex.platform.online.OnlineFriendInvite
import com.mirkori.inplacex.platform.online.RemoteFriendPlayStyle
import com.mirkori.inplacex.ui.screens.shared.PlayerAvatar
import kotlinx.coroutines.delay

private data class SocialFriendRow(
    val stableId: String,
    val title: String,
    val subtitle: String,
    val avatarUrl: String? = null,
    val actionLabel: String? = null,
    val actionEnabled: Boolean = false,
    val actionTag: String? = null,
    val onAction: () -> Unit = {},
)

@Composable
internal fun SocialFriendsReferenceContent(
    friends: List<LocalSocialRelationship>,
    pendingFriendRequests: List<LocalSocialRelationship>,
    incomingFriendRequests: List<MirkoriFriendRequest>,
    incomingInvites: List<OnlineFriendInvite>,
    showTestFriendBot: Boolean,
    onlineConfigured: Boolean,
    operationBusy: Boolean,
    operationMessage: String?,
    addFriendEnabled: Boolean,
    onOpenAddFriend: () -> Unit,
    onAcceptFriendRequest: (MirkoriFriendRequest) -> Unit,
    onAcceptInvite: (OnlineFriendInvite) -> Unit,
    onPlayTestFriend: () -> Unit,
    onPlayFriend: (LocalSocialRelationship) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val rows = buildList {
        incomingFriendRequests.forEach { request ->
            add(
                SocialFriendRow(
                    stableId = "request-${request.requestId}",
                    title = request.player.displayName,
                    subtitle = strings.text("social.friend.request.incoming"),
                    avatarUrl = request.player.avatarUrl,
                    actionLabel = strings.text("social.friend.request.accept"),
                    actionEnabled = !operationBusy,
                    actionTag = "social-friend-request-accept",
                    onAction = { onAcceptFriendRequest(request) },
                ),
            )
        }
        pendingFriendRequests.forEach { request ->
            add(
                SocialFriendRow(
                    stableId = "pending-${request.targetPlayerId}",
                    title = request.targetDisplayName,
                    subtitle = strings.text("social.friend.request.sent"),
                    actionLabel = strings.text("social.friend.request.sent.short"),
                ),
            )
        }
        incomingInvites.forEach { invite ->
            add(
                SocialFriendRow(
                    stableId = "invite-${invite.inviteCode}",
                    title = strings.text("social.invites.incoming.title"),
                    subtitle = strings.text(
                        if (invite.playStyle == RemoteFriendPlayStyle.RACE) {
                            "social.match.timed"
                        } else {
                            "social.match.turn_based"
                        },
                    ),
                    actionLabel = strings.text("social.invites.accept"),
                    actionEnabled = onlineConfigured && !operationBusy,
                    actionTag = "social-incoming-invite-accept",
                    onAction = { onAcceptInvite(invite) },
                ),
            )
        }
        if (showTestFriendBot) {
            add(
                SocialFriendRow(
                    stableId = "test-friend-bot",
                    title = strings.text("social.test_friend.title"),
                    subtitle = strings.text("social.test_friend.subtitle"),
                    actionLabel = strings.text("social.test_friend.play"),
                    actionEnabled = onlineConfigured && !operationBusy,
                    actionTag = "social-test-friend-play",
                    onAction = onPlayTestFriend,
                ),
            )
        }
        friends.forEach { friend ->
            add(
                SocialFriendRow(
                    stableId = "friend-${friend.targetPlayerId}",
                    title = friend.targetDisplayName,
                    subtitle = friend.note?.takeIf(String::isNotBlank)?.let { "@$it" }
                        ?: strings.text("social.friend.id_fallback")
                            .replace("{id}", friend.targetPlayerId.takeLast(8)),
                    actionLabel = strings.text("social.test_friend.play"),
                    actionEnabled = onlineConfigured && !operationBusy,
                    actionTag = "social-friend-play",
                    onAction = { onPlayFriend(friend) },
                ),
            )
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().testTag("social-friends-reference-screen")) {
        val adaptive = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (adaptive) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 15.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SocialReferenceHero(
                artRes = R.drawable.art_friends_hero_v11,
                title = strings.text("social.friends"),
                description = strings.text("social.friends.subtitle"),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (adaptive) 145.dp else 129.dp)
                    .testTag("social-friends-hero"),
            )

            ReferencePurpleButton(
                label = strings.text("social.friend.add.title"),
                enabled = addFriendEnabled && !operationBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 51.dp)
                    .testTag("social-friends-add"),
                leading = {
                    Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(25.dp))
                },
                onClick = onOpenAddFriend,
            )

            IllustratedSurface(
                colors = FriendsReferenceStyle.Cream,
                modifier = if (adaptive) {
                    Modifier.fillMaxWidth().heightIn(min = 350.dp)
                } else {
                    Modifier.fillMaxWidth().weight(1f)
                }.testTag("social-friends-list"),
                radius = 17.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = strings.text("social.reference.your_friends"),
                        style = FriendsReferenceStyle.CardTitle.copy(fontSize = 18.sp),
                        modifier = Modifier.semantics { heading() },
                    )
                    if (operationBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).testTag("social-friend-operation-busy"),
                            color = Color(0xFF1269C3),
                            strokeWidth = 2.dp,
                        )
                    }
                    operationMessage?.let { message ->
                        Text(
                            text = message,
                            style = FriendsReferenceStyle.Small,
                            color = Color(0xFF9D2D19),
                            modifier = Modifier.testTag("social-friend-operation-message"),
                        )
                    }
                    if (rows.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Groups,
                                contentDescription = null,
                                tint = Color(0xFF6D4899),
                                modifier = Modifier.size(64.dp),
                            )
                            Text(
                                text = strings.text("social.friends.empty"),
                                style = FriendsReferenceStyle.Body,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 10.dp).testTag("social-friends-empty"),
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(rows, key = SocialFriendRow::stableId) { row ->
                                SocialReferenceFriendRow(row, operationBusy)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SocialReferenceFriendRow(row: SocialFriendRow, operationBusy: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 82.dp)
            .background(Color(0xFFFFF2D9).copy(alpha = .72f), RoundedCornerShape(13.dp))
            .border(1.dp, Color(0xFFE4BC78), RoundedCornerShape(13.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag("social-friend-row-${row.stableId}"),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PlayerAvatar(
                displayName = row.title,
                avatarUrl = row.avatarUrl,
                modifier = Modifier.size(58.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = row.title,
                    style = FriendsReferenceStyle.CardTitle.copy(fontSize = 16.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.subtitle,
                    style = FriendsReferenceStyle.Small,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            row.actionLabel?.let { label ->
                ReferenceBlueButton(
                    label = label,
                    enabled = row.actionEnabled && !operationBusy,
                    busy = operationBusy && row.actionEnabled,
                    modifier = Modifier.width(83.dp).heightIn(min = 48.dp)
                        .then(row.actionTag?.let { Modifier.testTag(it) } ?: Modifier),
                    onClick = row.onAction,
                )
            }
        }
    }
}

@Composable
internal fun SocialInvitationsReferenceContent(
    selectedPlayStyle: RemoteFriendPlayStyle,
    selectedCodeLength: Int,
    targetDisplayName: String?,
    friendEntryPoint: Boolean,
    onPlayStyleChange: (RemoteFriendPlayStyle) -> Unit,
    onCodeLengthChange: (Int) -> Unit,
    onCreateCode: () -> Unit,
    onOpenJoinCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    BoxWithConstraints(modifier.fillMaxSize().testTag("social-invitations-reference-screen")) {
        val adaptive = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (adaptive) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 15.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SocialReferenceHero(
                artRes = R.drawable.art_invite_envelope_v11,
                title = if (friendEntryPoint && !targetDisplayName.isNullOrBlank()) {
                    strings.text("social.friend.match.title").replace("{name}", targetDisplayName)
                } else {
                    strings.text("social.invites")
                },
                description = strings.text(
                    if (friendEntryPoint) "social.friend.match.description"
                    else "social.invites.screen_description",
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (adaptive) 152.dp else 131.dp)
                    .testTag("social-invitations-hero"),
            )
            IllustratedSurface(
                colors = FriendsReferenceStyle.Cream,
                modifier = if (adaptive) {
                    Modifier.fillMaxWidth().heightIn(min = 520.dp)
                } else {
                    Modifier.fillMaxWidth().weight(1f)
                }.testTag("social-invitations-form"),
                radius = 17.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        strings.text("social.online.settings"),
                        style = FriendsReferenceStyle.CardTitle.copy(fontSize = 18.sp),
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(strings.text("social.match.format"), style = FriendsReferenceStyle.Body.copy(fontWeight = FontWeight.Bold))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReferenceSegment(
                            text = strings.text("social.match.timed"),
                            selected = selectedPlayStyle == RemoteFriendPlayStyle.RACE,
                            icon = {
                                Icon(Icons.Outlined.AccessTime, contentDescription = null, modifier = Modifier.size(22.dp))
                            },
                            modifier = Modifier.weight(1f).testTag("social-invite-style-race"),
                        ) { onPlayStyleChange(RemoteFriendPlayStyle.RACE) }
                        ReferenceSegment(
                            text = strings.text("social.match.turn_based"),
                            selected = selectedPlayStyle == RemoteFriendPlayStyle.TURN_BASED,
                            icon = {
                                Icon(Icons.Outlined.Groups, contentDescription = null, modifier = Modifier.size(22.dp))
                            },
                            modifier = Modifier.weight(1f).testTag("social-invite-style-turn-based"),
                        ) { onPlayStyleChange(RemoteFriendPlayStyle.TURN_BASED) }
                    }
                    Text(
                        strings.text(
                            if (selectedPlayStyle == RemoteFriendPlayStyle.RACE) {
                                "social.match.timed.description"
                            } else {
                                "social.match.turn_based.description"
                            },
                        ),
                        style = FriendsReferenceStyle.Small,
                    )
                    Text(strings.text("social.online.secret_length"), style = FriendsReferenceStyle.Body.copy(fontWeight = FontWeight.Bold))
                    ReferenceCodeLengthStepper(
                        value = selectedCodeLength,
                        onValueChange = onCodeLengthChange,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(strings.text("social.online.rules"), style = FriendsReferenceStyle.Small)
                    ReferenceBlueButton(
                        label = strings.text(
                            if (friendEntryPoint) "social.online.send_invite" else "social.online.create_code",
                        ),
                        enabled = true,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("social-invite-create-code"),
                        leading = { Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(25.dp)) },
                        onClick = onCreateCode,
                    )
                    if (!friendEntryPoint) {
                        ReferenceDivider(strings.text("social.reference.or"))
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(strings.text("social.online.friend_code"), style = FriendsReferenceStyle.Body.copy(fontWeight = FontWeight.Bold))
                            ReferenceRouteTile(
                                label = strings.text("social.online.friend_code.placeholder"),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)
                                    .testTag("social-invite-open-join-code"),
                                onClick = onOpenJoinCode,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SocialJoinCodeReferenceContent(
    inviteCode: String,
    busy: Boolean,
    onInviteCodeChange: (String) -> Unit,
    onJoin: () -> Unit,
    onCreateOwnCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    BoxWithConstraints(modifier.fillMaxSize().testTag("social-join-code-reference-screen")) {
        val adaptive = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (adaptive) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 15.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SocialReferenceHero(
                artRes = R.drawable.art_join_shield_v11,
                title = strings.text("social.invite.join.title"),
                description = strings.text("social.invite.join.description"),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (adaptive) 154.dp else 141.dp)
                    .testTag("social-join-code-hero"),
            )
            IllustratedSurface(
                colors = FriendsReferenceStyle.Cream,
                modifier = if (adaptive) {
                    Modifier.fillMaxWidth().heightIn(min = 500.dp)
                } else {
                    Modifier.fillMaxWidth().weight(1f)
                }.testTag("social-join-code-form"),
                radius = 17.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                ) {
                    Text(
                        strings.text("social.invite.join.code_label"),
                        style = FriendsReferenceStyle.CardTitle.copy(fontSize = 18.sp),
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        strings.text("social.invite.join.code_length")
                            .replace("{count}", FriendInviteCodeLength.toString()),
                        style = FriendsReferenceStyle.Body,
                    )
                    ReferenceInviteCodeField(
                        value = inviteCode,
                        onValueChange = onInviteCodeChange,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFEAB5), RoundedCornerShape(13.dp))
                            .border(1.dp, Color(0xFFF0C870), RoundedCornerShape(13.dp))
                            .padding(horizontal = 10.dp, vertical = 11.dp)
                            .testTag("social-invite-expiry-policy"),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Icon(Icons.Outlined.StarOutline, contentDescription = null, tint = Color(0xFFFFA600), modifier = Modifier.size(25.dp))
                            Text(strings.text("social.invite.join.expiry"), style = FriendsReferenceStyle.Small)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    ReferenceBlueButton(
                        label = strings.text("social.invite.join.action"),
                        enabled = inviteCode.length == FriendInviteCodeLength && !busy,
                        busy = busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag("social-invite-join"),
                        leading = { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(25.dp)) },
                        onClick = onJoin,
                    )
                    ReferenceDivider(strings.text("social.reference.or"))
                    ReferenceRouteTile(
                        label = strings.text("social.invite.create_own"),
                        leading = { Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(27.dp)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp).testTag("social-invite-create-own"),
                        onClick = onCreateOwnCode,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SocialInviteOperationReferenceContent(
    title: String,
    message: String,
    error: Boolean,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 15.dp, vertical = 4.dp)
            .testTag(if (error) "social-invite-error" else "social-invite-loading"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SocialReferenceHero(
            artRes = R.drawable.art_invite_envelope_v11,
            title = title,
            description = strings.text("social.invites.screen_description"),
            modifier = Modifier.fillMaxWidth().heightIn(min = 131.dp),
        )
        IllustratedSurface(FriendsReferenceStyle.Cream, Modifier.fillMaxWidth().weight(1f), radius = 17.dp) {
            Column(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (!error) CircularProgressIndicator(color = Color(0xFF1269C3), modifier = Modifier.size(46.dp))
                Text(
                    text = message,
                    style = FriendsReferenceStyle.Body.copy(
                        fontSize = 16.sp,
                        color = if (error) Color(0xFF9D2D19) else FriendsReferenceStyle.Ink,
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = if (error) 0.dp else 18.dp),
                )
                onRetry?.let { retry ->
                    ReferenceBlueButton(
                        label = strings.text("social.online.retry"),
                        enabled = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 54.dp)
                            .testTag("social-invite-retry"),
                        onClick = retry,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SocialInviteWaitingReferenceContent(
    invite: OnlineFriendInvite,
    title: String,
    notice: String?,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    var nowEpochMs by remember(invite.expiresAtEpochMs) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(invite.expiresAtEpochMs) {
        while (nowEpochMs < invite.expiresAtEpochMs) {
            delay(1_000L)
            nowEpochMs = System.currentTimeMillis()
        }
    }
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 15.dp, vertical = 4.dp)
            .testTag("social-invite-waiting"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SocialReferenceHero(
            artRes = R.drawable.art_invite_envelope_v11,
            title = title,
            description = strings.text("social.online.waiting_phone"),
            modifier = Modifier.fillMaxWidth().heightIn(min = 131.dp),
        )
        IllustratedSurface(FriendsReferenceStyle.Cream, Modifier.fillMaxWidth().weight(1f), radius = 17.dp) {
            Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(strings.text("social.online.share_code"), style = FriendsReferenceStyle.CardTitle)
                Text(
                    text = invite.inviteCode,
                    style = FriendsReferenceStyle.Title.copy(color = Color(0xFF0D61B5), fontSize = 32.sp),
                    modifier = Modifier.testTag("social-created-invite-code"),
                )
                Text(
                    strings.text("social.invite.remaining")
                        .replace("{time}", formatFriendInviteRemainingClock(invite.expiresAtEpochMs, nowEpochMs)),
                    style = FriendsReferenceStyle.Body.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.testTag("social-created-invite-expiry"),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReferenceRouteTile(
                        label = strings.text("social.invite.copy"),
                        modifier = Modifier.weight(1f).heightIn(min = 54.dp).testTag("social-invite-copy"),
                        onClick = onCopy,
                    )
                    ReferenceBlueButton(
                        label = strings.text("social.invite.share"),
                        enabled = true,
                        modifier = Modifier.weight(1f).heightIn(min = 54.dp).testTag("social-invite-share"),
                        onClick = onShare,
                    )
                }
                notice?.let { Text(it, style = FriendsReferenceStyle.Small, color = Color(0xFF0D61B5)) }
                Spacer(Modifier.weight(1f))
                ReferenceRouteTile(
                    label = strings.text("social.online.cancel"),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("social-invite-cancel"),
                    onClick = onCancel,
                )
            }
        }
    }
}

@Composable
private fun SocialReferenceHero(
    artRes: Int,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    IllustratedSurface(
        colors = listOf(Color(0xFF9250C9), Color(0xFF6B36A7), Color(0xFF45217F)),
        modifier = modifier,
        rim = Color(0xFFBDA2F0),
        radius = 18.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(painterResource(artRes), contentDescription = null, modifier = Modifier.size(98.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    title,
                    style = FriendsReferenceStyle.Title.copy(fontSize = 26.sp, lineHeight = 30.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    description,
                    style = FriendsReferenceStyle.Body.copy(color = Color.White, shadow = FriendsReferenceStyle.WhiteShadow),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReferencePurpleButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    IllustratedSurface(
        colors = if (enabled) listOf(Color(0xFFA94DD4), Color(0xFF7C35B7), Color(0xFF54248F))
        else listOf(Color(0xFF8B7D91), Color(0xFF685E6D)),
        modifier = modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        rim = Color(0xFFD4B5F1),
        radius = 17.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            leading?.invoke()
            Text(
                label,
                style = FriendsReferenceStyle.Body.copy(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(start = if (leading == null) 0.dp else 7.dp),
            )
        }
    }
}

@Composable
private fun ReferenceBlueButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier,
    busy: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    IllustratedSurface(
        colors = if (enabled) listOf(Color(0xFF2189ED), Color(0xFF0C68C8), Color(0xFF064896))
        else listOf(Color(0xFF8A9DAF), Color(0xFF637687)),
        modifier = modifier
            .alpha(if (enabled) 1f else .72f)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick),
        rim = Color(0xFF70C9FF),
        radius = 13.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                leading?.invoke()
                Text(
                    label,
                    style = FriendsReferenceStyle.Body.copy(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(start = if (leading == null) 0.dp else 7.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReferenceSegment(
    text: String,
    selected: Boolean,
    icon: @Composable () -> Unit,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(
                if (selected) Brush.verticalGradient(listOf(Color(0xFF2189ED), Color(0xFF0755B3)))
                else Brush.verticalGradient(listOf(Color(0xFFFFF6E3), Color(0xFFF4DCB2))),
                shape,
            )
            .border(1.dp, if (selected) Color(0xFF83D1FF) else Color(0xFFE6BB72), shape)
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.alpha(if (selected) 1f else .68f)) { icon() }
            Text(
                text,
                style = FriendsReferenceStyle.Body.copy(
                    color = if (selected) Color.White else Color(0xFF765A36),
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ReferenceCodeLengthStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReferenceStepButton(
            label = "−",
            enabled = value > MinimumOnlineCodeLength,
            description = strings.text("social.online.secret_length.decrease"),
            modifier = Modifier.testTag("social-invite-code-length-decrease"),
        ) { onValueChange(value - 1) }
        Box(
            Modifier.weight(1f).heightIn(min = 50.dp)
                .background(Color(0xFFFFF3DD), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFFEAB96A), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                formatOnlineCodeLength(strings, value),
                style = FriendsReferenceStyle.CardTitle.copy(fontSize = 18.sp),
                modifier = Modifier.testTag("social-invite-code-length-value"),
            )
        }
        ReferenceStepButton(
            label = "+",
            enabled = value < MaximumOnlineCodeLength,
            description = strings.text("social.online.secret_length.increase"),
            modifier = Modifier.testTag("social-invite-code-length-increase"),
        ) { onValueChange(value + 1) }
    }
}

@Composable
private fun ReferenceStepButton(
    label: String,
    enabled: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier.size(50.dp)
            .semantics { contentDescription = description }
            .background(Color(0xFFFFF1D6), RoundedCornerShape(11.dp))
            .border(1.dp, Color(0xFFE7AD53), RoundedCornerShape(11.dp))
            .alpha(if (enabled) 1f else .45f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = FriendsReferenceStyle.CardTitle.copy(fontSize = 22.sp))
    }
}

@Composable
private fun ReferenceInviteCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(normalizeFriendInviteCode(it)) },
        enabled = enabled,
        modifier = modifier
            .testTag("social-invite-code-field")
            .semantics { contentDescription = strings.text("social.invite.join.code_label") },
        textStyle = FriendsReferenceStyle.Body.copy(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
        ),
        singleLine = true,
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(1.dp).alpha(0f)) { innerTextField() }
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    repeat(FriendInviteCodeLength) { index ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(Color(0xFFFFF0D1), RoundedCornerShape(10.dp))
                                .border(1.dp, Color(0xFFE4B363), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (index < value.length) "•" else "",
                                style = FriendsReferenceStyle.CardTitle.copy(fontSize = 24.sp),
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ReferenceDivider(label: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f).height(1.dp).background(Color(0xFFE1BE82)))
        Text(label, style = FriendsReferenceStyle.Small, fontWeight = FontWeight.Bold)
        Box(Modifier.weight(1f).height(1.dp).background(Color(0xFFE1BE82)))
    }
}

@Composable
private fun ReferenceRouteTile(
    label: String,
    modifier: Modifier,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .background(Color(0xFFFFF0D1).copy(alpha = .86f), RoundedCornerShape(13.dp))
            .border(1.dp, Color(0xFFE5B86E), RoundedCornerShape(13.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            Text(
                label,
                style = FriendsReferenceStyle.Body.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f).padding(start = if (leading == null) 0.dp else 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

internal fun formatFriendInviteRemainingClock(expiresAtEpochMs: Long, nowEpochMs: Long): String {
    val remainingSeconds = ((expiresAtEpochMs - nowEpochMs).coerceAtLeast(0L) + 999L) / 1_000L
    val minutes = remainingSeconds / 60L
    val seconds = remainingSeconds % 60L
    return minutes.toString().padStart(2, '0') + ":" + seconds.toString().padStart(2, '0')
}
