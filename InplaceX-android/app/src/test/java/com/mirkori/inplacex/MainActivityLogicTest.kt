package com.mirkori.inplacex

import com.mirkori.inplacex.platform.mirkori.MirkoriProAccessState
import com.mirkori.inplacex.platform.mirkori.MirkoriProAvailability
import com.mirkori.inplacex.platform.mirkori.MirkoriProNotice
import com.mirkori.inplacex.platform.services.MonetizationEntitlements
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

    @Test
    fun mirkoriProGrantsRegularProAndRequiresAResolvedGameplayLease() {
        val menuState = MirkoriProAccessState(
            availability = MirkoriProAvailability.CACHED,
            active = true,
        )
        assertTrue(menuState.grantsProAccess(gameplayActive = false))
        assertFalse(menuState.grantsProAccess(gameplayActive = true))

        val leasedState = menuState.copy(
            availability = MirkoriProAvailability.READY,
            onlineSessionActive = true,
        )
        assertTrue(leasedState.grantsProAccess(gameplayActive = true))

        val limitedState = menuState.copy(
            availability = MirkoriProAvailability.READY,
            notice = MirkoriProNotice.CONCURRENCY_LIMIT,
        )
        assertFalse(limitedState.grantsProAccess(gameplayActive = true))

        val entitlements = MonetizationEntitlements.None.withMirkoriProAccess(active = true)
        assertTrue(entitlements.proSubscriptionActive)
        assertFalse(entitlements.proPlusSubscriptionActive)
    }

}
