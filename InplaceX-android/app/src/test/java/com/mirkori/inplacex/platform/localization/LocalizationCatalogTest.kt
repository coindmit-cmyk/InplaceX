package com.mirkori.inplacex.platform.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalizationCatalogTest {
    @Test
    fun featureCatalogsRejectDuplicateKeys() {
        val duplicate = localizationEntry("duplicate.key", "first")

        assertThrows(IllegalArgumentException::class.java) {
            localizationCatalog("duplicate", listOf(duplicate, duplicate))
        }
    }

    @Test
    fun featureCatalogsDoNotOverlap() {
        AppLanguage.values().forEach { language ->
            val catalogs = StaticLocalizationProvider.featureCatalogs(language)
            val entryCount = catalogs.sumOf { it.entries.size }
            val uniqueKeyCount = catalogs.flatMap { it.keys }.toSet().size

            assertEquals(
                "duplicate keys in ${language.name} feature catalogs",
                entryCount,
                uniqueKeyCount,
            )
        }
    }

    @Test
    fun russianAndEnglishCatalogsHaveTheSameKeys() {
        val russian = StaticLocalizationProvider.catalogFor(AppLanguage.RU)
        val english = StaticLocalizationProvider.catalogFor(AppLanguage.EN)

        assertEquals(russian.keys.sorted(), english.keys.sorted())
    }

    @Test
    fun unknownKeysUseExplicitKeyFallback() {
        val missingKey = "missing.localization.key"

        assertEquals(missingKey, StaticLocalizationProvider.forLanguage(AppLanguage.RU).text(missingKey))
        assertEquals(missingKey, StaticLocalizationProvider.forLanguage(AppLanguage.EN).text(missingKey))
    }

    @Test
    fun existingVisibleStringsRemainUnchanged() {
        assertEquals("Назад", StaticLocalizationProvider.forLanguage(AppLanguage.RU).text("top.back"))
        assertEquals("Back", StaticLocalizationProvider.forLanguage(AppLanguage.EN).text("top.back"))
        assertEquals("Компания", StaticLocalizationProvider.forLanguage(AppLanguage.RU).text("company.title"))
        assertEquals("Company", StaticLocalizationProvider.forLanguage(AppLanguage.EN).text("company.title"))
        assertEquals(
            "Секрет: {value}",
            StaticLocalizationProvider.forLanguage(AppLanguage.RU).text("game.debug.secret"),
        )
        assertEquals(
            "Secret: {value}",
            StaticLocalizationProvider.forLanguage(AppLanguage.EN).text("game.debug.secret"),
        )
    }
}
