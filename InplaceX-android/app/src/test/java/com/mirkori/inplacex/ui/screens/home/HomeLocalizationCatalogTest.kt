package com.mirkori.inplacex.ui.screens.home

import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.HomeCatalog
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLocalizationCatalogTest {
    @Test
    fun `home catalogs have matching keys and typed placeholders`() {
        assertEquals(HomeCatalog.ru.keys.sorted(), HomeCatalog.en.keys.sorted())

        HomeCatalog.ru.keys.forEach { key ->
            assertEquals(
                "placeholder mismatch for $key",
                placeholders(HomeCatalog.ru.values.getValue(key)),
                placeholders(HomeCatalog.en.values.getValue(key)),
            )
        }
    }

    @Test
    fun `home keys resolve in both languages`() {
        AppLanguage.values().forEach { language ->
            val strings = StaticLocalizationProvider.forLanguage(language)
            HomeCatalog.ru.keys.forEach { key ->
                assertFalse("$language falls back to key $key", strings.text(key) == key)
            }
        }
    }

    @Test
    fun `home placeholders are formatted through typed helpers`() {
        val ru = StaticLocalizationProvider.forLanguage(AppLanguage.RU)
        val en = StaticLocalizationProvider.forLanguage(AppLanguage.EN)

        assertEquals("6 цифр", ru.homeCodeLength(6))
        assertEquals("6 digits", en.homeCodeLength(6))
        assertEquals(
            "Последний счёт соперника: 4 • Подтверждено: 2/6",
            ru.homeDuelStatus(score = 4, confirmed = 2, codeLength = 6),
        )
        assertEquals(
            "Opponent's last score: 4 • Confirmed: 2/6",
            en.homeDuelStatus(score = 4, confirmed = 2, codeLength = 6),
        )
        assertEquals("Введите 6 цифр", ru.homeEnterDigits(6))
        assertEquals("Enter 6 digits", en.homeEnterDigits(6))
        assertEquals("Попытки: 8 из 12", ru.homeRaceAttempts(used = 8, limit = 12))
        assertEquals("Attempts: 8 of 12", en.homeRaceAttempts(used = 8, limit = 12))
        assertEquals("Время: 02:05", ru.homeRaceTime(elapsedSeconds = 125))
        assertEquals("Time: 02:05", en.homeRaceTime(elapsedSeconds = 125))
        assertEquals("Награда: +10 монет", ru.homeRaceReward(coins = 10))
        assertEquals("Reward: +10 coins", en.homeRaceReward(coins = 10))
    }

    @Test
    fun `race elapsed time is stable and never negative`() {
        assertEquals("00:00", formatRaceElapsed(-1))
        assertEquals("00:59", formatRaceElapsed(59))
        assertEquals("01:00", formatRaceElapsed(60))
        assertEquals("61:01", formatRaceElapsed(3_661))
    }

    @Test
    fun `russian home copy does not retain premium english word`() {
        assertTrue(HomeCatalog.ru.values.values.none { it.contains("premium", ignoreCase = true) })
    }

    @Test
    fun `online match label uses a non breaking hyphen`() {
        assertEquals("Онлайн‑матч", HomeCatalog.ru.values.getValue("home.pvp.online"))
    }

    private fun placeholders(value: String): Set<String> =
        placeholderPattern.findAll(value).map { it.value }.toSet()

    private companion object {
        val placeholderPattern = Regex("""%[ds]""")
    }
}
