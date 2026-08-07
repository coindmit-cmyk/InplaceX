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
    fun `Yandex remains configured when optional interstitial is absent`() {
        val config = AdsProviderConfig(
            ownerYandex = AdSdkConfig(
                gameBannerAdUnitId = "banner",
                rewardedAdUnitId = "rewarded",
            ),
        )

        assertEquals(
            listOf(AdProviderId.OWNER_YANDEX),
            config.configuredProviderIds(),
        )
    }

    @Test
    fun `runtime preloads Yandex in Russia`() = runBlocking {
        val owner = FakeProvider(AdProviderId.OWNER_YANDEX, AdPresentationResult.Completed)
        val runtime = AndroidAdRuntime(
            router = AdRouter(listOf(owner)),
            marketResolver = AdMarketResolver { AdMarket.RUSSIA },
            configuredProviders = listOf(owner.id),
        )
        val request = AdRequest(
            placement = AdPlacement.SHOP_COINS_REWARD,
            format = AdFormat.REWARDED,
        )

        val preload = runtime.preload(request)
        val presentation = runtime.show(request)

        assertEquals(
            listOf(AdProviderId.OWNER_YANDEX),
            preload.map { it.providerId },
        )
        assertEquals(AdProviderId.OWNER_YANDEX, presentation.shownBy)
        assertEquals(1, owner.preloadCalls)
    }

    @Test
    fun `global market fails closed until a non-Russian provider is configured`() = runBlocking {
        val owner = FakeProvider(AdProviderId.OWNER_YANDEX, AdPresentationResult.Completed)
        val runtime = AndroidAdRuntime(
            router = AdRouter(listOf(owner)),
            marketResolver = AdMarketResolver { AdMarket.GLOBAL },
            configuredProviders = listOf(owner.id),
        )
        val request = AdRequest(AdPlacement.GAME_BANNER, AdFormat.BANNER)

        assertEquals(emptyList<Any>(), runtime.preload(request))
        assertEquals(AdPresentationResult.ProviderUnavailable, runtime.show(request).result)
        assertEquals(0, owner.preloadCalls)
        assertEquals(0, owner.showCalls)
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

    @Test
    fun `runtime delegates provider privacy options without changing routing`() = runBlocking {
        var calls = 0
        val runtime = AndroidAdRuntime(
            router = AdRouter(emptyList()),
            marketResolver = AdMarketResolver { AdMarket.UNKNOWN },
            configuredProviders = emptyList(),
            privacyOptionsHandlers = listOf(
                AdPrivacyOptionsHandler {
                    calls += 1
                    true
                },
            ),
        )

        assertEquals(true, runtime.showProviderPrivacyOptions())
        assertEquals(1, calls)
    }

    @Test
    fun `runtime forwards consent changes to configured provider resources`() = runBlocking {
        val provider = ConsentAwareProvider()
        val runtime = createAdRuntime(
            config = AdsProviderConfig(
                ownerYandex = AdSdkConfig(gameBannerAdUnitId = "banner"),
            ),
            providers = listOf(provider),
            marketResolver = AdMarketResolver { AdMarket.RUSSIA },
            consentProvider = AcceptedAdConsentProvider,
        )

        runtime.onConsentChanged(AdConsentDecision.DECLINED)

        assertEquals(listOf(AdConsentDecision.DECLINED), provider.decisions)
    }

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

    private class ConsentAwareProvider : AdProvider, AdConsentChangeHandler {
        override val id: AdProviderId = AdProviderId.OWNER_YANDEX
        val decisions = mutableListOf<AdConsentDecision>()

        override suspend fun preload(request: AdRequest): AdPreloadResult =
            AdPreloadResult.READY

        override suspend fun show(request: AdRequest): AdPresentationResult =
            AdPresentationResult.Completed

        override suspend fun onConsentChanged(decision: AdConsentDecision) {
            decisions += decision
        }
    }
}
