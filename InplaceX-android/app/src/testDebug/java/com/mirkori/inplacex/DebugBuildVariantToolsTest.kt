package com.mirkori.inplacex

import com.mirkori.inplacex.platform.services.BillingNotice
import com.mirkori.inplacex.platform.services.MonetizationEntitlements
import com.mirkori.inplacex.platform.services.RewardedPlacement
import com.mirkori.inplacex.platform.services.StubAdService
import com.mirkori.inplacex.platform.services.StubBillingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugBuildVariantToolsTest {
    @Test
    fun `legacy Google profile actions remain debug only`() {
        assertTrue(legacyGoogleProfileActionsEnabled())
    }

    @Test
    fun `initial test coins are granted once instead of restored after spending`() {
        assertEquals(
            9_900,
            debugInitialCoinTopUp(currentCoins = 100, grantApplied = false),
        )
        assertEquals(
            0,
            debugInitialCoinTopUp(currentCoins = 9_500, grantApplied = true),
        )
    }

    @Test
    fun `debug billing cannot unlock premium products for free`() {
        val billing = StubBillingService()

        assertEquals(BillingNotice.CONFIGURATION_REQUIRED, billing.cachedState().notice)
    }

    @Test
    fun `debug rewarded placeholder cannot mint free coins`() {
        assertFalse(
            StubAdService().showRewardedAd(RewardedPlacement.SHOP_COINS_REWARD),
        )
    }

    @Test
    fun `debug paid feature toggles are combined with server entitlements`() {
        val localProPlus = paidEntitlementTestProgress(adFree = true, proPlus = true)

        val effective = localProPlus.withServerPaidEntitlements(MonetizationEntitlements.None)

        assertTrue(effective.adFreePurchased)
        assertTrue(effective.proSubscriptionActive)
        assertTrue(effective.proPlusSubscriptionActive)
    }

    @Test
    fun `debug local paid toggles suppress real ad gates`() {
        listOf(
            paidEntitlementTestProgress(adFree = true),
            paidEntitlementTestProgress(pro = true),
            paidEntitlementTestProgress(proPlus = true),
        ).forEach { progress ->
            val entitlements = progress.effectiveMonetizationEntitlements(
                serverEntitlements = MonetizationEntitlements.None,
                nowMs = 1_000L,
            )

            assertTrue(entitlements.adsDisabled)
        }
    }

    @Test
    fun `debug ad gate keeps temporary Pro suppression`() {
        val temporaryPro = paidEntitlementTestProgress().copy(temporaryProExpiresAtMs = 2_000L)

        val entitlements = temporaryPro.effectiveMonetizationEntitlements(
            serverEntitlements = MonetizationEntitlements.None,
            nowMs = 1_000L,
        )

        assertTrue(entitlements.adsDisabled)
    }
}
