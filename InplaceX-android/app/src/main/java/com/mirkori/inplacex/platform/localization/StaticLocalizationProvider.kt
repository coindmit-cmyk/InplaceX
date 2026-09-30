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
        return aggregateLocalizationCatalogs(
            "${language.name.lowercase()}.all",
            featureCatalogs(language),
        )
    }

    internal fun featureCatalogs(language: AppLanguage): List<LocalizationCatalog> {
        val commonCatalogs = when (language) {
            AppLanguage.RU -> listOf(HomeCatalog.ru, SecondaryCatalog.ru, GameCatalog.ru)
            AppLanguage.EN -> listOf(HomeCatalog.en, SecondaryCatalog.en, GameCatalog.en)
        }
        return commonCatalogs + variantLocalizationCatalogs(language)
    }
}

internal fun unknownKeyFallback(key: String): String = key
