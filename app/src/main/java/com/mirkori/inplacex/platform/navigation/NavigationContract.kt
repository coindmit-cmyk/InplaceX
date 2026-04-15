package com.mirkori.inplacex.platform.navigation

interface NavigationDestination {
    val route: String
}

data class GameFeatureDestination(
    override val route: String,
    val featureId: String,
) : NavigationDestination
