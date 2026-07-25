package com.mirkori.inplacex.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.screens.shared.SceneActionTile
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import com.mirkori.inplacex.ui.screens.shared.ScenePageColumn
import com.mirkori.inplacex.ui.screens.shared.SceneSplitStatRow

@Composable
fun ProfileRootScreen(
    progressState: GameProgressState,
    onGooglePlaySignIn: () -> Unit = {},
    onGooglePlaySignOut: () -> Unit = {},
) {
    val strings = LocalAppStrings.current

    ScenePageColumn(
        modifier = Modifier.fillMaxSize(),
        scrollable = true
    ) {
        SceneCard(accentColor = Color.White.copy(alpha = 0.76f)) {
            Text(
                text = progressState.playerDisplayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = strings.text("profile.account"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SceneSplitStatRow(
                leftLabel = strings.text("top.coins"),
                leftValue = progressState.coins.toString(),
                rightLabel = strings.text("top.energy"),
                rightValue = "${progressState.campaignEnergy}/${progressState.campaignEnergyMax}",
            )
            Button(
                onClick = if (progressState.googlePlaySignedIn) onGooglePlaySignOut else onGooglePlaySignIn,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (progressState.googlePlaySignedIn) {
                        strings.text("profile.google_play.sign_out")
                    } else {
                        strings.text("profile.google_play.sign_in")
                    }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SceneActionTile(
                title = strings.text("profile.progress"),
                subtitle = "${strings.text("profile.campaign_level")}: ${progressState.highestUnlockedCampaignLevel}",
                modifier = Modifier.weight(1f),
                accentBrush = Brush.verticalGradient(listOf(Color(0xFF6FB6FF), Color(0xFF4C6FFF))),
                onClick = {}
            )
            SceneActionTile(
                title = strings.text("profile.google_play.title"),
                subtitle = if (progressState.googlePlaySignedIn) {
                    strings.text("profile.google_play.connected")
                } else {
                    strings.text("profile.google_play.guest")
                },
                modifier = Modifier.weight(1f),
                accentBrush = Brush.verticalGradient(listOf(Color(0xFF6FD8B5), Color(0xFF2FA77D))),
                onClick = {}
            )
        }

        SceneCard(accentColor = Color.White.copy(alpha = 0.72f)) {
            Text(
                text = strings.text("profile.membership"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            MembershipLine(strings.text("profile.membership.ads"), progressState.adFreePurchased, strings)
            MembershipLine(strings.text("profile.membership.pro"), progressState.proSubscriptionActive, strings)
            MembershipLine(strings.text("profile.membership.pro_plus"), progressState.proPlusSubscriptionActive, strings)
        }

        SceneCard(accentColor = Color.White.copy(alpha = 0.72f)) {
            Text(
                text = strings.text("profile.progress"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text("${strings.text("profile.campaign_level")}: ${progressState.highestUnlockedCampaignLevel}")
            Text("${strings.text("profile.campaign_rating")}: ${progressState.totalCampaignRating}")
            Text(
                text = if (progressState.googlePlaySignedIn) {
                    strings.text("profile.google_play.connected")
                } else {
                    strings.text("profile.google_play.disconnected")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SceneCard(accentColor = Color.White.copy(alpha = 0.72f)) {
            Text(
                text = strings.text("profile.match_stats"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            MatchStatsRow(strings.text("profile.match_stats.pve"), progressState.pveStats.wins, progressState.pveStats.losses, strings)
            MatchStatsRow(strings.text("profile.match_stats.pvp"), progressState.pvpStats.wins, progressState.pvpStats.losses, strings)
            MatchStatsRow(strings.text("section.company.short"), progressState.companyStats.wins, progressState.companyStats.losses, strings)
        }
    }
}

@Composable
private fun MembershipLine(
    title: String,
    active: Boolean,
    strings: com.mirkori.inplacex.platform.localization.LocalizationProvider,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title)
        Text(
            text = if (active) strings.text("profile.membership.active") else strings.text("profile.membership.locked"),
            fontWeight = FontWeight.SemiBold,
            color = if (active) Color(0xFF2E7D32) else Color(0xFF8A93A8)
        )
    }
}

@Composable
private fun MatchStatsRow(
    title: String,
    wins: Int,
    losses: Int,
    strings: com.mirkori.inplacex.platform.localization.LocalizationProvider,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(
            strings.text("profile.match_stats.result")
                .replace("{wins}", wins.toString())
                .replace("{losses}", losses.toString())
        )
    }
}
