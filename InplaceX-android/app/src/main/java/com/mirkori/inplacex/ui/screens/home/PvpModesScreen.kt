package com.mirkori.inplacex.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.LocalAppStrings

@Composable
fun PvpModesScreen(
    codeLength: Int,
    onCodeLengthChange: (Int) -> Unit,
    onPlayWithBot: () -> Unit,
    onPlayOnline: () -> Unit,
    onlineAvailable: Boolean,
    onBack: () -> Unit,
) {
    val strings = LocalAppStrings.current
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(
                space = maxHeight * 0.025f,
                alignment = Alignment.CenterVertically
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = strings.text("home.pvp.screen.title"),
                style = MaterialTheme.typography.headlineSmall
            )

            Text(
                text = strings.text("home.pvp.screen.description"),
                style = MaterialTheme.typography.bodyLarge
            )

            Text(
                text = strings.text("social.online.secret_length"),
                style = MaterialTheme.typography.labelLarge,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    enabled = codeLength > MinimumHomeCodeLength,
                    onClick = { onCodeLengthChange(selectHomeCodeLength(codeLength - 1)) },
                ) {
                    Text("−")
                }
                Text(
                    text = strings.homeCodeLength(codeLength),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(
                    enabled = codeLength < MaximumHomeCodeLength,
                    onClick = { onCodeLengthChange(selectHomeCodeLength(codeLength + 1)) },
                ) {
                    Text("+")
                }
            }

            FilledTonalButton(
                onClick = onPlayWithBot,
                modifier = Modifier.fillMaxWidth(fraction = 0.68f)
            ) {
                Text(strings.text("home.pvp.bot"))
            }

            FilledTonalButton(
                onClick = onPlayOnline,
                enabled = onlineAvailable,
                modifier = Modifier.fillMaxWidth(fraction = 0.68f)
            ) {
                Text(
                    text = strings.text("home.pvp.online"),
                    maxLines = 1,
                    softWrap = false,
                )
            }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(fraction = 0.42f)
            ) {
                Text(strings.text("top.back"))
            }
        }
    }
}

internal fun selectHomeCodeLength(value: Int): Int =
    value.coerceIn(MinimumHomeCodeLength, MaximumHomeCodeLength)

internal const val MinimumHomeCodeLength = 4
internal const val MaximumHomeCodeLength = 10
