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
    }

    @Test
    fun secondaryScreenKeysResolveForBothLanguages() {
        val keys = listOf(
            "social.description",
            "social.online.error.offline",
            "social.online.new_match",
            "social.friend.request.notification.title",
            "social.friend.request.accept",
            "social.friend.request.root_notice",
            "social.friend.request.sent.short",
            "shop.hints.subtitle",
            "profile.mirkori.name.change",
            "profile.mirkori.avatar.change",
            "profile.match_stats.result",
            "company.dialog.exit_title",
        )

        AppLanguage.values().forEach { language ->
            val strings = StaticLocalizationProvider.forLanguage(language)
            keys.forEach { key ->
                assertEquals(false, strings.text(key) == key)
            }
        }

        assertEquals("Звёзды: {current} / {required}", StaticLocalizationProvider.forLanguage(AppLanguage.RU).text("company.scene.stars"))
        assertEquals("Stars: {current} / {required}", StaticLocalizationProvider.forLanguage(AppLanguage.EN).text("company.scene.stars"))
        assertEquals(
            "Играть уровень {value} — 1 энергия",
            StaticLocalizationProvider.forLanguage(AppLanguage.RU).text("company.action.play"),
        )
        assertEquals(
            "Play level {value} — 1 energy",
            StaticLocalizationProvider.forLanguage(AppLanguage.EN).text("company.action.play"),
        )
        assertEquals(
            "На время",
            StaticLocalizationProvider.forLanguage(AppLanguage.RU).text("social.match.timed"),
        )
        assertEquals(
            "Turn by turn",
            StaticLocalizationProvider.forLanguage(AppLanguage.EN).text("social.match.turn_based"),
        )
        assertEquals(
            "Онлайн‑матч",
            StaticLocalizationProvider.forLanguage(AppLanguage.RU).text("social.match.title"),
        )
        assertEquals(
            "Онлайн‑матчи",
            StaticLocalizationProvider.forLanguage(AppLanguage.RU).text("social.online.title"),
        )
    }
}
