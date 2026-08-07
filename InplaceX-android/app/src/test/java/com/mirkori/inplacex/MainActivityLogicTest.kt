package com.mirkori.inplacex

import com.mirkori.inplacex.ui.navigation.AppSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityLogicTest {
    @Test
    fun activeOnlineSessionRestoresSocialSection() {
        assertEquals(
            AppSection.SOCIAL,
            initialSectionForActiveOnlineSession("00000000-0000-0000-0000-000000000001"),
        )
    }

    @Test
    fun absentOnlineSessionKeepsHomeSection() {
        assertEquals(AppSection.HOME, initialSectionForActiveOnlineSession(null))
    }

    @Test
    fun checkoutBrowserAcceptsOnlyExternalHttpsUrls() {
        assertTrue(isExternalHttpsCheckoutUrl("https://pay.example/checkout/1"))
        assertFalse(isExternalHttpsCheckoutUrl("http://pay.example/checkout/1"))
        assertFalse(isExternalHttpsCheckoutUrl("https://user@pay.example/checkout/1"))
        assertFalse(isExternalHttpsCheckoutUrl("https://pay.example/checkout/1#paid"))
        assertFalse(isExternalHttpsCheckoutUrl("https://pay.example:70000/checkout/1"))
        assertFalse(
            isExternalHttpsCheckoutUrl("https://games.dmit.life/connect/inplacex/callback?payment=1"),
        )
        assertFalse(isExternalHttpsCheckoutUrl("javascript:alert(1)"))
    }

}
