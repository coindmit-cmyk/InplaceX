package com.mirkori.inplacex.ui.screens.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.screens.devbot.BotLabScreen
import com.mirkori.inplacex.ui.screens.shared.SceneActionTile
import com.mirkori.inplacex.ui.screens.shared.SceneBackdrop
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import com.mirkori.inplacex.ui.screens.shared.SceneSplitStatRow

@Composable
fun DeveloperRootScreen(
    progressState: GameProgressState,
    onAddCoins: () -> Unit,
    onAddHelpers: () -> Unit,
    onClearBoosts: () -> Unit,
    onRefillEnergy: () -> Unit,
    onEnableAdFree: () -> Unit,
    onEnablePro: () -> Unit,
    onEnableProPlus: () -> Unit,
) {
    var isBotLabOpen by remember { mutableStateOf(false) }
    val strings = LocalAppStrings.current

    if (isBotLabOpen) {
        BotLabScreen(onBack = { isBotLabOpen = false })
        return
    }

    SceneBackdrop(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        topColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
        bottomColor = Color(0xFFF6FBFF),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SceneCard {
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

            SceneCard {
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

            SceneCard {
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
                Button(
                    onClick = onEnableProPlus,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(strings.text("developer.action.enable_pro_plus"))
                }
            }

            SceneActionTile(
                title = strings.text("developer.bot_lab.title"),
                subtitle = strings.text("developer.bot_lab.subtitle"),
                onClick = { isBotLabOpen = true }
            )
        }
    }
}
