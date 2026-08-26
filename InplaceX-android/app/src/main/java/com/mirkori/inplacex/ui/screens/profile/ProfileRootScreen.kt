package com.mirkori.inplacex.ui.screens.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mirkori.inplacex.ui.screens.shared.PagePrimaryButton as Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import com.mirkori.inplacex.ui.screens.shared.PageSecondaryButton as OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.monetization.TemporaryProPolicy
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import com.mirkori.inplacex.platform.mirkori.MirkoriAccountState
import com.mirkori.inplacex.platform.mirkori.MirkoriAccountStateKind
import com.mirkori.platform.sdk.PlatformAuthMode
import com.mirkori.inplacex.platform.mirkori.MirkoriPublicPlayerProfile
import com.mirkori.inplacex.ui.screens.shared.SceneBadge
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import com.mirkori.inplacex.ui.screens.shared.ScenePageColumn
import com.mirkori.inplacex.ui.screens.shared.PlayerAvatar
import com.mirkori.inplacex.ui.screens.shared.PlayerAvatarPresets
import com.mirkori.inplacex.ui.theme.InplaceXColors
import com.mirkori.inplacex.ui.theme.PageColors
import com.mirkori.inplacex.ui.theme.PageType
import com.mirkori.inplacex.ui.screens.shared.PageHeroCard
import com.mirkori.inplacex.ui.screens.shared.PageStatusPill
import com.mirkori.inplacex.ui.screens.shared.PageSectionHeader
import com.mirkori.inplacex.ui.screens.shared.StatTile
import com.mirkori.inplacex.ui.screens.shared.StatusCard
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight

