package com.mirkori.inplacex.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.match.RaceRewardPolicy
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.theme.InplaceXColors

@Composable
internal fun RaceResultDialog(
    won: Boolean,
    attemptsUsed: Int,
    attemptLimit: Int,
    elapsedSeconds: Int,
    onRetry: () -> Unit,
    onHome: () -> Unit,
) {
    val strings = LocalAppStrings.current

    AlertDialog(
        onDismissRequest = onHome,
        containerColor = InplaceXColors.ToyCream,
        titleContentColor = InplaceXColors.ToyBrown,
        textContentColor = InplaceXColors.ToyBrown,
        title = {
            Text(
                text = if (won) {
                    strings.text("home.race.result.win_title")
                } else {
                    strings.text("home.race.result.loss_title")
                },
                fontWeight = FontWeight.Black,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (won) {
                        strings.text("home.race.result.win_message")
                    } else {
                        strings.text("home.race.result.loss_message")
                    },
                )
                Text(
                    text = strings.homeRaceAttempts(
                        used = attemptsUsed,
                        limit = attemptLimit,
                    ),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = strings.homeRaceTime(elapsedSeconds),
                    fontWeight = FontWeight.Bold,
                )
                if (won) {
                    Text(
                        text = strings.homeRaceReward(RaceRewardPolicy.WIN_COINS),
                        color = InplaceXColors.ToyGreen,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text(strings.text("home.race.result.retry"))
            }
        },
        dismissButton = {
            TextButton(onClick = onHome) {
                Text(strings.text("home.race.result.home"))
            }
        },
    )
}
