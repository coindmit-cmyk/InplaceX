package com.mirkori.inplacex.ui.screens.social

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mirkori.inplacex.data.local.LocalSocialRelationship
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.mirkori.MirkoriFriendOperationResult
import com.mirkori.inplacex.platform.mirkori.MirkoriFriendRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun FriendsReferenceScreen(
    friends: List<LocalSocialRelationship>,
    incomingFriendRequests: List<MirkoriFriendRequest>,
    onlineConfigured: Boolean,
    onOpenFriends: () -> Unit,
    onInvite: () -> Unit,
    onFindMatch: () -> Unit,
    onAcceptFriendRequest: suspend (MirkoriFriendRequest) -> MirkoriFriendOperationResult,
    modifier: Modifier = Modifier,
    incomingInviteCount: Int = 0,
    onlineFriendIds: Set<String>? = null,
) {
    val strings = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    var acceptedIds by remember { mutableStateOf(emptySet<String>()) }
    var acceptingId by remember { mutableStateOf<String?>(null) }
    var acceptanceError by remember { mutableStateOf<String?>(null) }
    val requests = incomingFriendRequests.filterNot { it.requestId in acceptedIds }
    // Наличие транспорта не доказывает присутствие конкретного друга.
    val confirmedOnline = onlineFriendIds?.takeIf { onlineConfigured }
    val visibleFriends = if (confirmedOnline == null) friends else friends.filter { it.targetPlayerId in confirmedOnline }
    val acceptRequest: (MirkoriFriendRequest) -> Unit = { request ->
        if (acceptingId == null) {
            acceptingId = request.requestId
            acceptanceError = null
            scope.launch {
                try {
                    when (onAcceptFriendRequest(request)) {
                        is MirkoriFriendOperationResult.Success -> acceptedIds = acceptedIds + request.requestId
                        MirkoriFriendOperationResult.Rejected -> acceptanceError = "social.friend.request.rejected"
                        MirkoriFriendOperationResult.Unavailable -> acceptanceError = "social.friend.request.unavailable"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    acceptanceError = "social.friend.request.unavailable"
                } finally {
                    acceptingId = null
                }
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val scale = (maxWidth.value / 374f).coerceIn(.85f, 1.15f)
        val largeText = LocalDensity.current.fontScale > 1.3f
        val stacked = largeText || maxWidth < 340.dp
        Column(
            Modifier.fillMaxSize().testTag("friends-reference-screen")
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp * scale, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp * scale),
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 110.dp * scale)
                    .padding(start = 20.dp * scale, top = 5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(strings.text("social.reference.title"), style = FriendsReferenceStyle.Title,
                    modifier = Modifier.semantics { heading() })
                Text(strings.text("social.reference.subtitle"),
                    style = FriendsReferenceStyle.Body.copy(color = Color.White, shadow = FriendsReferenceStyle.WhiteShadow))
                IllustratedSurface(
                    colors = if (onlineConfigured) FriendsReferenceStyle.Green else FriendsReferenceStyle.Chrome,
                    rim = Color(0xFFA9C967), radius = 16.dp,
                    modifier = Modifier.padding(top = 4.dp).testTag("friends-availability"),
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(12.dp).background(
                            Brush.verticalGradient(if (onlineConfigured) listOf(Color(0xFFC5FF77), Color(0xFF61A922))
                                else listOf(Color(0xFFD5DDE6), Color(0xFF8A99AA))), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = .7f), CircleShape))
                        Text(
                            if (confirmedOnline != null) strings.text("social.reference.online_count").replace("{count}", visibleFriends.size.toString())
                            else strings.text(if (onlineConfigured) "social.status.available" else "social.status.preparing"),
                            style = FriendsReferenceStyle.Small.copy(color = Color.White, fontWeight = FontWeight.Bold),
                        )
                    }
                }
            }

            IllustratedSurface(FriendsReferenceStyle.Cream,
                Modifier.fillMaxWidth().heightIn(min = 136.dp * scale).testTag("friends-preview")) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp * scale, vertical = 12.dp * scale),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.text(if (confirmedOnline == null) "social.reference.your_friends" else "social.reference.online_friends"),
                        style = FriendsReferenceStyle.CardTitle)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp * scale)) {
                        if (visibleFriends.isEmpty()) {
                            Text(strings.text("social.reference.empty"), style = FriendsReferenceStyle.Body,
                                modifier = Modifier.weight(1f).padding(end = 8.dp))
                        } else {
                            visibleFriends.take(4).forEach { friend ->
                                Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpenFriends),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box {
                                        val art = FriendsArt.entries[(friend.targetPlayerId.hashCode() and Int.MAX_VALUE) % 4]
                                        FriendsIllustration(art, Modifier.size(58.dp * scale).clip(CircleShape))
                                        if (confirmedOnline != null) Box(Modifier.align(Alignment.BottomEnd)
                                            .size(10.dp).background(Color(0xFF6BC02A), CircleShape)
                                            .border(1.dp, FriendsReferenceStyle.LightRim, CircleShape))
                                    }
                                    Text(friend.targetDisplayName, style = FriendsReferenceStyle.Small,
                                        modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (visibleFriends.size < 4) Spacer(Modifier.weight((4 - visibleFriends.size).toFloat()))
                        }
                        Column(Modifier.width(44.dp * scale).heightIn(min = 72.dp)
                            .testTag("friends-open-all").clickable(role = Role.Button, onClick = onOpenFriends),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.size(42.dp * scale).background(Brush.verticalGradient(
                                listOf(Color(0xFFFFF5DF), Color(0xFFF1D3A0))), CircleShape)
                                .border(1.dp, FriendsReferenceStyle.Border, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Add, null, Modifier.size(30.dp), tint = Color(0xFF785025))
                            }
                            Text(strings.text("social.reference.all"), style = FriendsReferenceStyle.Small)
                        }
                    }
                }
            }

            if (incomingInviteCount > 0) {
                IllustratedSurface(FriendsReferenceStyle.Cream,
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenFriends)) {
                    Text(strings.text("social.invites.incoming.notice").replace("{count}", incomingInviteCount.toString()),
                        style = FriendsReferenceStyle.Body, modifier = Modifier.padding(16.dp))
                }
            }

            requests.firstOrNull()?.let { request ->
                IllustratedSurface(FriendsReferenceStyle.Cream,
                    Modifier.fillMaxWidth().heightIn(min = 92.dp * scale).testTag("friends-request")) {
                    Column(Modifier.fillMaxWidth().padding(12.dp * scale)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Box {
                                FriendsIllustration(FriendsArt.ENVELOPE, Modifier.size(56.dp * scale))
                                ReferenceCountBadge(requests.size, Modifier.align(Alignment.TopEnd))
                            }
                            if (stacked) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    RequestText(request, requests.size, onOpenFriends)
                                    AcceptRequestButton(acceptingId != null, Modifier.fillMaxWidth()) {
                                        acceptRequest(request)
                                    }
                                }
                            } else {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f).testTag("friends-requests-open")
                                            .clickable(role = Role.Button, onClick = onOpenFriends)) {
                                            Text(strings.text("social.reference.request"), style = FriendsReferenceStyle.CardTitle.copy(fontSize = 16.sp), maxLines = 1)
                                            Text(request.player.displayName, style = FriendsReferenceStyle.Body,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        AcceptRequestButton(acceptingId != null, Modifier.width(87.dp)) {
                                            acceptRequest(request)
                                        }
                                    }
                                    Text(strings.text("social.friend.request.incoming"), style = FriendsReferenceStyle.Small)
                                }
                            }
                        }
                        acceptanceError?.let { Text(strings.text(it), color = Color(0xFF982D18),
                            style = FriendsReferenceStyle.Small, modifier = Modifier.padding(top = 5.dp)) }
                    }
                }
            }

            val invite: @Composable (Modifier) -> Unit = { tileModifier ->
                ReferenceActionTile(strings.text("social.reference.invite"), strings.text("social.reference.invite_description"),
                    FriendsArt.INVITE, FriendsReferenceStyle.Purple, onlineConfigured,
                    tileModifier.testTag("friends-invite"), scale, onInvite)
            }
            val match: @Composable (Modifier) -> Unit = { tileModifier ->
                ReferenceActionTile(strings.text("social.reference.find_match"), strings.text("social.reference.match_description"),
                    FriendsArt.SWORDS, FriendsReferenceStyle.Blue, onlineConfigured,
                    tileModifier.testTag("friends-find-match"), scale, onFindMatch)
            }
            if (stacked) {
                invite(Modifier.fillMaxWidth())
                match(Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp * scale)) {
                    invite(Modifier.weight(1f))
                    match(Modifier.weight(1f))
                }
            }

            IllustratedSurface(
                if (onlineConfigured) FriendsReferenceStyle.Green else FriendsReferenceStyle.Green.map { lerp(it, Color.DarkGray, .6f) },
                Modifier.fillMaxWidth().heightIn(min = 76.dp * scale).testTag("friends-online-matches")
                    .clickable(enabled = onlineConfigured, role = Role.Button, onClick = onFindMatch),
                rim = Color(0xFFA0BE65),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    FriendsIllustration(FriendsArt.ROBOT, Modifier.size(55.dp * scale))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(strings.text("social.reference.matches"), style = FriendsReferenceStyle.CardTitle.copy(color = Color.White, fontSize = 20.sp))
                        Text(strings.text("social.reference.matches_description"), style = FriendsReferenceStyle.Small.copy(color = Color.White))
                    }
                    ReferenceArrow(Color(0xFF5A8D29))
                }
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}