@Composable
fun ProfileRootScreen(
    progressState: GameProgressState,
    nowMs: Long = System.currentTimeMillis(),
    mirkoriAccountState: MirkoriAccountState = MirkoriAccountState(MirkoriAccountStateKind.INITIALIZING),
    mirkoriAuthResultKey: String? = null,
    mirkoriAuthInProgress: Boolean = false,
    publicPlayerProfile: MirkoriPublicPlayerProfile? = null,
    publicProfileResultKey: String? = null,
    publicProfileInProgress: Boolean = false,
    authResultKey: String? = null,
    authInProgress: Boolean = false,
    showGooglePlayCard: Boolean = false,
    onMirkoriSignIn: () -> Unit = {},
    onPublicHandleChange: (String) -> Unit = {},
    onDisplayNameChange: (String) -> Unit = {},
    onAvatarChange: (String) -> Unit = {},
    onGooglePlaySignIn: () -> Unit = {},
    onGooglePlaySignOut: () -> Unit = {},
) {
    val strings = LocalAppStrings.current
    val clipboardManager = LocalClipboardManager.current
    var handleDialogOpen by remember { mutableStateOf(false) }
    var handleInput by remember(publicPlayerProfile?.handle) {
        mutableStateOf(publicPlayerProfile?.handle.orEmpty())
    }
    var localHandleError by remember { mutableStateOf(false) }
    var nameDialogOpen by remember { mutableStateOf(false) }
    var nameInput by remember(publicPlayerProfile?.displayName, progressState.playerDisplayName) {
        mutableStateOf(publicPlayerProfile?.displayName ?: progressState.playerDisplayName)
    }
    var localNameError by remember { mutableStateOf(false) }
    var avatarDialogOpen by remember { mutableStateOf(false) }
    val visibleDisplayName = publicPlayerProfile?.displayName ?: progressState.playerDisplayName

    LaunchedEffect(publicProfileResultKey) {
        if (publicProfileResultKey == "profile.mirkori.handle.saved") handleDialogOpen = false
        if (publicProfileResultKey == "profile.mirkori.name.saved") nameDialogOpen = false
        if (publicProfileResultKey == "profile.mirkori.avatar.saved") avatarDialogOpen = false
    }

    if (nameDialogOpen) {
        AlertDialog(
            shape = RoundedCornerShape(24.dp),
            containerColor = PageColors.Cream,
            titleContentColor = PageColors.Text,
            textContentColor = PageColors.Text,
            onDismissRequest = { if (!publicProfileInProgress) nameDialogOpen = false },
            title = { Text(strings.text("profile.mirkori.name.change")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.text("profile.mirkori.name.rules"))
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = {
                            nameInput = it.take(40)
                            localNameError = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(strings.text("profile.mirkori.name")) },
                        isError = localNameError,
                    )
                    if (localNameError) Text(strings.text("profile.mirkori.name.invalid"))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val normalized = nameInput.trim()
                        if (normalized.isNotEmpty()) onDisplayNameChange(normalized)
                        else localNameError = true
                    },
                    enabled = !publicProfileInProgress,
                ) {
                    Text(strings.text("profile.mirkori.name.save"))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { nameDialogOpen = false },
                    enabled = !publicProfileInProgress,
                ) {
                    Text(strings.text("profile.mirkori.handle.cancel"))
                }
            },
        )
    }

    if (avatarDialogOpen) {
        AlertDialog(
            shape = RoundedCornerShape(24.dp),
            containerColor = PageColors.Cream,
            titleContentColor = PageColors.Text,
            textContentColor = PageColors.Text,
            onDismissRequest = { if (!publicProfileInProgress) avatarDialogOpen = false },
            title = { Text(strings.text("profile.mirkori.avatar.change")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.text("profile.mirkori.avatar.choose"))
                    PlayerAvatarPresets.chunked(3).forEach { rowPresets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            rowPresets.forEach { preset ->
                                PlayerAvatar(
                                    displayName = visibleDisplayName,
                                    avatarUrl = "/${preset.key}",
                                    modifier = Modifier.size(64.dp),
                                    onClick = { onAvatarChange(preset.key) },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                OutlinedButton(
                    onClick = { avatarDialogOpen = false },
                    enabled = !publicProfileInProgress,
                ) {
                    Text(strings.text("profile.mirkori.handle.cancel"))
                }
            },
        )
    }

    if (handleDialogOpen) {
        AlertDialog(
            shape = RoundedCornerShape(24.dp),
            containerColor = PageColors.Cream,
            titleContentColor = PageColors.Text,
            textContentColor = PageColors.Text,
            onDismissRequest = { if (!publicProfileInProgress) handleDialogOpen = false },
            title = { Text(strings.text("profile.mirkori.handle.change")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.text("profile.mirkori.handle.rules"))
                    OutlinedTextField(
                        value = handleInput,
                        onValueChange = {
                            handleInput = it.lowercase().filter { character ->
                                character in 'a'..'z' || character in '0'..'9' || character == '_'
                            }.take(24)
                            localHandleError = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        prefix = { Text("@") },
                        label = { Text(strings.text("profile.mirkori.handle")) },
                        isError = localHandleError,
                    )
                    if (localHandleError) Text(strings.text("profile.mirkori.handle.invalid"))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val normalized = handleInput.trim().lowercase()
                        if (normalized.matches(Regex("[a-z0-9_]{3,24}"))) {
                            onPublicHandleChange(normalized)
                        } else {
                            localHandleError = true
                        }
                    },
                    enabled = !publicProfileInProgress,
                ) {
                    Text(strings.text("profile.mirkori.handle.save"))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { handleDialogOpen = false },
                    enabled = !publicProfileInProgress,
                ) {
                    Text(strings.text("profile.mirkori.handle.cancel"))
                }
            },
        )
    }

    ScenePageColumn(
        modifier = Modifier.fillMaxSize(),
        scrollable = true,
    ) {
        PageHeroCard(
            title = visibleDisplayName,
            subtitle = publicPlayerProfile?.handle?.let { "@$it" }
                ?: strings.text("profile.mirkori.handle.not_set"),
            accent = PageColors.Profile,
            titleModifier = if (mirkoriAccountState.gamePlayerId == null) Modifier else Modifier.clickable {
                nameInput = visibleDisplayName
                localNameError = false
                nameDialogOpen = true
            },
            leading = {
                PlayerAvatar(
                    displayName = visibleDisplayName,
                    avatarUrl = publicPlayerProfile?.avatarUrl,
                    modifier = Modifier.size(78.dp),
                    onClick = if (mirkoriAccountState.gamePlayerId == null) null else {
                        { avatarDialogOpen = true }
                    },
                )
            },
        ) {
            Text(
                text = when (mirkoriAccountState.kind) {
                    MirkoriAccountStateKind.LINKED -> strings.text("profile.mirkori.connected.short")
                    MirkoriAccountStateKind.GUEST -> strings.text("profile.mirkori.guest.short")
                    MirkoriAccountStateKind.INITIALIZING -> strings.text("profile.mirkori.initializing")
                    MirkoriAccountStateKind.UNAVAILABLE -> strings.text("profile.mirkori.unavailable.short")
                },
                style = PageType.Secondary, color = Color.White,
            )

            if (mirkoriAccountState.kind != MirkoriAccountStateKind.LINKED) Text(
                text = when (mirkoriAccountState.kind) {
                    MirkoriAccountStateKind.LINKED -> strings.text("profile.mirkori.connected")
                    MirkoriAccountStateKind.GUEST -> strings.text("profile.mirkori.guest")
                    MirkoriAccountStateKind.INITIALIZING -> strings.text("profile.mirkori.initializing")
                    MirkoriAccountStateKind.UNAVAILABLE -> strings.text("profile.mirkori.unavailable")
                },
                style = PageType.Secondary,
                color = Color.White.copy(alpha = .92f),
            )

            mirkoriAccountState.gamePlayerId?.let { playerId ->
                OutlinedButton(
                    onClick = {
                        handleInput = publicPlayerProfile?.handle.orEmpty()
                        localHandleError = false
                        handleDialogOpen = true
                    },
                    enabled = !publicProfileInProgress,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(strings.text("profile.mirkori.handle.change"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                    text = strings.text("profile.mirkori.player_id").replace("{id}", playerId.takeLast(8)),
                    style = PageType.Secondary, color = Color.White.copy(alpha = .92f), modifier = Modifier.weight(1f),
                  )
                  IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(playerId)) },
                  ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = strings.text("profile.mirkori.copy_player_id"), tint = Color.White)
                  }
                }
            }

            publicProfileResultKey?.let { key ->
                Text(
                    text = strings.text(key),
                    style = PageType.Secondary,
                    color = if (key in PublicProfileSuccessKeys) {
                        PageColors.Success
                    } else {
                        PageColors.Error
                    },
                )
            }

            if (mirkoriAccountState.kind != MirkoriAccountStateKind.LINKED) {
                Button(
                    onClick = onMirkoriSignIn,
                    enabled = !mirkoriAuthInProgress &&
                        mirkoriAccountState.kind != MirkoriAccountStateKind.INITIALIZING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(strings.text("profile.mirkori.sign_in"))
                }
            }

            val resultKey = if (mirkoriAuthInProgress) "profile.mirkori.in_progress" else mirkoriAuthResultKey
            resultKey?.let { key ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = if (key in MirkoriAuthErrorKeys) {
                        PageColors.Error.copy(alpha = 0.12f)
                    } else {
                        PageColors.Success.copy(alpha = 0.14f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (key in MirkoriAuthErrorKeys) PageColors.Error else PageColors.Success,
                    ),
                ) {
                    Text(
                        text = strings.text(key),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = PageType.Secondary,
                    )
                }
            }
        }

        if (showGooglePlayCard) {
            val googleConnected = if (mirkoriAccountState.kind == MirkoriAccountStateKind.LINKED) {
                mirkoriAccountState.authMode == PlatformAuthMode.GOOGLE
            } else {
                progressState.googlePlaySignedIn
            }
            StatusCard(
                title = strings.text("profile.google_play.title"),
                message = strings.text(if (googleConnected) {
                    "profile.google_play.connected"
                } else if (mirkoriAccountState.kind == MirkoriAccountStateKind.LINKED) {
                    "profile.google_play.mirkori_connected"
                } else {
                    "profile.google_play.disconnected"
                }),
                accent = PageColors.Profile,
            ) {
            val legacyGoogleActionsAllowed = mirkoriAccountState.kind == MirkoriAccountStateKind.GUEST
            if (googleConnected) {
                if (legacyGoogleActionsAllowed) {
                    OutlinedButton(
                        onClick = onGooglePlaySignOut,
                        enabled = !authInProgress,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(strings.text("profile.google_play.sign_out"))
                    }
                }
            } else if (
                mirkoriAccountState.kind == MirkoriAccountStateKind.GUEST ||
                mirkoriAccountState.kind == MirkoriAccountStateKind.LINKED
            ) {
                Button(
                    onClick = onGooglePlaySignIn,
                    enabled = !authInProgress,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(strings.text("profile.google_play.sign_in"))
                }
            }
            val googleResultKey = if (authInProgress) "profile.auth.in_progress" else authResultKey
            googleResultKey?.let { key ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = if (key in AuthErrorKeys) {
                        PageColors.Error.copy(alpha = 0.12f)
                    } else {
                        PageColors.Success.copy(alpha = 0.14f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (key in AuthErrorKeys) PageColors.Error else PageColors.Success,
                    ),
                ) {
                    Text(
                        text = strings.text(key),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = PageType.Secondary,
                    )
                }
            }
            }
        }

        PageSectionHeader(strings.text("profile.overview"))
        ProfileOverview(progressState)

        SceneCard(accentColor = PageColors.Cream) {
            Text(
                text = strings.text("profile.membership"),
                modifier = Modifier.semantics { heading() },
                style = PageType.CardTitle,
                fontWeight = FontWeight.SemiBold,
            )
            MembershipLine(strings.text("profile.membership.ads"), progressState.adFreePurchased, strings)
            MembershipLine(strings.text("profile.membership.pro"), progressState.proSubscriptionActive, strings)
            MembershipLine(strings.text("profile.membership.pro_plus"), progressState.proPlusSubscriptionActive, strings)
            val temporaryIncluded = progressState.proSubscriptionActive || progressState.proPlusSubscriptionActive
            val temporaryActive = progressState.temporaryProActiveAt(nowMs)
            MembershipLine(
                title = strings.text("profile.membership.temporary_pro"),
                active = temporaryIncluded || temporaryActive,
                strings = strings,
                activeText = when {
                    temporaryIncluded -> strings.text("profile.membership.included")
                    temporaryActive -> strings.text("profile.membership.remaining").replace(
                        "{time}",
                        TemporaryProPolicy.formatRemaining(progressState.temporaryProExpiresAtMs, nowMs),
                    )
                    else -> null
                },
            )
        }

        SceneCard(accentColor = PageColors.Cream) {
            Text(
                text = strings.text("profile.match_stats"),
                modifier = Modifier.semantics { heading() },
                style = PageType.CardTitle,
                fontWeight = FontWeight.SemiBold,
            )
            MatchStatsRow(
                strings.text("profile.match_stats.pve"),
                progressState.pveStats.wins,
                progressState.pveStats.losses,
                strings,
            )
            MatchStatsRow(
                strings.text("profile.match_stats.pvp"),
                progressState.pvpStats.wins,
                progressState.pvpStats.losses,
                strings,
            )
            MatchStatsRow(
                strings.text("section.company.short"),
                progressState.companyStats.wins,
                progressState.companyStats.losses,
                strings,
            )
        }
    }
}

