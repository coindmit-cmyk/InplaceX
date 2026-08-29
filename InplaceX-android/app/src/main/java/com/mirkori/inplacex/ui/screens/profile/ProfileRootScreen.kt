package com.mirkori.inplacex.ui.screens.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mirkori.inplacex.ui.screens.shared.PagePrimaryButton as Button
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import com.mirkori.inplacex.R
import com.mirkori.inplacex.core.monetization.TemporaryProPolicy
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import com.mirkori.inplacex.platform.mirkori.MirkoriAccountState
import com.mirkori.inplacex.platform.mirkori.MirkoriAccountStateKind
import com.mirkori.platform.sdk.PlatformAuthMode
import com.mirkori.inplacex.platform.mirkori.MirkoriPublicPlayerProfile
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import com.mirkori.inplacex.ui.screens.shared.ScenePageColumn
import com.mirkori.inplacex.ui.screens.shared.PlayerAvatar
import com.mirkori.inplacex.ui.screens.shared.PlayerAvatarPresets
import com.mirkori.inplacex.ui.theme.PageColors
import com.mirkori.inplacex.ui.theme.PageType
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
    onOpenShop: () -> Unit = {},
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
        modifier = Modifier.fillMaxSize().testTag("profile-reference-screen"),
        scrollable = true,
        verticalSpacing = 8.dp,
        horizontalPadding = 16.dp,
        verticalPadding = 0.dp,
    ) {
        ProfileIdentityCard(
            displayName = visibleDisplayName,
            handle = publicPlayerProfile?.handle,
            avatarUrl = publicPlayerProfile?.avatarUrl,
            accountStatus = when (mirkoriAccountState.kind) {
                MirkoriAccountStateKind.LINKED -> strings.text("profile.mirkori.connected.short")
                MirkoriAccountStateKind.GUEST -> strings.text("profile.mirkori.guest.short")
                MirkoriAccountStateKind.INITIALIZING -> strings.text("profile.mirkori.initializing")
                MirkoriAccountStateKind.UNAVAILABLE -> strings.text("profile.mirkori.unavailable.short")
            },
            unlockedCampaignLevel = progressState.highestUnlockedCampaignLevel,
            campaignRating = progressState.totalCampaignRating,
            playerIdAvailable = mirkoriAccountState.gamePlayerId != null,
            publicProfileInProgress = publicProfileInProgress,
            onNameClick = if (mirkoriAccountState.gamePlayerId == null) null else {
                {
                    nameInput = visibleDisplayName
                    localNameError = false
                    nameDialogOpen = true
                }
            },
            onAvatarClick = if (mirkoriAccountState.gamePlayerId == null) null else {
                { avatarDialogOpen = true }
            },
            onPublicIdClick = if (mirkoriAccountState.gamePlayerId == null) null else {
                {
                    handleInput = publicPlayerProfile?.handle.orEmpty()
                    localHandleError = false
                    handleDialogOpen = true
                }
            },
        )

        if (mirkoriAccountState.kind != MirkoriAccountStateKind.LINKED) {
            ProfileAccountActionCard(
                message = when (mirkoriAccountState.kind) {
                    MirkoriAccountStateKind.LINKED -> strings.text("profile.mirkori.connected")
                    MirkoriAccountStateKind.GUEST -> strings.text("profile.mirkori.guest")
                    MirkoriAccountStateKind.INITIALIZING -> strings.text("profile.mirkori.initializing")
                    MirkoriAccountStateKind.UNAVAILABLE -> strings.text("profile.mirkori.unavailable")
                },
                actionLabel = strings.text("profile.mirkori.sign_in"),
                actionEnabled = !mirkoriAuthInProgress &&
                    mirkoriAccountState.kind != MirkoriAccountStateKind.INITIALIZING,
                onAction = onMirkoriSignIn,
            )
        }

        publicProfileResultKey?.let { key ->
            ProfileResultCard(
                text = strings.text(key),
                success = key in PublicProfileSuccessKeys,
            )
        }
        val mirkoriResultKey = if (mirkoriAuthInProgress) "profile.mirkori.in_progress" else mirkoriAuthResultKey
        mirkoriResultKey?.let { key ->
            ProfileResultCard(
                text = strings.text(key),
                success = key !in MirkoriAuthErrorKeys,
            )
        }

        val googleConnected = if (mirkoriAccountState.kind == MirkoriAccountStateKind.LINKED) {
            mirkoriAccountState.authMode == PlatformAuthMode.GOOGLE
        } else {
            progressState.googlePlaySignedIn
        }
        val googleActionAllowed = showGooglePlayCard &&
            mirkoriAccountState.kind == MirkoriAccountStateKind.GUEST
        ProfileConnectionsCard(
            showGooglePlayBrand = showGooglePlayCard,
            providerTitle = if (showGooglePlayCard) {
                strings.text("profile.google_play.title")
            } else {
                strings.text("profile.mirkori.title")
            },
            providerStatus = if (showGooglePlayCard) {
                strings.text(
                    if (googleConnected) {
                        "profile.google_play.connected.short"
                    } else if (mirkoriAccountState.kind == MirkoriAccountStateKind.LINKED) {
                        "profile.google_play.disconnected.short"
                    } else {
                        "profile.google_play.disconnected"
                    },
                )
            } else {
                when (mirkoriAccountState.kind) {
                    MirkoriAccountStateKind.LINKED -> strings.text("profile.connection.connected")
                    MirkoriAccountStateKind.GUEST -> strings.text("profile.connection.guest")
                    MirkoriAccountStateKind.INITIALIZING -> strings.text("profile.connection.connecting")
                    MirkoriAccountStateKind.UNAVAILABLE -> strings.text("profile.connection.unavailable")
                }
            },
            connected = if (showGooglePlayCard) googleConnected else
                mirkoriAccountState.kind == MirkoriAccountStateKind.LINKED,
            actionLabel = when {
                !googleActionAllowed -> null
                googleConnected -> strings.text("profile.google_play.sign_out")
                else -> strings.text("profile.google_play.sign_in")
            },
            actionEnabled = !authInProgress,
            onAction = when {
                !googleActionAllowed -> null
                googleConnected -> onGooglePlaySignOut
                else -> onGooglePlaySignIn
            },
        )

        val googleResultKey = if (authInProgress) "profile.auth.in_progress" else authResultKey
        if (showGooglePlayCard) googleResultKey?.let { key ->
            ProfileResultCard(
                text = strings.text(key),
                success = key !in AuthErrorKeys,
            )
        }

        ProfileSectionTitle(
            title = strings.text("profile.overview"),
            modifier = Modifier
                .padding(top = 4.dp)
                .testTag("profile-overview"),
        )
        ProfileOverview(progressState)

        ProfileSectionTitle(
            title = strings.text("profile.purchases_and_subscriptions"),
            modifier = Modifier
                .padding(top = 5.dp)
                .testTag("profile-purchases"),
        )
        ProfilePremiumSummary(progressState, nowMs, onOpenShop)

        mirkoriAccountState.gamePlayerId?.let { playerId ->
            ProfilePlayerIdRow(
                label = strings.text("profile.mirkori.player_id").replace("{id}", playerId.takeLast(8)),
                copyDescription = strings.text("profile.mirkori.copy_player_id"),
                onCopy = { clipboardManager.setText(AnnotatedString(playerId)) },
            )
        }

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

@Composable
private fun ProfileIdentityCard(
    displayName: String,
    handle: String?,
    avatarUrl: String?,
    accountStatus: String,
    unlockedCampaignLevel: Int,
    campaignRating: Int,
    playerIdAvailable: Boolean,
    publicProfileInProgress: Boolean,
    onNameClick: (() -> Unit)?,
    onAvatarClick: (() -> Unit)?,
    onPublicIdClick: (() -> Unit)?,
) {
    val strings = LocalAppStrings.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 257.dp)
            .testTag("profile-hero"),
        shape = ProfileHeroShape,
        color = Color.Transparent,
        contentColor = Color.White,
        border = BorderStroke(1.dp, ProfileBlueRim),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .background(Brush.verticalGradient(ProfileBlueGradient)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 128.dp)
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerAvatar(
                    displayName = displayName,
                    avatarUrl = avatarUrl,
                    modifier = Modifier.size(104.dp),
                    onClick = onAvatarClick,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = displayName,
                        modifier = Modifier
                            .semantics { heading() }
                            .then(
                                if (onNameClick == null) Modifier else Modifier.clickable(onClick = onNameClick),
                            ),
                        color = Color.White,
                        fontSize = 26.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = handle?.let { "@$it" } ?: strings.text("profile.mirkori.handle.not_set"),
                        color = Color.White.copy(alpha = .96f),
                        style = PageType.Body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = accountStatus,
                        color = Color.White.copy(alpha = .88f),
                        style = PageType.Secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 75.dp),
                shape = RoundedCornerShape(0.dp),
                color = ProfileBlueBand,
                border = BorderStroke(1.dp, ProfileBlueRim.copy(alpha = .72f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = strings.text("profile.progress"),
                            color = Color.White.copy(alpha = .76f),
                            fontSize = 11.sp,
                            lineHeight = 13.sp,
                        )
                        Text(
                            text = "${strings.text("profile.campaign_level")}: $unlockedCampaignLevel",
                            color = Color.White,
                            fontSize = 14.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.art_profile_trophy_v10),
                            contentDescription = strings.text("profile.campaign_rating"),
                            modifier = Modifier.size(44.dp),
                        )
                        Text(
                            text = campaignRating.toString(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .then(
                        if (onPublicIdClick == null) Modifier else Modifier.clickable(
                            enabled = !publicProfileInProgress,
                            onClick = onPublicIdClick,
                        ),
                    ),
                shape = RoundedCornerShape(0.dp),
                color = ProfileBlueEditBand,
                border = BorderStroke(1.dp, ProfileBlueRim.copy(alpha = .72f)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = null,
                        tint = ProfileCream,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = if (playerIdAvailable) {
                            strings.text("profile.mirkori.handle.change")
                        } else {
                            strings.text("profile.mirkori.handle.not_set")
                        },
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        fontSize = 15.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (onPublicIdClick != null) {
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = ProfileCream,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileAccountActionCard(
    message: String,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    ProfileCreamCard {
        Text(message, style = PageType.Secondary, color = PageColors.TextSecondary)
        Button(
            onClick = onAction,
            enabled = actionEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(actionLabel)
        }
    }
}

@Composable
private fun ProfileResultCard(text: String, success: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (success) ProfileSuccess.copy(alpha = .20f) else PageColors.Error.copy(alpha = .16f),
        border = BorderStroke(1.dp, if (success) ProfileSuccess else PageColors.Error),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            style = PageType.Secondary,
            color = PageColors.Text,
        )
    }
}

@Composable
private fun ProfileConnectionsCard(
    showGooglePlayBrand: Boolean,
    providerTitle: String,
    providerStatus: String,
    connected: Boolean,
    actionLabel: String?,
    actionEnabled: Boolean,
    onAction: (() -> Unit)?,
) {
    ProfileCreamCard(
        modifier = Modifier
            .heightIn(min = 109.dp)
            .testTag("profile-connections"),
        contentPadding = 10,
        verticalSpacing = 8,
        backgroundGradient = ProfileConnectionGradient,
    ) {
        Text(
            text = LocalAppStrings.current.text("profile.connections"),
            style = PageType.CardTitle.copy(fontSize = 17.sp, lineHeight = 20.sp),
            modifier = Modifier.padding(top = 4.dp).semantics { heading() },
        )
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(11.dp),
            color = ProfileCreamHighlight,
            border = BorderStroke(1.dp, ProfileGoldBorder.copy(alpha = .65f)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = if (showGooglePlayBrand) Color(0xFFFFF8E8) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (showGooglePlayBrand) {
                        GooglePlayMark(Modifier.size(27.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            tint = ProfileBlueAccent,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = providerTitle,
                        style = PageType.Body.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = providerStatus,
                        color = if (connected) ProfileSuccess else PageColors.TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (actionLabel != null && onAction != null) {
                    Surface(
                        onClick = onAction,
                        enabled = actionEnabled,
                        shape = RoundedCornerShape(10.dp),
                        color = ProfileCream,
                        border = BorderStroke(1.dp, ProfileGoldBorder),
                    ) {
                        Text(
                            text = actionLabel,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
                            color = ProfileBlueAccent,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GooglePlayMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val leftTopX = width * .08f
        val leftBottomX = width * .08f
        val middleX = width * .43f
        val topY = height * .06f
        val middleTopY = height * .29f
        val middleBottomY = height * .71f
        val bottomY = height * .94f
        val tipX = width * .94f
        val tipY = height * .50f

        drawPath(
            path = Path().apply {
                moveTo(leftTopX, topY)
                lineTo(middleX, middleTopY)
                lineTo(tipX, tipY)
                close()
            },
            color = Color(0xFF2F80ED),
        )
        drawPath(
            path = Path().apply {
                moveTo(leftTopX, topY)
                lineTo(leftBottomX, bottomY)
                lineTo(middleX, middleBottomY)
                lineTo(middleX, middleTopY)
                close()
            },
            color = Color(0xFF00A65A),
        )
        drawPath(
            path = Path().apply {
                moveTo(middleX, middleTopY)
                lineTo(middleX, middleBottomY)
                lineTo(tipX, tipY)
                close()
            },
            color = Color(0xFFFFCC32),
        )
        drawPath(
            path = Path().apply {
                moveTo(leftBottomX, bottomY)
                lineTo(tipX, tipY)
                lineTo(middleX, middleBottomY)
                close()
            },
            color = Color(0xFFEA4335),
        )
    }
}

@Composable
private fun ProfileSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        color = Color.White,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(top = 1.dp).semantics { heading() },
    )
}

@Composable
private fun ProfilePremiumSummary(
    progressState: GameProgressState,
    nowMs: Long,
    onOpenShop: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val premiumActive = progressState.proSubscriptionActive ||
        progressState.proPlusSubscriptionActive || progressState.temporaryProActiveAt(nowMs)
    val status = strings.text(
        if (premiumActive) "profile.premium.active" else "profile.premium.inactive",
    )
    Surface(
        onClick = onOpenShop,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 57.dp)
            .testTag("profile-premium")
            .semantics { stateDescription = status },
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, ProfileGoldBorder),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .background(Brush.verticalGradient(ProfilePremiumGradient))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.art_premium_crown_v10),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(44.dp),
                )
            }
            Text(
                text = strings.text("profile.premium_account"),
                modifier = Modifier.weight(1f),
                style = PageType.Body.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = status,
                color = if (premiumActive) ProfileSuccess else PageColors.Error,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = ProfileGoldBorder,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ProfilePlayerIdRow(label: String, copyDescription: String, onCopy: () -> Unit) {
    ProfileCreamCard(contentPadding = 8, verticalSpacing = 0) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), style = PageType.Secondary)
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = copyDescription,
                    tint = ProfileBlueAccent,
                )
            }
        }
    }
}

