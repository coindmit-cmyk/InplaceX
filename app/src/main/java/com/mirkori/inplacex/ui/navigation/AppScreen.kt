package com.mirkori.inplacex.ui.navigation

sealed interface AppScreen {
    data object Home : AppScreen
    data object RaceSetup : AppScreen
    data object RaceGame : AppScreen
}
