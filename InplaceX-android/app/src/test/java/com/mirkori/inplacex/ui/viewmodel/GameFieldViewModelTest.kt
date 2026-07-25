package com.mirkori.inplacex.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
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
}
