package com.mirkori.inplacex.ui.screens.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.monetization.TemporaryProPolicy
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.platform.localization.LocalizationProvider
import com.mirkori.inplacex.platform.mirkori.MirkoriAccountState
import com.mirkori.inplacex.platform.mirkori.MirkoriAccountStateKind
import com.mirkori.inplacex.ui.screens.shared.SceneBadge
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import com.mirkori.inplacex.ui.screens.shared.ScenePageColumn
import com.mirkori.inplacex.ui.theme.InplaceXColors

@Composable
fun ProfileRootScreen(
    progressState: GameProgressState,
    nowMs: Long = System.currentTimeMillis(),
    mirkoriAccountState: MirkoriAccountState = MirkoriAccountState(MirkoriAccountStateKind.INITIALIZING),
    mirkoriAuthResultKey: String? = null,
    mirkoriAuthInProgress: Boolean = false,
    authResultKey: String? = null,
    authInProgress: Boolean = false,
    showGooglePlayCard: Boolean = false,
    onMirkoriSignIn: () -> Unit = {},
    onGooglePlaySignIn: () -> Unit = {},
    onGooglePlaySignOut: () -> Unit = {},
) {
    val strings = LocalAppStrings.current

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
                Surface(
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                    color = InplaceXColors.Cobalt,
                    contentColor = InplaceXColors.White,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = playerInitials(progressState.playerDisplayName),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = progressState.playerDisplayName,
                        modifier = Modifier.semantics { heading() },
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
                    text = strings.text("profile.mirkori.player_id").replace("{id}", playerId.takeLast(8)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        InplaceXColors.Coral.copy(alpha = 0.12f)
                    } else {
                        InplaceXColors.Mint.copy(alpha = 0.14f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (key in MirkoriAuthErrorKeys) InplaceXColors.Coral else InplaceXColors.Mint,
                    ),
                ) {
                    Text(
                        text = strings.text(key),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (showGooglePlayCard) {
            SceneCard(accentColor = InplaceXColors.ToyCream.copy(alpha = 0.95f)) {
            Text(
                text = strings.text("profile.google_play.title"),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (progressState.googlePlaySignedIn) {
                    strings.text("profile.google_play.connected")
                } else {
                    strings.text("profile.google_play.disconnected")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val legacyGoogleActionsAllowed = mirkoriAccountState.kind == MirkoriAccountStateKind.GUEST
            if (progressState.googlePlaySignedIn) {
                if (legacyGoogleActionsAllowed) {
                    OutlinedButton(
                        onClick = onGooglePlaySignOut,
                        enabled = !authInProgress,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(strings.text("profile.google_play.sign_out"))
                    }
                }
            } else if (legacyGoogleActionsAllowed) {
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
                        InplaceXColors.Coral.copy(alpha = 0.12f)
                    } else {
                        InplaceXColors.Mint.copy(alpha = 0.14f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (key in AuthErrorKeys) InplaceXColors.Coral else InplaceXColors.Mint,
                    ),
                ) {
                    Text(
                        text = strings.text(key),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
