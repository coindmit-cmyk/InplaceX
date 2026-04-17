package com.mirkori.inplacex.platform.localization

interface LocalizationProvider {
    fun text(key: String): String
}

enum class AppLanguage {
    RU,
    EN,
}
