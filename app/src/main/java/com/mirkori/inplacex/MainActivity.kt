package com.mirkori.inplacex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mirkori.inplacex.core.engine.GameEngine
import com.mirkori.inplacex.core.model.AnalysisBoardState
import com.mirkori.inplacex.core.model.GameConfig
import com.mirkori.inplacex.core.model.GameStatus
import com.mirkori.inplacex.core.model.GameTab
import com.mirkori.inplacex.ui.GameScreen
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

                var currentTab by remember {
                    mutableStateOf(GameTab.HISTORY)
                }

                var analysisBoard by remember {
                    mutableStateOf(AnalysisBoardState.create(config.codeLength))
                }

                val statusText = when (gameState.status) {
                    GameStatus.IN_PROGRESS -> "Игра идёт"
                    GameStatus.WON -> "Победа"
                    GameStatus.LOST -> "Поражение"
                }

                GameScreen(
                    paddingValues = PaddingValues(),
                    currentTab = currentTab,
                    onTabChange = { currentTab = it },
                    knownDigits = List(config.codeLength) { null },
                    currentGuess = currentGuess,
                    onGuessChange = { value ->
                        currentGuess = value
                            .filter { it.isDigit() }
                            .take(config.codeLength)
                    },
                    attemptLimit = config.attemptLimit,
                    attemptsUsed = gameState.attempts.size,
                    statusText = statusText,
                    historyLines = gameState.attempts.map {
                        "${it.guess} -> ${it.exactMatches}"
                    },
                    analysisBoard = analysisBoard,
                    onAnalysisCellClick = { digit, position ->
                        analysisBoard = analysisBoard.toggleCell(digit, position)
                    },
                    onSubmitGuess = {
                        gameState = GameEngine.makeGuess(gameState, currentGuess)
                        currentGuess = ""
                    },
                    onRestart = {
                        gameState = GameEngine.createNewGame(config)
                        currentGuess = ""
                        analysisBoard = AnalysisBoardState.create(config.codeLength)
                        currentTab = GameTab.HISTORY
                    },
                    isSubmitEnabled = currentGuess.length == config.codeLength &&
                            gameState.status == GameStatus.IN_PROGRESS,
                    debugSecret = gameState.secret
                )
            }
        }
    }
}