package com.mirkori.inplacex.platform.localization

private class MapLocalizationProvider(
    private val values: Map<String, String>,
) : LocalizationProvider {
    override fun text(key: String): String = values[key] ?: unknownKeyFallback(key)
}

object StaticLocalizationProvider {
    fun forLanguage(language: AppLanguage): LocalizationProvider {
        return MapLocalizationProvider(catalogFor(language).values)
    }

    internal fun catalogFor(language: AppLanguage): LocalizationCatalog {
        val localeCatalogs = when (language) {
            AppLanguage.RU -> listOf(HomeCatalog.ru, SecondaryCatalog.ru, GameCatalog.ru)
            AppLanguage.EN -> listOf(HomeCatalog.en, SecondaryCatalog.en, GameCatalog.en)
        }
        return aggregateLocalizationCatalogs("${language.name.lowercase()}.all", localeCatalogs)
    }

    internal fun featureCatalogs(language: AppLanguage): List<LocalizationCatalog> {
        return when (language) {
            AppLanguage.RU -> listOf(HomeCatalog.ru, SecondaryCatalog.ru, GameCatalog.ru)
            AppLanguage.EN -> listOf(HomeCatalog.en, SecondaryCatalog.en, GameCatalog.en)
        }
    }
}

internal fun unknownKeyFallback(key: String): String = key
