package com.mirkori.inplacex

import com.mirkori.inplacex.platform.services.BillingNotice
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
}
