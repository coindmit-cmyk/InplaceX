package com.mirkori.inplacex.platform.localization

import android.content.Context

/** Persists only the app's selected display language in a private preference file. */
class AppLanguageSettingsStore(
    context: Context,
    preferencesName: String = PreferencesName,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    /** Returns RU for a missing, malformed, or unsupported stored value. */
    fun read(): AppLanguage {
        val storedName = preferences.all[LanguageKey] as? String ?: return AppLanguage.RU
        return runCatching { AppLanguage.valueOf(storedName) }.getOrDefault(AppLanguage.RU)
    }

    /** Commits synchronously so a force-stop immediately after selection retains the choice. */
    fun write(language: AppLanguage): Boolean =
        preferences.edit().putString(LanguageKey, language.name).commit()

    private companion object {
        const val PreferencesName = "inplacex_language_settings"
        const val LanguageKey = "app_language"
    }
}
