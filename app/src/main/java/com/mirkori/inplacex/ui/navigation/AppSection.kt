package com.mirkori.inplacex.ui.navigation

enum class AppSection(
    val title: String,
    val shortLabel: String
) {
    HOME(
        title = "Главная",
        shortLabel = "Главная"
    ),
    SOCIAL(
        title = "Игры с друзьями / Онлайн",
        shortLabel = "Играть"
    ),
    TOURNAMENTS(
        title = "Турниры",
        shortLabel = "Турниры"
    ),
    SHOP(
        title = "Магазин",
        shortLabel = "Магазин"
    ),
    PROFILE(
        title = "Профиль",
        shortLabel = "Профиль"
    )
}