@Composable
private fun ProfileCreamCard(
    modifier: Modifier = Modifier,
    contentPadding: Int = 12,
    verticalSpacing: Int = 8,
    backgroundGradient: List<Color> = ProfileCreamGradient,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        contentColor = PageColors.Text,
        border = BorderStroke(1.dp, ProfileGoldBorder),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .background(Brush.verticalGradient(backgroundGradient))
                .padding(contentPadding.dp),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing.dp),
            content = content,
        )
    }
}

private val ProfileBlueGradient = listOf(
    Color(0xFF1268AE),
    Color(0xFF0A4778),
    Color(0xFF062D4E),
)
private val ProfileCreamGradient = listOf(
    Color(0xFFFFEFD1),
    Color(0xFFFBE8C4),
    Color(0xFFF6DDAE),
)
private val ProfileConnectionGradient = listOf(
    Color(0xFFFCEAC6),
    Color(0xFFF8E1B7),
    Color(0xFFF2D49F),
)
private val ProfilePremiumGradient = listOf(
    Color(0xFFFCE8C2),
    Color(0xFFF7DCAE),
    Color(0xFFF1CE96),
)
private val ProfileBlueBand = Color(0xFF042F5A)
private val ProfileBlueEditBand = Color(0xFF19436A)
private val ProfileBlueAccent = Color(0xFF126AB2)
private val ProfileBlueRim = Color(0xFF3D8DBD)
private val ProfileCream = Color(0xFFFBE8C4)
private val ProfileCreamHighlight = Color(0xFFFDECCD)
private val ProfileGoldBorder = Color(0xFFC28B43)
private val ProfileSuccess = Color(0xFF347A2B)