@Composable
private fun RequestText(request: MirkoriFriendRequest, count: Int, onOpen: () -> Unit) {
    val strings = LocalAppStrings.current
    Column(Modifier.testTag("friends-requests-open").clickable(role = Role.Button, onClick = onOpen)) {
        Text(strings.text("social.reference.request"), style = FriendsReferenceStyle.CardTitle)
        Text(request.player.displayName, style = FriendsReferenceStyle.Body)
        Text(strings.text("social.friend.request.incoming"), style = FriendsReferenceStyle.Small)
        if (count > 1) Text(strings.text("social.reference.requests_count").replace("{count}", count.toString()), style = FriendsReferenceStyle.Small)
    }
}

@Composable
private fun AcceptRequestButton(busy: Boolean, modifier: Modifier, onAccept: () -> Unit) {
    val strings = LocalAppStrings.current
    Box(modifier.heightIn(min = 48.dp).testTag("friends-accept-request")
        .clickable(enabled = !busy, role = Role.Button, onClick = onAccept), contentAlignment = Alignment.Center) {
        IllustratedSurface(listOf(Color(0xFF1AABF0), Color(0xFF0781CF), Color(0xFF075295)),
            Modifier.fillMaxWidth(), rim = Color(0xFF84DDFF), radius = 11.dp) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(strings.text("social.friend.request.accept"),
                    style = FriendsReferenceStyle.Body.copy(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun ReferenceActionTile(
    title: String, subtitle: String, art: FriendsArt, colors: List<Color>, enabled: Boolean,
    modifier: Modifier, scale: Float, onClick: () -> Unit,
) {
    IllustratedSurface(if (enabled) colors else colors.map { lerp(it, Color.DarkGray, .6f) },
        modifier.heightIn(min = 190.dp * scale).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        rim = Color(0xFFADCAE8)) {
        Column(Modifier.fillMaxWidth().padding(14.dp * scale), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            FriendsIllustration(art, Modifier.size(57.dp * scale))
            Text(title, style = FriendsReferenceStyle.CardTitle.copy(color = Color.White, fontSize = 21.sp, lineHeight = 25.sp,
                shadow = FriendsReferenceStyle.WhiteShadow))
            Text(subtitle, style = FriendsReferenceStyle.Body.copy(color = Color.White))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { ReferenceArrow(colors.first()) }
        }
    }
}

@Composable
internal fun ReferenceArrow(color: Color) {
    Box(Modifier.size(33.dp).background(Brush.verticalGradient(listOf(color, lerp(color, Color.Black, .4f))), CircleShape)
        .border(1.dp, Color.White.copy(alpha = .55f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.ChevronRight, null, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun ReferenceCountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(modifier.size(20.dp).background(Brush.verticalGradient(listOf(Color(0xFFFF6243), Color(0xFFD9230B))), CircleShape)
        .border(1.dp, Color(0xFFFFB898), CircleShape), contentAlignment = Alignment.Center) {
        Text(count.coerceAtMost(99).toString(), style = FriendsReferenceStyle.Small.copy(color = Color.White, fontWeight = FontWeight.Bold))
    }
}
