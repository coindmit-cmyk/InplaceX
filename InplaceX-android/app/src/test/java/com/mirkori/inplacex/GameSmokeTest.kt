package com.mirkori.inplacex

import com.mirkori.inplacex.core.engine.GameEngine
import com.mirkori.inplacex.core.match.MatchPhase
import com.mirkori.inplacex.core.model.GameConfig
import com.mirkori.inplacex.platform.config.AppConfigCatalog
import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSmokeTest {
    @Test
    fun engineWinsOnExactGuess() {
        val engine = GameEngine(
            GameConfig(
                codeLength = 4,
                allowDuplicates = false,
                attemptLimit = 3,
                seed = 1L,
            )
        )

        val started = engine.start()
        val result = engine.submit(started.debugSecret)

        assertEquals(MatchPhase.WON, result.phase)
        assertEquals(1, result.attempts.size)
        assertTrue(result.attempts.first().isWin)
    }

    @Test
    fun appCatalogContainsGameModesAndSections() {
        assertTrue(AppConfigCatalog.gameModes.isNotEmpty())
        assertTrue(AppConfigCatalog.platformConfig.navigationItems.isNotEmpty())
    }

    @Test
    fun localizationProviderResolvesKnownKeys() {
        val ruStrings = StaticLocalizationProvider.forLanguage(AppLanguage.RU)
        val enStrings = StaticLocalizationProvider.forLanguage(AppLanguage.EN)

        assertEquals("Главная", ruStrings.text("section.home.title"))
        assertEquals("Home", enStrings.text("section.home.title"))
    }
}
