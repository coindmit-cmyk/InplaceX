package com.mirkori.inplacex.platform.services

import android.content.ContextWrapper
import com.mirkori.inplacex.BuildConfig
import com.mirkori.inplacex.platform.config.PlatformConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugProviderServicesTest {
    @Test
    fun `debug factory provides sandbox success adapters`() {
        val services = ProviderServicesFactory.create(
            context = ContextWrapper(null),
            platformConfig = PlatformConfig(navigationItems = emptyList()),
        )

        assertEquals("sandbox", BuildConfig.PROVIDER_ENVIRONMENT)
        assertTrue(services.authService is StubGooglePlayAuthService)
        assertTrue(services.adService is StubAdService)
        assertTrue(services.billingService is StubBillingService)
        assertTrue(services.authService.signInWithGooglePlay().isSignedIn)
        assertTrue(services.adService.showRewardedAd(RewardedPlacement.SHOP_COINS_REWARD))
        assertTrue(services.billingService.purchase(BillingProductId.REMOVE_ADS))
    }
}
