package com.mirkori.inplacex.ui.screens.developer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.data.local.LocalPlatformSnapshot
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.screens.devbot.BotLabScreen
import com.mirkori.inplacex.ui.screens.shared.SceneActionTile
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import com.mirkori.inplacex.ui.screens.shared.ScenePageColumn
import com.mirkori.inplacex.ui.screens.shared.SceneSplitStatRow

@Composable
fun DeveloperRootScreen(
    progressState: GameProgressState,
    platformSnapshot: LocalPlatformSnapshot,
    onAddCoins: () -> Unit,
    onAddHelpers: () -> Unit,
    onClearBoosts: () -> Unit,
    onRefillEnergy: () -> Unit,
    onEnableAdFree: () -> Unit,
    onDisableAdFree: () -> Unit,
    onEnablePro: () -> Unit,
    onDisablePro: () -> Unit,
    onEnableProPlus: () -> Unit,
    onDisableProPlus: () -> Unit,
) {
    var isBotLabOpen by remember { mutableStateOf(false) }
    var isUserDataOpen by remember { mutableStateOf(false) }
    val strings = LocalAppStrings.current

    if (isBotLabOpen) {
        BotLabScreen(onBack = { isBotLabOpen = false })
        return
    }

    ScenePageColumn(
        modifier = Modifier.fillMaxSize(),
        scrollable = true
    ) {
        SceneCard(accentColor = Color.White.copy(alpha = 0.76f)) {
            Text(
                text = strings.text("developer.title"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = strings.text("developer.description"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SceneSplitStatRow(
                leftLabel = strings.text("top.coins"),
                leftValue = progressState.coins.toString(),
                rightLabel = strings.text("top.energy"),
                rightValue = "${progressState.campaignEnergy}/${progressState.campaignEnergyMax}"
            )
        }

        SceneCard(accentColor = Color.White.copy(alpha = 0.72f)) {
            Text(
                text = strings.text("developer.section.resources"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onAddCoins,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(strings.text("developer.action.add_coins"))
                }
                Button(
                    onClick = onAddHelpers,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(strings.text("developer.action.add_helpers"))
                }
            }
            Button(
                onClick = onRefillEnergy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(strings.text("developer.action.refill_energy"))
            }
            Button(
                onClick = onClearBoosts,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(strings.text("developer.action.clear_boosts"))
            }
        }

        SceneCard(accentColor = Color.White.copy(alpha = 0.72f)) {
            Text(
                text = strings.text("developer.section.membership"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onEnableAdFree,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(strings.text("developer.action.enable_ads_free"))
                }
                Button(
                    onClick = onEnablePro,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(strings.text("developer.action.enable_pro"))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onDisableAdFree,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Disable Ads")
                }
                Button(
                    onClick = onDisablePro,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Disable Pro")
                }
            }
            Button(
                onClick = onEnableProPlus,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(strings.text("developer.action.enable_pro_plus"))
            }
            Button(
                onClick = onDisableProPlus,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Disable Pro+")
            }
            SceneSplitStatRow(
                leftLabel = "Ads",
                leftValue = if (progressState.adFreePurchased) "ON" else "OFF",
                rightLabel = "Pro",
                rightValue = when {
                    progressState.proPlusSubscriptionActive -> "PRO+"
                    progressState.proSubscriptionActive -> "PRO"
                    else -> "OFF"
                }
            )
        }

        SceneActionTile(
            title = strings.text("developer.bot_lab.title"),
            subtitle = strings.text("developer.bot_lab.subtitle"),
            onClick = { isBotLabOpen = true }
        )

        SceneActionTile(
            title = "User Data",
            subtitle = "Open the full local user snapshot and entitlement state.",
            onClick = { isUserDataOpen = true }
        )
    }

    if (isUserDataOpen) {
        UserDataDialog(
            snapshot = platformSnapshot,
            onClose = { isUserDataOpen = false }
        )
    }
}

@Composable
private fun UserDataDialog(
    snapshot: LocalPlatformSnapshot,
    onClose: () -> Unit,
) {
    val progress = snapshot.gameProgress

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onClose) {
                Text("Close")
            }
        },
        title = {
            Text("User Data")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UserDataLine("Player", snapshot.playerProfile.displayName)
                UserDataLine("Player ID", snapshot.playerProfile.playerId)
                UserDataLine("Install ID", snapshot.playerProfile.installationId)
                UserDataLine("Auth", snapshot.playerProfile.authProvider.name)
                UserDataLine("Guest", snapshot.playerProfile.isGuest.toString())
                UserDataLine("Online", snapshot.playerProfile.isOnline.toString())
                UserDataLine("Locale", snapshot.playerProfile.locale)
                UserDataLine("Region", snapshot.playerProfile.regionCode)
                UserDataLine("Cloud Rev", snapshot.playerProfile.cloudRevision.toString())
                UserDataLine("Google Play", progress.googlePlaySignedIn.toString())
                UserDataLine("Coins", progress.coins.toString())
                UserDataLine("Energy", "${progress.campaignEnergy}/${progress.campaignEnergyMax}")
                UserDataLine("Energy Refill", "${progress.campaignEnergyRefillMinutes} min")
                UserDataLine("Matches Played", progress.matchesPlayed.toString())
                UserDataLine("Matches Won", progress.matchesWon.toString())
                UserDataLine("Campaign Level", progress.highestUnlockedCampaignLevel.toString())
                UserDataLine("Campaign Rating", progress.totalCampaignRating.toString())
                UserDataLine("Hints", "${progress.openPositionHints}/${progress.checkDigitHints}/${progress.checkPositionHints}")
                UserDataLine("Boosts", "${progress.extraMovesBoosts}/${progress.extraTimeBoosts}")
                UserDataLine("Ads Disabled", progress.adsDisabled.toString())
                UserDataLine("Ad Free", progress.adFreePurchased.toString())
                UserDataLine("Pro", progress.proSubscriptionActive.toString())
                UserDataLine("Pro+", progress.proPlusSubscriptionActive.toString())
                UserDataLine("PVE", "W ${progress.pveStats.wins} / L ${progress.pveStats.losses}")
                UserDataLine("PVP", "W ${progress.pvpStats.wins} / L ${progress.pvpStats.losses}")
                UserDataLine("Company", "W ${progress.companyStats.wins} / L ${progress.companyStats.losses}")
                UserDataLine("Campaign Rows", snapshot.campaignProgress.size.toString())
                UserDataLine("Identity Links", snapshot.identityLinks.size.toString())
                UserDataLine("Relationships", snapshot.relationships.size.toString())
                UserDataLine("Rooms", snapshot.rooms.size.toString())
                UserDataLine("Matches", snapshot.matches.size.toString())
                UserDataLine("Sync Queue", snapshot.pendingSyncOperations.size.toString())
            }
        }
    )
}

@Composable
private fun UserDataLine(
    label: String,
    value: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
