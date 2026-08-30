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
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
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
import com.mirkori.inplacex.platform.mirkori.MirkoriPublicPlayerProfile
import com.mirkori.inplacex.ui.screens.shared.SceneBadge
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import com.mirkori.inplacex.ui.screens.shared.ScenePageColumn
import com.mirkori.inplacex.ui.screens.shared.PlayerAvatar
import com.mirkori.inplacex.ui.screens.shared.PlayerAvatarPresets
import com.mirkori.inplacex.ui.theme.InplaceXColors
import com.mirkori.platform.sdk.PlatformAuthMode

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
        SceneCard(accentColor = InplaceXColors.ToyCream.copy(alpha = 0.97f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerAvatar(
                    displayName = visibleDisplayName,
                    avatarUrl = publicPlayerProfile?.avatarUrl,
                    modifier = Modifier.size(64.dp),
                    onClick = if (mirkoriAccountState.gamePlayerId == null) null else {
                        { avatarDialogOpen = true }
                    },
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = visibleDisplayName,
                        modifier = Modifier
                            .semantics { heading() }
                            .then(
                                if (mirkoriAccountState.gamePlayerId == null) Modifier else {
                                    Modifier.clickable {
                                        nameInput = visibleDisplayName
                                        localNameError = false
                                        nameDialogOpen = true
                                    }
                                },
                            ),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = when (mirkoriAccountState.kind) {
                            MirkoriAccountStateKind.LINKED -> strings.text("profile.mirkori.connected.short")
                            MirkoriAccountStateKind.GUEST -> strings.text("profile.mirkori.guest.short")
                            MirkoriAccountStateKind.INITIALIZING -> strings.text("profile.mirkori.initializing")
                            MirkoriAccountStateKind.UNAVAILABLE -> strings.text("profile.mirkori.unavailable.short")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = when (mirkoriAccountState.kind) {
                    MirkoriAccountStateKind.LINKED -> strings.text("profile.mirkori.connected")
                    MirkoriAccountStateKind.GUEST -> strings.text("profile.mirkori.guest")
                    MirkoriAccountStateKind.INITIALIZING -> strings.text("profile.mirkori.initializing")
                    MirkoriAccountStateKind.UNAVAILABLE -> strings.text("profile.mirkori.unavailable")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            mirkoriAccountState.gamePlayerId?.let { playerId ->
                Text(
                    text = publicPlayerProfile?.handle?.let { "@$it" }
                        ?: strings.text("profile.mirkori.handle.not_set"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
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
                Text(
                    text = strings.text("profile.mirkori.player_id").replace("{id}", playerId.takeLast(8)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { clipboardManager.setText(AnnotatedString(playerId)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(strings.text("profile.mirkori.copy_player_id"))
                }
            }

            publicProfileResultKey?.let { key ->
                Text(
                    text = strings.text(key),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (key in PublicProfileSuccessKeys) {
                        InplaceXColors.Mint
                    } else {
                        InplaceXColors.Coral
                    },
                )
            }

        }

        SceneCard(
            modifier = Modifier.testTag("profile-connections"),
            accentColor = InplaceXColors.ToyCream.copy(alpha = 0.95f),
        ) {
            Text(
                text = strings.text("profile.connections"),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            ProfileConnectionBlock(
                title = strings.text("profile.mirkori.title"),
                status = when (mirkoriAccountState.kind) {
                    MirkoriAccountStateKind.LINKED -> strings.text("profile.mirkori.connected")
                    MirkoriAccountStateKind.GUEST -> strings.text("profile.mirkori.guest")
                    MirkoriAccountStateKind.INITIALIZING -> strings.text("profile.mirkori.initializing")
                    MirkoriAccountStateKind.UNAVAILABLE -> strings.text("profile.mirkori.unavailable")
                },
                action = mirkoriConnectionAction(mirkoriAccountState.kind),
                actionLabel = strings.text("profile.mirkori.sign_in"),
                actionEnabled = !mirkoriAuthInProgress,
                onAction = onMirkoriSignIn,
                testTag = "profile-connection-mirkori",
            )

            val mirkoriResultKey = if (mirkoriAuthInProgress) {
                "profile.mirkori.in_progress"
            } else {
                mirkoriAuthResultKey
            }
            mirkoriResultKey?.let { key ->
                ProfileConnectionNotice(
                    text = strings.text(key),
                    error = key in MirkoriAuthErrorKeys,
                )
            }

            if (showGooglePlayCard) {
                val googleConnected = googleConnectionIsActive(
                    locallySignedIn = progressState.googlePlaySignedIn,
                    accountKind = mirkoriAccountState.kind,
                    authMode = mirkoriAccountState.authMode,
                )
                val googleAction = googleConnectionAction(
                    showGooglePlay = true,
                    connected = googleConnected,
                    accountKind = mirkoriAccountState.kind,
                    authMode = mirkoriAccountState.authMode,
                )
                ProfileConnectionBlock(
                    title = strings.text("profile.google_play.title"),
                    status = if (googleConnected) {
                        strings.text("profile.google_play.connected")
                    } else {
                        strings.text("profile.google_play.disconnected")
                    },
                    action = googleAction,
                    actionLabel = when (googleAction) {
                        ProfileConnectionAction.SIGN_IN -> strings.text("profile.google_play.sign_in")
                        ProfileConnectionAction.SIGN_OUT -> strings.text("profile.google_play.sign_out")
                        null -> null
                    },
                    actionEnabled = !authInProgress,
                    onAction = when (googleAction) {
                        ProfileConnectionAction.SIGN_OUT -> onGooglePlaySignOut
                        else -> onGooglePlaySignIn
                    },
                    testTag = "profile-connection-google",
                )

                val googleResultKey = if (authInProgress) "profile.auth.in_progress" else authResultKey
                googleResultKey?.let { key ->
                    ProfileConnectionNotice(
                        text = strings.text(key),
                        error = key in AuthErrorKeys,
                    )
                }
            }
        }

        Text(
            text = strings.text("profile.overview"),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        ProfileOverview(progressState)

        SceneCard(accentColor = InplaceXColors.ToyCream.copy(alpha = 0.95f)) {
            Text(
                text = strings.text("profile.membership"),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
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

        SceneCard(accentColor = InplaceXColors.ToyCream.copy(alpha = 0.95f)) {
            Text(
                text = strings.text("profile.match_stats"),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
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

internal enum class ProfileConnectionAction {
    SIGN_IN,
    SIGN_OUT,
}

internal fun mirkoriConnectionAction(accountKind: MirkoriAccountStateKind): ProfileConnectionAction? =
    when (accountKind) {
        MirkoriAccountStateKind.GUEST,
        MirkoriAccountStateKind.UNAVAILABLE,
        -> ProfileConnectionAction.SIGN_IN
        MirkoriAccountStateKind.INITIALIZING,
        MirkoriAccountStateKind.LINKED,
        -> null
    }

internal fun googleConnectionIsActive(
    locallySignedIn: Boolean,
    accountKind: MirkoriAccountStateKind,
    authMode: PlatformAuthMode?,
): Boolean = locallySignedIn && (
    accountKind != MirkoriAccountStateKind.LINKED || authMode == PlatformAuthMode.GOOGLE
)

internal fun googleConnectionAction(
    showGooglePlay: Boolean,
    connected: Boolean,
    accountKind: MirkoriAccountStateKind,
    authMode: PlatformAuthMode?,
): ProfileConnectionAction? = when {
    !showGooglePlay || accountKind == MirkoriAccountStateKind.INITIALIZING -> null
    connected -> ProfileConnectionAction.SIGN_OUT
    accountKind == MirkoriAccountStateKind.GUEST -> ProfileConnectionAction.SIGN_IN
    accountKind == MirkoriAccountStateKind.UNAVAILABLE -> ProfileConnectionAction.SIGN_IN
    accountKind == MirkoriAccountStateKind.LINKED && authMode == PlatformAuthMode.GOOGLE ->
        ProfileConnectionAction.SIGN_IN
    else -> null
}

@Composable
private fun ProfileConnectionBlock(
    title: String,
    status: String,
    action: ProfileConnectionAction?,
    actionLabel: String?,
    actionEnabled: Boolean,
    onAction: () -> Unit,
    testTag: String,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(14.dp),
        color = InplaceXColors.ToyCreamShadow.copy(alpha = 0.32f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (action != null && actionLabel != null) {
                when (action) {
                    ProfileConnectionAction.SIGN_IN -> Button(
                        onClick = onAction,
                        enabled = actionEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("$testTag-sign-in"),
                    ) {
                        Text(actionLabel)
                    }
                    ProfileConnectionAction.SIGN_OUT -> OutlinedButton(
                        onClick = onAction,
                        enabled = actionEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("$testTag-sign-out"),
                    ) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileConnectionNotice(text: String, error: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (error) {
            InplaceXColors.Coral.copy(alpha = 0.12f)
        } else {
            InplaceXColors.Mint.copy(alpha = 0.14f)
        },
        border = BorderStroke(
            1.dp,
            if (error) InplaceXColors.Coral else InplaceXColors.Mint,
        ),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ProfileOverview(progressState: GameProgressState) {
    val strings = LocalAppStrings.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 560.dp
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SceneBadge(
                        label = strings.text("profile.campaign_level"),
                        value = progressState.highestUnlockedCampaignLevel.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    SceneBadge(
                        label = strings.text("profile.campaign_rating"),
                        value = progressState.totalCampaignRating.toString(),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SceneBadge(
                        label = strings.text("top.coins"),
                        value = progressState.coins.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    SceneBadge(
                        label = strings.text("profile.matches"),
                        value = progressState.matchesPlayed.toString(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SceneBadge(
                    label = strings.text("profile.campaign_level"),
                    value = progressState.highestUnlockedCampaignLevel.toString(),
                    modifier = Modifier.weight(1f),
                )
                SceneBadge(
                    label = strings.text("profile.campaign_rating"),
                    value = progressState.totalCampaignRating.toString(),
                    modifier = Modifier.weight(1f),
                )
                SceneBadge(
                    label = strings.text("top.coins"),
                    value = progressState.coins.toString(),
                    modifier = Modifier.weight(1f),
                )
                SceneBadge(
                    label = strings.text("profile.matches"),
                    value = progressState.matchesPlayed.toString(),
                    modifier = Modifier.weight(1f),
                )
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
            InplaceXColors.Mint.copy(alpha = 0.12f)
        } else {
            InplaceXColors.ToyCreamShadow.copy(alpha = 0.48f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (active) {
                    activeText ?: strings.text("profile.membership.active")
                } else {
                    strings.text("profile.membership.locked")
                },
                fontWeight = FontWeight.SemiBold,
                color = if (active) InplaceXColors.Mint else InplaceXColors.InkMuted,
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