private val AuthErrorKeys = setOf(
    "profile.auth.unavailable",
    "profile.auth.not_configured",
    "profile.auth.rejected",
)

private val MirkoriAuthErrorKeys = setOf(
    "profile.mirkori.unavailable",
    "profile.mirkori.rejected",
    "profile.mirkori.conflict",
)

@Composable
private fun ProfileOverview(progressState: GameProgressState) {
    val strings = LocalAppStrings.current
    val stats = listOf(
        strings.text("profile.campaign_level") to progressState.highestUnlockedCampaignLevel.toString(),
        strings.text("profile.campaign_rating") to progressState.totalCampaignRating.toString(),
        strings.text("top.coins") to progressState.coins.toString(),
        strings.text("profile.matches") to progressState.matchesPlayed.toString(),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        stats.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { (label, value) ->
                    StatTile(label, value, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun MembershipLine(
    title: String,
    active: Boolean,
    strings: LocalizationProvider,
    activeText: String? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (active) {
            PageColors.Success.copy(alpha = 0.12f)
        } else {
            PageColors.CreamSecondary
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                text = if (active) {
                    activeText ?: strings.text("profile.membership.active")
                } else {
                    strings.text("profile.membership.locked")
                },
                fontWeight = FontWeight.SemiBold,
                color = if (active) PageColors.Success else PageColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun MatchStatsRow(
    title: String,
    wins: Int,
    losses: Int,
    strings: LocalizationProvider,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(
            strings.text("profile.match_stats.result")
                .replace("{wins}", wins.toString())
                .replace("{losses}", losses.toString()),
        )
    }
}

internal fun playerInitials(displayName: String): String {
    val parts = displayName
        .trim()
        .split(Regex("\\s+|_+"))
        .filter(String::isNotBlank)
    return when {
        parts.isEmpty() -> "IX"
        parts.size == 1 -> parts.first().take(2).uppercase()
        else -> "${parts.first().first()}${parts.last().first()}".uppercase()
    }
}

private val PublicProfileSuccessKeys = setOf(
    "profile.mirkori.handle.saved",
    "profile.mirkori.name.saved",
    "profile.mirkori.avatar.saved",
)
