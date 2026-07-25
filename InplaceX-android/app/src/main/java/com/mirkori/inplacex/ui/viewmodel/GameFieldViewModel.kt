package com.mirkori.inplacex.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.mirkori.inplacex.core.match.MatchSnapshot
import com.mirkori.inplacex.ui.screens.game.state.GameFieldEvent
import com.mirkori.inplacex.ui.screens.game.state.GameFieldMatchParameters
import com.mirkori.inplacex.ui.screens.game.state.GameFieldStateHolder
import com.mirkori.inplacex.ui.screens.game.state.GameFieldUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GameFieldViewModel(
    savedStateHandle: SavedStateHandle,
    parameters: GameFieldMatchParameters = GameFieldMatchParameters(),
) : ViewModel() {

    private val stateHolder = GameFieldStateHolder(savedStateHandle, parameters)

    private val _state = MutableStateFlow(stateHolder.currentSnapshot())
    val state: StateFlow<MatchSnapshot> = _state.asStateFlow()
    val uiState: StateFlow<GameFieldUiState> = stateHolder.state

    fun submit(guess: String) {
        stateHolder.submitRawGuess(guess)
        _state.value = stateHolder.currentSnapshot()
    }

    fun restart() {
        stateHolder.dispatch(GameFieldEvent.MatchRestarted)
        _state.value = stateHolder.currentSnapshot()
    }

    fun dispatch(event: GameFieldEvent) {
        stateHolder.dispatch(event)
        _state.value = stateHolder.currentSnapshot()
    }

    constructor() : this(SavedStateHandle())
}
