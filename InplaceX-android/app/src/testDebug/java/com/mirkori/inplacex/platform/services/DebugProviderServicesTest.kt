package com.mirkori.inplacex.platform.services

import android.content.ContextWrapper
import com.mirkori.inplacex.ads.AdFormat
import com.mirkori.inplacex.ads.AdPlacement
import com.mirkori.inplacex.ads.AdProviderId
import com.mirkori.inplacex.ads.AdRequest
import com.mirkori.inplacex.BuildConfig
import com.mirkori.inplacex.platform.ads.AdConsentController
import com.mirkori.inplacex.platform.ads.AdConsentDecision
import com.mirkori.inplacex.platform.config.AdSdkConfig
import com.mirkori.inplacex.platform.config.AdsProviderConfig
import com.mirkori.inplacex.platform.config.PlatformConfig
import com.mirkori.inplacex.platform.config.ProviderConfig
import com.mirkori.inplacex.platform.config.ProviderEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugProviderServicesTest {
    @Test
    fun `debug factory keeps unavailable monetization providers fail closed`() {
        val services = ProviderServicesFactory.create(
            context = ContextWrapper(null),
            platformConfig = PlatformConfig(navigationItems = emptyList()),
            adConsentController = TestAdConsentController,
        )

        assertEquals("sandbox", BuildConfig.PROVIDER_ENVIRONMENT)
        assertTrue(services.authService is StubGooglePlayAuthService)
        assertTrue(services.adService is StubAdService)
        assertTrue(services.billingService is StubBillingService)
        assertTrue(services.authService.signInWithGooglePlay().isSignedIn)
        assertFalse(services.adService.showRewardedAd(RewardedPlacement.SHOP_COINS_REWARD))
        assertEquals(BillingNotice.CONFIGURATION_REQUIRED, services.billingService.cachedState().notice)
    }

    @Test
    fun `sandbox debug runtime routes rewarded ads to Yandex independent of device market`() =
        runBlocking {
            val services = ProviderServicesFactory.create(
                context = ContextWrapper(null),
                platformConfig = PlatformConfig(
                    navigationItems = emptyList(),
                    providers = ProviderConfig(
                        environment = ProviderEnvironment.SANDBOX,
                        ads = AdsProviderConfig(
                            ownerYandex = AdSdkConfig(
                                rewardedAdUnitId = "demo-rewarded-yandex",
                            ),
                        ),
                    ),
                ),
                adConsentController = TestAdConsentController,
            )

            try {
                val plan = services.adRuntime.plan(
                    AdRequest(
                        placement = AdPlacement.SHOP_COINS_REWARD,
                        format = AdFormat.REWARDED,
                    ),
                )

                assertEquals(listOf(AdProviderId.OWNER_YANDEX), plan.providers)
                assertEquals("", services.gameBannerAdUnitId)
            } finally {
                services.adRuntime.close()
            }
        }

    @Test
    fun `sandbox debug runtime replaces configured Yandex ids with demo placements`() {
        val configured = AdSdkConfig(
            gameBannerAdUnitId = "R-M-owner-banner",
            rewardedAdUnitId = "R-M-owner-rewarded",
            postMatchInterstitialAdUnitId = "R-M-owner-interstitial",
        )

        assertEquals(
            AdSdkConfig(
                gameBannerAdUnitId = "demo-banner-yandex",
                rewardedAdUnitId = "demo-rewarded-yandex",
                postMatchInterstitialAdUnitId = "demo-interstitial-yandex",
            ),
            selectDebugYandexConfig(ProviderEnvironment.SANDBOX, configured),
        )
        assertEquals(
            configured,
            selectDebugYandexConfig(ProviderEnvironment.LIVE, configured),
        )
    }

    @Test
    fun `sandbox debug factory exposes demo banner id to banner UI`() {
        val services = ProviderServicesFactory.create(
            context = ContextWrapper(null),
            platformConfig = PlatformConfig(
                navigationItems = emptyList(),
                providers = ProviderConfig(
                    environment = ProviderEnvironment.SANDBOX,
                    ads = AdsProviderConfig(
                        ownerYandex = AdSdkConfig(
                            gameBannerAdUnitId = "R-M-owner-banner",
                        ),
                    ),
                ),
            ),
            adConsentController = TestAdConsentController,
        )

        try {
            assertEquals("demo-banner-yandex", services.gameBannerAdUnitId)
        } finally {
            services.adRuntime.close()
        }
    }

    private object TestAdConsentController : AdConsentController {
        override fun currentDecision(): AdConsentDecision = AdConsentDecision.ACCEPTED

        override fun updateDecision(decision: AdConsentDecision) = Unit
    }
}
