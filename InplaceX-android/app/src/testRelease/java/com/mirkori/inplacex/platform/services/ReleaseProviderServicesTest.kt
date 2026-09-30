package com.mirkori.inplacex.platform.services

import android.content.ContextWrapper
import com.mirkori.inplacex.BuildConfig
import com.mirkori.inplacex.platform.config.AdsProviderConfig
import com.mirkori.inplacex.platform.config.BillingProviderConfig
import com.mirkori.inplacex.platform.config.GooglePlayProviderConfig
import com.mirkori.inplacex.platform.config.PlatformConfig
import com.mirkori.inplacex.platform.config.ProviderConfig
import com.mirkori.inplacex.platform.config.ProviderEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseProviderServicesTest {
    @Test
    fun `release factory fails closed even when runtime config requests sandbox`() {
        val services = ProviderServicesFactory.create(
            context = ContextWrapper(null),
            platformConfig = PlatformConfig(
                navigationItems = emptyList(),
                providers = ProviderConfig(
                    environment = ProviderEnvironment.SANDBOX,
                    googlePlay = GooglePlayProviderConfig(webClientId = "configured-id"),
                    ads = AdsProviderConfig(admobAppId = "configured-id"),
                    billing = BillingProviderConfig(
                        removeAdsProductId = "remove_ads",
                        proSubscriptionId = "pro",
                        proPlusSubscriptionId = "pro_plus",
                    ),
                ),
            ),
        )

        val beforeSignIn = services.authService.currentSession()

        assertEquals("live", BuildConfig.PROVIDER_ENVIRONMENT)
        assertEquals(beforeSignIn, services.authService.signInWithGooglePlay())
        assertFalse(services.adService.showBanner("game"))
        assertFalse(services.adService.showRewardedAd(RewardedPlacement.SHOP_COINS_REWARD))
        assertFalse(services.adService.showInterstitial(InterstitialPlacement.POST_MATCH))
        assertFalse(services.billingService.purchase(BillingProductId.REMOVE_ADS))
        assertNull(
            javaClass.classLoader?.getResource(
                "com/mirkori/inplacex/platform/services/StubGooglePlayAuthService.class",
            ),
        )
    }
}
