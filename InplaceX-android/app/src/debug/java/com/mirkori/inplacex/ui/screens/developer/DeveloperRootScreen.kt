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
                    Text(strings.text("developer.action.disable_ads_free"))
                }
                Button(
                    onClick = onDisablePro,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(strings.text("developer.action.disable_pro"))
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
                Text(strings.text("developer.action.disable_pro_plus"))
            }
            SceneSplitStatRow(
                leftLabel = strings.text("developer.membership.ads"),
                leftValue = if (progressState.adFreePurchased) "ON" else "OFF",
                rightLabel = strings.text("developer.membership.pro"),
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
            title = strings.text("developer.user_data.title"),
            subtitle = strings.text("developer.user_data.subtitle"),
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
    val strings = LocalAppStrings.current

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = onClose) {
                Text(strings.text("developer.user_data.close"))
            }
        },
        title = {
            Text(strings.text("developer.user_data.title"))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UserDataLine(strings.text("developer.user_data.player"), snapshot.playerProfile.displayName)
                UserDataLine(strings.text("developer.user_data.player_id"), snapshot.playerProfile.playerId)
                UserDataLine(strings.text("developer.user_data.install_id"), snapshot.playerProfile.installationId)
                UserDataLine(strings.text("developer.user_data.auth"), snapshot.playerProfile.authProvider.name)
                UserDataLine(strings.text("developer.user_data.guest"), snapshot.playerProfile.isGuest.toString())
                UserDataLine(strings.text("developer.user_data.online"), snapshot.playerProfile.isOnline.toString())
                UserDataLine(strings.text("developer.user_data.locale"), snapshot.playerProfile.locale)
                UserDataLine(strings.text("developer.user_data.region"), snapshot.playerProfile.regionCode)
                UserDataLine(strings.text("developer.user_data.cloud_revision"), snapshot.playerProfile.cloudRevision.toString())
                UserDataLine(strings.text("developer.user_data.google_play"), progress.googlePlaySignedIn.toString())
                UserDataLine(strings.text("developer.user_data.coins"), progress.coins.toString())
                UserDataLine(strings.text("developer.user_data.energy"), "${progress.campaignEnergy}/${progress.campaignEnergyMax}")
                UserDataLine(
                    strings.text("developer.user_data.energy_refill"),
                    strings.text("developer.user_data.minutes").replace("{value}", progress.campaignEnergyRefillMinutes.toString())
                )
                UserDataLine(strings.text("developer.user_data.matches_played"), progress.matchesPlayed.toString())
                UserDataLine(strings.text("developer.user_data.matches_won"), progress.matchesWon.toString())
                UserDataLine(strings.text("developer.user_data.campaign_level"), progress.highestUnlockedCampaignLevel.toString())
                UserDataLine(strings.text("developer.user_data.campaign_rating"), progress.totalCampaignRating.toString())
                UserDataLine(strings.text("developer.user_data.hints"), "${progress.openPositionHints}/${progress.checkDigitHints}/${progress.checkPositionHints}")
                UserDataLine(strings.text("developer.user_data.boosts"), "${progress.extraMovesBoosts}/${progress.extraTimeBoosts}")
                UserDataLine(strings.text("developer.user_data.ads_disabled"), progress.adsDisabled.toString())
                UserDataLine(strings.text("developer.user_data.ad_free"), progress.adFreePurchased.toString())
                UserDataLine(strings.text("developer.user_data.pro"), progress.proSubscriptionActive.toString())
                UserDataLine(strings.text("developer.user_data.pro_plus"), progress.proPlusSubscriptionActive.toString())
                UserDataLine(strings.text("developer.user_data.pve"), matchResult(strings, progress.pveStats.wins, progress.pveStats.losses))
                UserDataLine(strings.text("developer.user_data.pvp"), matchResult(strings, progress.pvpStats.wins, progress.pvpStats.losses))
                UserDataLine(strings.text("developer.user_data.company"), matchResult(strings, progress.companyStats.wins, progress.companyStats.losses))
                UserDataLine(strings.text("developer.user_data.campaign_rows"), snapshot.campaignProgress.size.toString())
                UserDataLine(strings.text("developer.user_data.identity_links"), snapshot.identityLinks.size.toString())
                UserDataLine(strings.text("developer.user_data.relationships"), snapshot.relationships.size.toString())
                UserDataLine(strings.text("developer.user_data.rooms"), snapshot.rooms.size.toString())
                UserDataLine(strings.text("developer.user_data.matches"), snapshot.matches.size.toString())
                UserDataLine(strings.text("developer.user_data.sync_queue"), snapshot.pendingSyncOperations.size.toString())
            }
        }
    )
}

private fun matchResult(
    strings: com.mirkori.inplacex.platform.localization.LocalizationProvider,
    wins: Int,
    losses: Int,
): String = strings.text("developer.user_data.match_result")
    .replace("{wins}", wins.toString())
    .replace("{losses}", losses.toString())

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
