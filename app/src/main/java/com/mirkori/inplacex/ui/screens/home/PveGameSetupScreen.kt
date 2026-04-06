package com.mirkori.inplacex.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.ui.screens.game.GameFieldParams
import com.mirkori.inplacex.ui.screens.game.TypeGame

private enum class OnlineLikeMode(
    val title: String,
    val description: String
) {
    RATED("Рейтинговый", "Стандартные правила, словно в онлайне"),
    QUICK("Быстрый матч", "Короткая сессия с ограничением по времени"),
    CUSTOM("Кастом", "Ручная настройка всех параметров")
}

@Composable
fun PveGameSetupScreen(
    onBack: () -> Unit,
    onStartGame: (GameFieldParams, String) -> Unit
) {
    var selectedMode by rememberSaveable { mutableStateOf(OnlineLikeMode.RATED) }
    var useHints by rememberSaveable { mutableStateOf(true) }
    var totalTimeText by rememberSaveable { mutableStateOf("0") }
    var moveTimeText by rememberSaveable { mutableStateOf("0") }
    var moveLimitText by rememberSaveable { mutableStateOf("0") }

    fun parseNumber(value: String): Int = value.toIntOrNull()?.coerceAtLeast(0) ?: 0

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Создание PvE матча",
                style = MaterialTheme.typography.headlineSmall
            )

            Text(
                text = "Экран как при создании онлайн-игры, но запускаем соло матч.",
                style = MaterialTheme.typography.bodyMedium
            )

            Surface(
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Режим", style = MaterialTheme.typography.titleMedium)

                    OnlineLikeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedMode = mode }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedMode == mode,
                                onClick = { selectedMode = mode }
                            )
                            Column {
                                Text(mode.title, style = MaterialTheme.typography.bodyLarge)
                                Text(mode.description, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Параметры матча", style = MaterialTheme.typography.titleMedium)

                    OutlinedTextField(
                        value = totalTimeText,
                        onValueChange = { totalTimeText = it.filter(Char::isDigit) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Время на всю игру (сек), 0 — без лимита") },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = moveTimeText,
                        onValueChange = { moveTimeText = it.filter(Char::isDigit) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Время на ход (сек), 0 — без лимита") },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = moveLimitText,
                        onValueChange = { moveLimitText = it.filter(Char::isDigit) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Лимит ходов, 0 — без лимита") },
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Подсказки")
                        Switch(
                            checked = useHints,
                            onCheckedChange = { useHints = it }
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val params = GameFieldParams(
                        typeGame = TypeGame.RaceMatch,
                        useHints = useHints,
                        timeAll = parseNumber(totalTimeText),
                        timeMove = parseNumber(moveTimeText),
                        limitMoves = parseNumber(moveLimitText),
                        lenSecret = 6
                    )
                    onStartGame(params, selectedMode.title)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Создать PvE матч")
            }

            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Назад")
            }
        }
    }
}
