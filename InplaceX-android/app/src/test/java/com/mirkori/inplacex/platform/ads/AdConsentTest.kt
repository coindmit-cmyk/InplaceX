package com.mirkori.inplacex.platform.ads

import com.mirkori.inplacex.ads.AdFormat
import com.mirkori.inplacex.ads.AdMarket
import com.mirkori.inplacex.ads.AdPlacement
import com.mirkori.inplacex.ads.AdPresentationResult
import com.mirkori.inplacex.ads.AdProvider
import com.mirkori.inplacex.ads.AdProviderId
import com.mirkori.inplacex.ads.AdRequest
import com.mirkori.inplacex.ads.AdRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AdConsentTest {
    @Test
    fun `unknown stored values stay undecided`() {
        assertEquals(AdConsentDecision.UNDECIDED, parseStoredAdConsent(null))
        assertEquals(AdConsentDecision.UNDECIDED, parseStoredAdConsent("future_value"))
        assertEquals(AdConsentDecision.ACCEPTED, parseStoredAdConsent("ACCEPTED"))
        assertEquals(AdConsentDecision.DECLINED, parseStoredAdConsent("DECLINED"))
        assertEquals(
            AdConsentDecision.UNDECIDED,
            parseStoredAdConsent("ACCEPTED", storedPolicyVersion = 0),
        )
    }

    @Test
    fun `runtime makes no provider call before a consent decision`() = runBlocking {
        val provider = RecordingProvider()
        var decision = AdConsentDecision.UNDECIDED
        val runtime = AndroidAdRuntime(
            router = AdRouter(listOf(provider)),
            marketResolver = AdMarketResolver { AdMarket.RUSSIA },
            configuredProviders = listOf(provider.id),
            consentProvider = AdConsentProvider { decision },
        )
        val request = AdRequest(
            placement = AdPlacement.SHOP_COINS_REWARD,
            format = AdFormat.REWARDED,
        )

        assertEquals(AdPresentationResult.ProviderUnavailable, runtime.show(request).result)
        decision = AdConsentDecision.DECLINED
        assertEquals(AdPresentationResult.Completed, runtime.show(request).result)
        assertEquals(1, provider.showCalls)
    }

    private class RecordingProvider : AdProvider {
        override val id: AdProviderId = AdProviderId.OWNER_YANDEX
        var showCalls = 0

        override suspend fun preload(request: AdRequest) =
            com.mirkori.inplacex.ads.AdPreloadResult.READY

        override suspend fun show(request: AdRequest): AdPresentationResult {
            showCalls += 1
            return AdPresentationResult.Completed
        }
    }
}
