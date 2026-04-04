package com.mirkori.inplacex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mirkori.inplacex.core.engine.GameEngine
import com.mirkori.inplacex.core.model.GameConfig
import com.mirkori.inplacex.core.model.GameStatus
import com.mirkori.inplacex.ui.theme.InplaceXTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            InplaceXTheme {
                val config = remember {
                    GameConfig(
                        codeLength = 6,
                        allowDuplicates = true,
                        attemptLimit = 12
                    )
                }

                var gameState by remember {
                    mutableStateOf(GameEngine.createNewGame(config))
                }

                var currentGuess by remember {
                    mutableStateOf("")
                }

                Scaffold { innerPadding ->
                    GameContent(
                        paddingValues = innerPadding,
                        currentGuess = currentGuess,
                        onGuessChange = { value ->
                            currentGuess = value.filter { it.isDigit() }.take(config.codeLength)
                        },
                        attemptLimit = config.attemptLimit,
                        attemptsUsed = gameState.attempts.size,
                        status = gameState.status,
                        historyLines = gameState.attempts.map {
                            "${it.guess} -> ${it.exactMatches}"
                        },
                        onSubmitGuess = {
                            gameState = GameEngine.makeGuess(gameState, currentGuess)
                            currentGuess = ""
                        },
                        onRestart = {
                            gameState = GameEngine.createNewGame(config)
                            currentGuess = ""
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GameContent(
    paddingValues: PaddingValues,
    currentGuess: String,
    onGuessChange: (String) -> Unit,
    attemptLimit: Int,
    attemptsUsed: Int,
    status: GameStatus,
    historyLines: List<String>,
    onSubmitGuess: () -> Unit,
    onRestart: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "InplaceX")
        Text(text = "Попытки: $attemptsUsed / $attemptLimit")
        Text(text = "Статус: ${status.name}")

        OutlinedTextField(
            value = currentGuess,
            onValueChange = onGuessChange,
            label = { Text("Введите число") },
            singleLine = true
        )

        Button(onClick = onSubmitGuess) {
            Text("Проверить")
        }

        Text("История ходов:")

        if (historyLines.isEmpty()) {
            Text("Ходов пока нет")
        } else {
            historyLines.forEach { line ->
                Text(line)
            }
        }

        TextButton(onClick = onRestart) {
            Text("Новая игра")
        }
    }
}