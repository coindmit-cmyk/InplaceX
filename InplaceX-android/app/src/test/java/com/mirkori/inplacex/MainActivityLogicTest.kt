package com.mirkori.inplacex

import com.mirkori.inplacex.ui.navigation.AppSection
import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.data.local.ModeStats
import com.mirkori.inplacex.platform.services.MonetizationEntitlements
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
    fun paidFeaturesIgnoreLegacyLocalFlagsUntilPlatformConfirmsThem() {
        val legacyPremium = progress().copy(
            adFreePurchased = true,
            proSubscriptionActive = true,
            proPlusSubscriptionActive = true,
        )

        val failClosed = legacyPremium.withServerPaidEntitlements(MonetizationEntitlements.None)
        val confirmed = legacyPremium.withServerPaidEntitlements(
            MonetizationEntitlements(
                adFreePurchased = true,
                proSubscriptionActive = false,
                proPlusSubscriptionActive = false,
            ),
        )

        assertFalse(failClosed.adFreePurchased)
        assertFalse(failClosed.proSubscriptionActive)
        assertFalse(failClosed.proPlusSubscriptionActive)
        assertTrue(confirmed.adFreePurchased)
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

    private fun progress(): GameProgressState = GameProgressState(
        playerDisplayName = "Player",
        googlePlaySignedIn = false,
        openPositionHints = 0,
        checkDigitHints = 0,
        checkPositionHints = 0,
        extraMovesBoosts = 0,
        extraTimeBoosts = 0,
        coins = 0,
        campaignEnergy = 1,
        campaignEnergyMax = 5,
        campaignEnergyRefillMinutes = 30,
        matchesPlayed = 0,
        matchesWon = 0,
        highestUnlockedCampaignLevel = 1,
        totalCampaignRating = 0,
        pveStats = ModeStats(0, 0),
        pvpStats = ModeStats(0, 0),
        companyStats = ModeStats(0, 0),
        adFreePurchased = false,
        proSubscriptionActive = false,
        proPlusSubscriptionActive = false,
        temporaryProExpiresAtMs = 0L,
    )
}
