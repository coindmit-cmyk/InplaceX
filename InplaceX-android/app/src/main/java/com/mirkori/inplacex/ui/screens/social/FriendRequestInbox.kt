package com.mirkori.inplacex.ui.screens.social

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import com.mirkori.inplacex.ui.screens.shared.PagePrimaryButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.mirkori.inplacex.ui.screens.shared.PageSecondaryButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.mirkori.MirkoriFriendOperationResult
import com.mirkori.inplacex.platform.mirkori.MirkoriFriendRequest
import com.mirkori.inplacex.ui.theme.InplaceXColors
import com.mirkori.inplacex.ui.theme.PageColors
import com.mirkori.inplacex.ui.theme.PageType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun FriendRequestInbox(
    requests: List<MirkoriFriendRequest>,
    onAccept: suspend (MirkoriFriendRequest) -> MirkoriFriendOperationResult,
) {
    val strings = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }
    var acceptedIds by remember { mutableStateOf(emptySet<String>()) }
    var pendingId by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<Pair<String, String>?>(null) }
    val visibleRequests = requests.distinctBy { it.requestId }.filterNot { it.requestId in acceptedIds }
    val title = strings.text(
        if (visibleRequests.size == 1) "social.friend.requests.single" else "social.friend.requests.title",
    )

    if (visibleRequests.isNotEmpty()) {
        Surface(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().testTag("friend-requests-open"),
            shape = RoundedCornerShape(20.dp),
            color = PageColors.Cream,
            contentColor = PageColors.Text,
            border = BorderStroke(1.dp, PageColors.Border),
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.heightIn(min = 64.dp).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.PersonAdd, contentDescription = null, tint = PageColors.Friends)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = PageType.Body, fontWeight = FontWeight.Bold)
                    Text(
                        text = visibleRequests.singleOrNull()?.player?.displayName
                            ?: strings.text("social.friend.requests.open_list"),
                        style = PageType.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (visibleRequests.size > 1) {
                    Badge(containerColor = PageColors.Friends, contentColor = InplaceXColors.White) {
                        Text(visibleRequests.size.toString())
                    }
                }
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            }
        }
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = PageColors.Cream,
            titleContentColor = PageColors.Text,
            textContentColor = PageColors.Text,
            title = {
                Text(
                    if (visibleRequests.size > 1) "$title · ${visibleRequests.size}" else title,
                    style = PageType.Section,
                )
            },
            text = {
                if (visibleRequests.isEmpty()) {
                    Text(
                        strings.text("social.friend.requests.empty"),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                            .testTag("friend-requests-list"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(visibleRequests, key = { it.requestId }) { request ->
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = PageColors.CreamSecondary,
                                contentColor = PageColors.Text,
                                border = BorderStroke(1.dp, PageColors.Border),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        request.player.displayName,
                                        style = PageType.CardTitle,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    request.player.handle?.takeIf { it.isNotBlank() }?.let {
                                        Text("@$it", style = PageType.Secondary)
                                    }
                                    if (failure?.first == request.requestId) {
                                        Text(
                                            strings.text(requireNotNull(failure).second),
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                        )
                                    }
                                    Button(
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                            .testTag("friend-request-accept-${request.requestId}"),
                                        enabled = pendingId == null,
                                        onClick = {
                                            if (pendingId != null) return@Button
                                            pendingId = request.requestId
                                            failure = null
                                            scope.launch {
                                                try {
                                                    when (onAccept(request)) {
                                                        is MirkoriFriendOperationResult.Success ->
                                                            acceptedIds = acceptedIds + request.requestId
                                                        MirkoriFriendOperationResult.Rejected ->
                                                            failure = request.requestId to "social.friend.request.rejected"
                                                        MirkoriFriendOperationResult.Unavailable ->
                                                            failure = request.requestId to "social.friend.request.unavailable"
                                                    }
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (_: Exception) {
                                                    failure = request.requestId to "social.friend.request.unavailable"
                                                } finally {
                                                    pendingId = null
                                                }
                                            }
                                        },
                                    ) {
                                        if (pendingId == request.requestId) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                            Text(
                                                strings.text("social.friend.requests.accepting"),
                                                modifier = Modifier.padding(start = 8.dp),
                                            )
                                        } else {
                                            Text(strings.text("social.friend.request.accept"))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(strings.text("social.friend.search.close"))
                }
            },
        )
    }
}