private val ProfileHeroShape = GenericShape { size, _ ->
    val width = size.width
    val height = size.height
    moveTo(0f, height * .10f)
    cubicTo(
        width * .02f,
        height * .08f,
        width * .04f,
        height * .055f,
        width * .07f,
        height * .045f,
    )
    cubicTo(
        width * .12f,
        0f,
        width * .24f,
        0f,
        width * .31f,
        height * .025f,
    )
    cubicTo(
        width * .42f,
        height * .06f,
        width * .75f,
        height * .035f,
        width * .92f,
        height * .065f,
    )
    cubicTo(
        width * .975f,
        height * .07f,
        width,
        height * .085f,
        width,
        height * .12f,
    )
    lineTo(width, height * .93f)
    quadraticBezierTo(width, height, width * .95f, height)
    lineTo(width * .05f, height)
    quadraticBezierTo(0f, height, 0f, height * .93f)
    close()
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
        ProfileStat(
            label = strings.text("profile.campaign_level"),
            value = progressState.highestUnlockedCampaignLevel.toString(),
            iconRes = R.drawable.art_profile_map_v10,
        ),
        ProfileStat(
            label = strings.text("profile.campaign_rating"),
            value = progressState.totalCampaignRating.toString(),
            iconRes = R.drawable.art_profile_star_v10,
        ),
        ProfileStat(
            label = strings.text("top.coins"),
            value = progressState.coins.toString(),
            fallbackIcon = Icons.Outlined.MonetizationOn,
            accent = Color(0xFFCE7800),
        ),
        ProfileStat(
            label = strings.text("profile.matches"),
            value = progressState.matchesPlayed.toString(),
            iconRes = R.drawable.art_profile_matches_v10,
        ),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.chunked(2).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                row.forEachIndexed { columnIndex, stat ->
                    ProfileStatTile(
                        stat,
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTag("profile-stat-${rowIndex * 2 + columnIndex}"),
                    )
                }
            }
        }
    }
}

