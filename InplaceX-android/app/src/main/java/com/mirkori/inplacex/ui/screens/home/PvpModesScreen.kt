package com.mirkori.inplacex.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.platform.localization.LocalAppStrings
import com.mirkori.inplacex.ui.common.WarmPrimaryButton
import com.mirkori.inplacex.ui.common.WarmSecondaryButton
import com.mirkori.inplacex.ui.screens.shared.SceneCard
import com.mirkori.inplacex.ui.theme.InplaceXColors

@Composable
fun PvpModesScreen(
    codeLength: Int,
    onCodeLengthChange: (Int) -> Unit,
    onPlayWithBot: () -> Unit,
    onPlayOnline: () -> Unit,
    onlineAvailable: Boolean,
    onBack: () -> Unit,
    modeAccentColor: Color = InplaceXColors.ToyPurple,
) {
    val strings = LocalAppStrings.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SceneCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = strings.text("home.pvp.screen.title"),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = strings.text("home.pvp.screen.description"),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = modeAccentColor.copy(alpha = 0.12f),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = strings.text("social.online.secret_length"),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            WarmSecondaryButton(
                                label = "−",
                                enabled = codeLength > MinimumHomeCodeLength,
                                onClick = { onCodeLengthChange(selectHomeCodeLength(codeLength - 1)) },
                                modifier = Modifier.width(44.dp).height(40.dp),
                            )
                            Text(
                                text = strings.homeCodeLength(codeLength),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            WarmSecondaryButton(
                                label = "+",
                                enabled = codeLength < MaximumHomeCodeLength,
                                onClick = { onCodeLengthChange(selectHomeCodeLength(codeLength + 1)) },
                                modifier = Modifier.width(44.dp).height(40.dp),
                            )
                        }
                    }
                }

                WarmPrimaryButton(
                    label = strings.text("home.pvp.bot"),
                    onClick = onPlayWithBot,
                    enabled = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                )

                WarmSecondaryButton(
                    label = strings.text("home.pvp.online"),
                    onClick = onPlayOnline,
                    enabled = onlineAvailable,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                )

                WarmSecondaryButton(
                    label = strings.text("top.back"),
                    onClick = onBack,
                    enabled = true,
                    modifier = Modifier
                        .fillMaxWidth(fraction = 0.50f)
                        .height(44.dp),
                )
            }
        }
    }
}

internal fun selectHomeCodeLength(value: Int): Int =
    value.coerceIn(MinimumHomeCodeLength, MaximumHomeCodeLength)

internal const val MinimumHomeCodeLength = 4
internal const val MaximumHomeCodeLength = 10
