package com.mirkori.inplacex

import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVariantIsolationTest {
    @Test
    fun `release Mirkori platform identity is HTTPS and bound to the production package`() {
        assertEquals("com.mirkori.inplacex", BuildConfig.APPLICATION_ID)
        assertTrue(BuildConfig.MIRKORI_PLATFORM_BASE_URL.startsWith("https://"))
        assertFalse(BuildConfig.MIRKORI_PLATFORM_ALLOW_CLEARTEXT_LOOPBACK)
        assertFalse(testFriendBotEnabled())
    }

    @Test
    fun `release variant excludes internal tool catalogs and classes`() {
        AppLanguage.values().forEach { language ->
            val catalog = StaticLocalizationProvider.catalogFor(language)
            val forbiddenKeys = catalog.keys.filter(::isInternalToolKey)

            assertFalse("$language contains internal tool keys: $forbiddenKeys", forbiddenKeys.isNotEmpty())
            forbiddenProbeKeys.forEach { key ->
                assertEquals(key, StaticLocalizationProvider.forLanguage(language).text(key))
            }
        }

        debugClassResources.forEach { resource ->
            assertNull(
                "release classpath contains debug-only class $resource",
                javaClass.classLoader?.getResource(resource),
            )
        }
    }

    private fun isInternalToolKey(key: String): Boolean =
        key.startsWith("game.debug.") ||
            key.startsWith("game.debug_screen.") ||
            key.startsWith("developer.") ||
            key.startsWith("settings.developer") ||
            key.startsWith("profile.developer")

    private companion object {
        val forbiddenProbeKeys = listOf(
            "game.debug.secret",
            "game.debug_screen.title",
            "developer.action.add_coins",
            "developer.bot_lab.title",
            "settings.developer",
        )
        val debugClassResources = listOf(
            "com/mirkori/inplacex/ui/screens/developer/DeveloperRootScreenKt.class",
            "com/mirkori/inplacex/ui/screens/devbot/BotLabScreenKt.class",
            "com/mirkori/inplacex/ui/shell/DebugSecretAdSlotKt.class",
            "com/mirkori/inplacex/ui/screens/game/GameFieldDebugScreenKt.class",
            "com/mirkori/inplacex/ui/screens/game/GameDebugAdSlotKt.class",
            "com/mirkori/inplacex/ui/screens/game/presentation/GameDebugAdSlotContentKt.class",
            "com/mirkori/inplacex/platform/services/StubGooglePlayAuthService.class",
            "com/mirkori/inplacex/platform/services/StubAdService.class",
            "com/mirkori/inplacex/platform/services/StubBillingService.class",
        )
    }
}
