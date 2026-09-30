package com.mirkori.inplacex

import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class DebugVariantIsolationTest {
    @Test
    fun `debug variant keeps owner tools and localized controls`() {
        val russian = StaticLocalizationProvider.forLanguage(AppLanguage.RU)
        val english = StaticLocalizationProvider.forLanguage(AppLanguage.EN)

        assertEquals("Секрет: {value}", russian.text("game.debug.secret"))
        assertEquals("Secret: {value}", english.text("game.debug.secret"))
        assertEquals("+10 000 монет", russian.text("developer.action.add_coins"))
        assertEquals("+10,000 coins", english.text("developer.action.add_coins"))
        assertEquals("Лаборатория бота", russian.text("developer.bot_lab.title"))
        assertEquals("Bot Lab", english.text("developer.bot_lab.title"))
        assertFalse(variantToolsBottomSlotEnabled(toolsEnabled = true))

        debugClassResources.forEach { resource ->
            assertNotNull("missing debug-only class $resource", javaClass.classLoader?.getResource(resource))
        }
    }

    private companion object {
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
