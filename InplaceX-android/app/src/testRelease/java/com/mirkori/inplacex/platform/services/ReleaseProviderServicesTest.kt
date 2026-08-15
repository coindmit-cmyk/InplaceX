package com.mirkori.inplacex.platform.services

import android.content.ContextWrapper
import com.mirkori.inplacex.BuildConfig
import com.mirkori.inplacex.platform.ads.AdConsentController
import com.mirkori.inplacex.platform.ads.AdConsentDecision
import com.mirkori.inplacex.platform.config.AdSdkConfig
import com.mirkori.inplacex.platform.config.AdsProviderConfig
import com.mirkori.inplacex.platform.config.BillingProviderConfig
import com.mirkori.inplacex.platform.config.GooglePlayProviderConfig
import com.mirkori.inplacex.platform.config.PlatformConfig
import com.mirkori.inplacex.platform.config.ProviderConfig
import com.mirkori.inplacex.platform.config.ProviderEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
                    ads = AdsProviderConfig(
                        ownerYandex = AdSdkConfig(
                            gameBannerAdUnitId = "banner-id",
                            rewardedAdUnitId = "rewarded-id",
                            postMatchInterstitialAdUnitId = "interstitial-id",
                        ),
                    ),
                    billing = BillingProviderConfig(
                        removeAdsProductId = "remove_ads",
                        proSubscriptionId = "pro",
                        proPlusSubscriptionId = "pro_plus",
                    ),
                ),
            ),
            adConsentController = TestAdConsentController,
        )

        val beforeSignIn = services.authService.currentSession()

        assertEquals("live", BuildConfig.PROVIDER_ENVIRONMENT)
        assertEquals(beforeSignIn, services.authService.signInWithGooglePlay())
        assertFalse(services.adService.showBanner("game"))
        assertFalse(services.adService.showRewardedAd(RewardedPlacement.SHOP_COINS_REWARD))
        assertFalse(services.adService.showInterstitial(InterstitialPlacement.POST_MATCH))
        assertTrue(services.adsConfigured)
        assertTrue(services.postMatchInterstitialConfigured)
        assertTrue(services.billingService is UnavailableBillingService)
        assertEquals(BillingNotice.PROVIDER_UNAVAILABLE, services.billingService.cachedState().notice)
        assertNull(
            javaClass.classLoader?.getResource(
                "com/mirkori/inplacex/platform/services/StubGooglePlayAuthService.class",
            ),
        )
    }

    private object TestAdConsentController : AdConsentController {
        override fun currentDecision(): AdConsentDecision = AdConsentDecision.ACCEPTED

        override fun updateDecision(decision: AdConsentDecision) = Unit
    }
}
