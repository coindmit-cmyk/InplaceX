package com.mirkori.inplacex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mirkori.inplacex.core.engine.GameEngine
import com.mirkori.inplacex.core.model.AnalysisBoardState
import com.mirkori.inplacex.core.model.AnalysisCellState
import com.mirkori.inplacex.core.model.GameConfig
import com.mirkori.inplacex.core.model.GameStatus
import com.mirkori.inplacex.core.model.GameTab
import com.mirkori.inplacex.ui.GameScreen
import com.mirkori.inplacex.ui.theme.InplaceXTheme
import kotlinx.coroutines.delay

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

                var startTime by remember {
                    mutableLongStateOf(System.currentTimeMillis())
                }

                var elapsedSeconds by remember {
                    mutableLongStateOf(0L)
                }

                LaunchedEffect(startTime) {
                    while (true) {
                        elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
                        delay(1000)
                    }
                }

                val elapsedTime = "%02d:%02d".format(
                    elapsedSeconds / 60,
                    elapsedSeconds % 60
                )

                Scaffold { innerPadding ->
                    GameScreen(
                        paddingValues = innerPadding,
                        currentTab = currentTab,
                        onTabChange = { currentTab = it },
                        elapsedTime = elapsedTime,
                        knownDigits = buildKnownDigitsFromAnalysis(analysisBoard),
                        currentGuess = currentGuess,
                        onGuessChange = { value ->
                            currentGuess = value
                                .filter { it.isDigit() }
                                .take(config.codeLength)
                        },
                        attemptLimit = config.attemptLimit,
                        attemptsUsed = gameState.attempts.size,
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
                            startTime = System.currentTimeMillis()
                        },
                        isSubmitEnabled = currentGuess.length == config.codeLength &&
                            gameState.status == GameStatus.IN_PROGRESS,
                        debugSecret = gameState.secret
                    )
                }
            }
        }
    }
}

private fun buildKnownDigitsFromAnalysis(
    analysisBoard: AnalysisBoardState
): List<Char?> {
    return List(analysisBoard.codeLength) { position ->
        val yesDigits = (0..9).filter { digit ->
            analysisBoard.cells[digit][position] == AnalysisCellState.YES
        }

        if (yesDigits.size == 1) {
            yesDigits.first().digitToChar()
        } else {
            null
        }
    }
}
