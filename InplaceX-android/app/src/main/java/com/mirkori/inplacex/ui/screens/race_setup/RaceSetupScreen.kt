package com.mirkori.inplacex.ui.screens.race_setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.model.GameConfig
import com.mirkori.inplacex.ui.common.BottomReserveMode
import com.mirkori.inplacex.ui.common.ScreenBottomReserve

@Composable
fun RaceSetupScreen(
    paddingValues: PaddingValues,
    config: GameConfig,
    onConfigChange: (GameConfig) -> Unit,
    onBack: () -> Unit,
    onStartRace: () -> Unit
) {
    val safeDrawing = WindowInsets.safeDrawing.asPaddingValues()
    val navBar = WindowInsets.navigationBars.asPaddingValues()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(
                top = safeDrawing.calculateTopPadding(),
                bottom = navBar.calculateBottomPadding()
            )
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Настройка гонки", style = MaterialTheme.typography.headlineSmall)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onBack,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Назад")
                        }

                        Button(
                            onClick = onStartRace,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Старт")
                        }
                    }

                    SettingCard(
                        title = "Длина кода",
                        value = config.codeLength.toString()
                    ) {
                        StepperRow(
                            onMinus = {
                                onConfigChange(
                                    config.copy(codeLength = (config.codeLength - 1).coerceAtLeast(4))
                                        .normalizeAttemptLimit()
                                )
                            },
                            onPlus = {
                                onConfigChange(
                                    config.copy(codeLength = (config.codeLength + 1).coerceAtMost(12))
                                        .normalizeAttemptLimit()
                                )
                            }
                        )
                    }

                    SettingCard(
                        title = "Лимит попыток",
                        value = config.attemptLimit.toString()
                    ) {
                        StepperRow(
                            onMinus = {
                                onConfigChange(
                                    config.copy(attemptLimit = (config.attemptLimit - 1).coerceAtLeast(config.codeLength))
                                )
                            },
                            onPlus = {
                                onConfigChange(
                                    config.copy(attemptLimit = (config.attemptLimit + 1).coerceAtMost(config.codeLength * 4))
                                )
                            }
                        )
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Повторы цифр", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = if (config.allowDuplicates) "Разрешены" else "Запрещены",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            Switch(
                                checked = config.allowDuplicates,
                                onCheckedChange = { checked ->
                                    onConfigChange(config.copy(allowDuplicates = checked))
                                }
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = 1.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Что заложено сразу", style = MaterialTheme.typography.titleMedium)
                            Text("• отдельный главный экран")
                            Text("• отдельный экран настройки")
                            Text("• отдельный экран гонки")
                            Text("• нижний резерв под меню / рекламу / premium")
                        }
                    }
                }
            }

            ScreenBottomReserve(
                mode = BottomReserveMode.MENU,
                onMenuHomeClick = onBack,
                onMenuPlayClick = {},
                onMenuProfileClick = {}
            )
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    value: String,
    actions: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            actions()
        }
    }
}

@Composable
private fun StepperRow(
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onMinus) { Text("-") }
        FilledTonalButton(onClick = onPlus) { Text("+") }
    }
}

private fun GameConfig.normalizeAttemptLimit(): GameConfig {
    val normalizedAttemptLimit = attemptLimit
        .coerceAtLeast(codeLength)
        .coerceAtMost(codeLength * 4)

    return copy(attemptLimit = normalizedAttemptLimit)
}
