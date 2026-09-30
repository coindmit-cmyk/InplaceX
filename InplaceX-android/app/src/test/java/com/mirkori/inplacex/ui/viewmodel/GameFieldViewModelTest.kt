package com.mirkori.inplacex.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.ui.screens.game.MatchSessionSummary
import com.mirkori.inplacex.ui.screens.game.state.GameFieldEvent
import com.mirkori.inplacex.ui.screens.game.state.GameFieldMatchParameters
import org.junit.Assert.assertEquals
import org.junit.Test

class GameFieldViewModelTest {
    @Test
    fun `restores typed ui state through saved state handle`() {
        val handle = SavedStateHandle()
        val parameters = GameFieldMatchParameters(codeLength = 4, attemptLimit = 3)
        val source = GameFieldViewModel(handle, parameters)
        source.dispatch(GameFieldEvent.DigitEntered('4'))
        source.dispatch(GameFieldEvent.DigitEntered('2'))

        val restored = GameFieldViewModel(handle, parameters)

        assertEquals(listOf('4', '2', null, null), restored.uiState.value.input.slots)
    }

    @Test
    fun `terminal race reports loss once and stays terminal until explicit restart`() {
        val viewModel = GameFieldViewModel(
            SavedStateHandle(),
            GameFieldMatchParameters(codeLength = 4, attemptLimit = 1),
            initialSecret = "1234",
        )
        val controller = GameFieldRouteController(viewModel)
        val finished = mutableListOf<MatchSessionSummary>()
        val callbacks = lifecycleCallbacks(finished)

        "1235".forEach { controller.dispatch(GameFieldEvent.DigitEntered(it), callbacks) }
        controller.dispatch(GameFieldEvent.GuessSubmitted, callbacks)
        controller.dispatch(GameFieldEvent.TimerTicked(), callbacks)

        assertEquals(MatchPhase.LOST, viewModel.uiState.value.match.phase)
        assertEquals(1, finished.size)
        assertEquals(false, finished.single().won)

        controller.restart(callbacks)

        assertEquals(MatchPhase.ACTIVE, viewModel.uiState.value.match.phase)
        assertEquals(0, viewModel.uiState.value.match.attempts.size)
    }

    @Test
    fun `terminal race reports win without automatically starting another match`() {
        val viewModel = GameFieldViewModel(
            SavedStateHandle(),
            GameFieldMatchParameters(codeLength = 4, attemptLimit = 3),
            initialSecret = "1234",
        )
        val controller = GameFieldRouteController(viewModel)
        val finished = mutableListOf<MatchSessionSummary>()
        val callbacks = lifecycleCallbacks(finished)

        "1234".forEach { controller.dispatch(GameFieldEvent.DigitEntered(it), callbacks) }
        controller.dispatch(GameFieldEvent.GuessSubmitted, callbacks)

        assertEquals(MatchPhase.WON, viewModel.uiState.value.match.phase)
        assertEquals(1, viewModel.uiState.value.match.attempts.size)
        assertEquals(true, finished.single().won)
    }

    @Test
    fun `restored terminal match is not reported a second time`() {
        val handle = SavedStateHandle()
        val parameters = GameFieldMatchParameters(codeLength = 4, attemptLimit = 1)
        val source = GameFieldViewModel(handle, parameters, initialSecret = "1234")
        val firstController = GameFieldRouteController(source)
        val firstFinished = mutableListOf<MatchSessionSummary>()
        val firstCallbacks = lifecycleCallbacks(firstFinished)
        "1235".forEach { firstController.dispatch(GameFieldEvent.DigitEntered(it), firstCallbacks) }
        firstController.dispatch(GameFieldEvent.GuessSubmitted, firstCallbacks)

        val restored = GameFieldViewModel(handle, parameters, initialSecret = "9999")
        val restoredController = GameFieldRouteController(restored)
        val duplicateFinished = mutableListOf<MatchSessionSummary>()
        restoredController.dispatch(GameFieldEvent.TimerTicked(), lifecycleCallbacks(duplicateFinished))

        assertEquals(MatchPhase.LOST, restored.uiState.value.match.phase)
        assertEquals(1, firstFinished.size)
        assertEquals(0, duplicateFinished.size)
    }

    private fun lifecycleCallbacks(
        finished: MutableList<MatchSessionSummary>,
    ) = GameFieldLifecycleCallbacks(
        onMatchStarted = {},
        onMatchWon = {},
        onMatchFinished = finished::add,
        onGuessResolved = { _, _, _ -> },
        autoRestartOnWin = false,
    )
}
