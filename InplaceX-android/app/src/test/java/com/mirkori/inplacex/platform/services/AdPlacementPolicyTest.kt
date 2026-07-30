package com.mirkori.inplacex.platform.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdPlacementPolicyTest {
    @Test
    fun `game banner is reserved only for non premium gameplay after provider acceptance`() {
        val free = entitlements()

        assertFalse(
            AdPlacementPolicy.shouldReserveGameBanner(
                isInGame = false,
                entitlements = free,
                providerAccepted = true,
            ),
        )
        assertFalse(
            AdPlacementPolicy.shouldReserveGameBanner(
                isInGame = true,
                entitlements = free,
                providerAccepted = false,
            ),
        )
        assertTrue(
            AdPlacementPolicy.shouldReserveGameBanner(
                isInGame = true,
                entitlements = free,
                providerAccepted = true,
            ),
        )
    }

    @Test
    fun `every paid entitlement suppresses the game banner`() {
        val paid = listOf(
            entitlements(adFree = true),
            entitlements(pro = true),
            entitlements(proPlus = true),
        )

        paid.forEach { entitlements ->
            assertFalse(AdPlacementPolicy.canRequestGameBanner(true, entitlements))
            assertFalse(
                AdPlacementPolicy.shouldReserveGameBanner(
                    isInGame = true,
                    entitlements = entitlements,
                    providerAccepted = true,
                ),
            )
        }
    }

    @Test
    fun `post match interstitial uses the shared onboarding and cadence policy`() {
        val free = entitlements()

        assertFalse(AdPlacementPolicy.shouldShowPostMatchInterstitial(19, free))
        assertTrue(AdPlacementPolicy.shouldShowPostMatchInterstitial(20, free))
        assertFalse(AdPlacementPolicy.shouldShowPostMatchInterstitial(21, free))
        assertFalse(
            AdPlacementPolicy.shouldShowPostMatchInterstitial(
                matchesPlayed = 24,
                entitlements = entitlements(proPlus = true),
            ),
        )
    }

    private fun entitlements(
        adFree: Boolean = false,
        pro: Boolean = false,
        proPlus: Boolean = false,
    ): MonetizationEntitlements = MonetizationEntitlements(
        adFreePurchased = adFree,
        proSubscriptionActive = pro,
        proPlusSubscriptionActive = proPlus,
    )
}
