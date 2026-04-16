package com.mirkori.inplacex.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.data.local.GameProgressState

@Composable
fun ProfileRootScreen(
    progressState: GameProgressState,
    onAddDeveloperCoins: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Profile",
            style = MaterialTheme.typography.headlineSmall
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Coins: ${progressState.coins}", fontWeight = FontWeight.SemiBold)
                Text("Campaign energy: ${progressState.campaignEnergy}/${progressState.campaignEnergyMax}")
                Text("Campaign unlocked level: ${progressState.highestUnlockedCampaignLevel}")
                Text("Campaign total rating: ${progressState.totalCampaignRating}")
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
                Text("Developer", style = MaterialTheme.typography.titleMedium)
                Button(onClick = onAddDeveloperCoins) {
                    Text("+100 монет")
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
