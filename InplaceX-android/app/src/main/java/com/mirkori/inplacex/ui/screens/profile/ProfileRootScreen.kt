package com.mirkori.inplacex.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.platform.localization.LocalAppStrings

@Composable
fun ProfileRootScreen(
    progressState: GameProgressState,
    onGooglePlaySignIn: () -> Unit = {},
    onGooglePlaySignOut: () -> Unit = {},
    onAddDeveloperCoins: () -> Unit = {},
) {
    val strings = LocalAppStrings.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = strings.text("profile.title"),
            style = MaterialTheme.typography.headlineSmall
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(strings.text("profile.account"), style = MaterialTheme.typography.titleMedium)
                Text("${strings.text("profile.player")}: ${progressState.playerDisplayName}", fontWeight = FontWeight.SemiBold)
                Text(
                    if (progressState.googlePlaySignedIn) {
                        strings.text("profile.google_play.connected")
                    } else {
                        strings.text("profile.google_play.disconnected")
                    }
                )
                Button(
                    onClick = if (progressState.googlePlaySignedIn) onGooglePlaySignOut else onGooglePlaySignIn
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
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(strings.text("profile.progress"), style = MaterialTheme.typography.titleMedium)
                Text("${strings.text("top.coins")}: ${progressState.coins}")
                Text("${strings.text("top.energy")}: ${progressState.campaignEnergy}/${progressState.campaignEnergyMax}")
                Text("${strings.text("profile.campaign_level")}: ${progressState.highestUnlockedCampaignLevel}")
                Text("${strings.text("profile.campaign_rating")}: ${progressState.totalCampaignRating}")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(strings.text("profile.membership"), style = MaterialTheme.typography.titleMedium)
                Text(strings.text(if (progressState.adFreePurchased) "profile.ads_removed.yes" else "profile.ads_removed.no"))
                Text(strings.text(if (progressState.proSubscriptionActive) "profile.pro.yes" else "profile.pro.no"))
                Text(strings.text(if (progressState.proPlusSubscriptionActive) "profile.pro_plus.yes" else "profile.pro_plus.no"))
            }
        }

        StatsCard("PvE Race", progressState.pveStats.wins, progressState.pveStats.losses)
        StatsCard("PvP Duel", progressState.pvpStats.wins, progressState.pvpStats.losses)
        StatsCard("Company", progressState.companyStats.wins, progressState.companyStats.losses)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(strings.text("profile.developer"), style = MaterialTheme.typography.titleMedium)
                Button(onClick = onAddDeveloperCoins) {
                    Text(strings.text("profile.developer.add_coins"))
                }
            }
        }
    }
}

@Composable
private fun StatsCard(
    title: String,
    wins: Int,
    losses: Int,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Wins: $wins")
                Text("Losses: $losses")
            }
        }
    }
}