private data class ProfileStat(
    val label: String,
    val value: String,
    val iconRes: Int? = null,
    val fallbackIcon: ImageVector? = null,
    val accent: Color = ProfileBlueAccent,
)

@Composable
private fun ProfileStatTile(stat: ProfileStat, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.heightIn(min = 83.dp),
        shape = RoundedCornerShape(15.dp),
        color = Color.Transparent,
        contentColor = PageColors.Text,
        border = BorderStroke(1.dp, ProfileGoldBorder),
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .background(Brush.verticalGradient(ProfileCreamGradient))
                .padding(horizontal = 6.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(42.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (stat.iconRes != null) {
                    Image(
                        painter = painterResource(stat.iconRes),
                        contentDescription = null,
                        modifier = Modifier.requiredSize(
                            when (stat.iconRes) {
                                R.drawable.art_profile_star_v10 -> 60.dp
                                R.drawable.art_profile_matches_v10 -> 52.dp
                                else -> 42.dp
                            },
                        ),
                    )
                } else {
                    Icon(
                        imageVector = requireNotNull(stat.fallbackIcon),
                        contentDescription = null,
                        tint = stat.accent,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stat.label,
                    color = PageColors.Text.copy(alpha = .82f),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stat.value,
                    color = PageColors.Text,
                    fontSize = 22.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
