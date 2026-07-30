package com.mirkori.inplacex.platform.ads

import com.mirkori.inplacex.ads.AdFormat
import com.mirkori.inplacex.ads.AdMarket
import com.mirkori.inplacex.ads.AdPreloadResult
import com.mirkori.inplacex.ads.AdPresentationResult
import com.mirkori.inplacex.ads.AdProvider
import com.mirkori.inplacex.ads.AdProviderId
import com.mirkori.inplacex.ads.AdRequest
import com.mirkori.inplacex.ads.AdRouter
import com.mirkori.inplacex.ads.AdPlacement
import com.mirkori.inplacex.platform.config.AdSdkConfig
import com.mirkori.inplacex.platform.config.AdsProviderConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAdRuntimeTest {
    @Test
    fun `configured providers keep fixed beneficiary identities`() {
        val config = AdsProviderConfig(
            ownerYandex = configuredSdk(),
            companyAdMob = configuredSdk(applicationId = "admob-app"),
            companyHuawei = configuredSdk(applicationId = "huawei-app"),
        )

        assertEquals(
            listOf(
                AdProviderId.OWNER_YANDEX,
                AdProviderId.COMPANY_ADMOB,
                AdProviderId.COMPANY_HUAWEI,
            ),
            config.configuredProviderIds(),
        )
    }

    @Test
    fun `runtime preloads eligible providers and preserves owner display priority`() = runBlocking {
        val owner = FakeProvider(AdProviderId.OWNER_YANDEX, AdPresentationResult.NotReady)
        val company = FakeProvider(AdProviderId.COMPANY_ADMOB, AdPresentationResult.Completed)
        val runtime = AndroidAdRuntime(
            router = AdRouter(listOf(company, owner)),
            marketResolver = AdMarketResolver { AdMarket.GLOBAL },
            configuredProviders = listOf(company.id, owner.id),
        )
        val request = AdRequest(
            placement = AdPlacement.SHOP_COINS_REWARD,
            format = AdFormat.REWARDED,
        )

        val preload = runtime.preload(request)
        val presentation = runtime.show(request)

        assertEquals(
            listOf(AdProviderId.OWNER_YANDEX, AdProviderId.COMPANY_ADMOB),
            preload.map { it.providerId },
        )
        assertEquals(AdProviderId.COMPANY_ADMOB, presentation.shownBy)
        assertEquals(1, owner.preloadCalls)
        assertEquals(1, company.preloadCalls)
    }

    @Test
    fun `unknown market fails closed without provider calls`() = runBlocking {
        val owner = FakeProvider(AdProviderId.OWNER_YANDEX, AdPresentationResult.Completed)
        val runtime = AndroidAdRuntime(
            router = AdRouter(listOf(owner)),
            marketResolver = AdMarketResolver { AdMarket.UNKNOWN },
            configuredProviders = listOf(owner.id),
        )
        val request = AdRequest(
            placement = AdPlacement.GAME_BANNER,
            format = AdFormat.BANNER,
        )

        assertEquals(emptyList<Any>(), runtime.preload(request))
        assertEquals(AdPresentationResult.ProviderUnavailable, runtime.show(request).result)
        assertEquals(0, owner.preloadCalls)
        assertEquals(0, owner.showCalls)
    }

    private fun configuredSdk(applicationId: String = ""): AdSdkConfig =
        AdSdkConfig(
            applicationId = applicationId,
            gameBannerAdUnitId = "banner",
            rewardedAdUnitId = "rewarded",
            postMatchInterstitialAdUnitId = "interstitial",
        )

    private class FakeProvider(
        override val id: AdProviderId,
        private val presentation: AdPresentationResult,
    ) : AdProvider {
        var preloadCalls = 0
            private set
        var showCalls = 0
            private set

        override suspend fun preload(request: AdRequest): AdPreloadResult {
            preloadCalls += 1
            return AdPreloadResult.READY
        }

        override suspend fun show(request: AdRequest): AdPresentationResult {
            showCalls += 1
            return presentation
        }
    }
}
