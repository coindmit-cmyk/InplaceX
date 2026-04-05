package com.mirkori.inplacex.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.mirkori.inplacex.core.engine.GameConfig
import com.mirkori.inplacex.core.engine.GameEngine
import com.mirkori.inplacex.core.engine.GameSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GameFieldViewModel : ViewModel() {

    private val engine = GameEngine(
        GameConfig(
            codeLength = 6,
            allowDuplicates = true,
            attemptLimit = 12,
        )
    )

    private val _state = MutableStateFlow(engine.start())
    val state: StateFlow<GameSnapshot> = _state.asStateFlow()

    fun submit(guess: String) {
        _state.value = engine.submit(guess)
    }

    fun restart() {
        _state.value = engine.start()
    }
}
