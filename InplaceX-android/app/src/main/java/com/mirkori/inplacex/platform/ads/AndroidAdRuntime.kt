package com.mirkori.inplacex.platform.ads

import com.mirkori.inplacex.ads.AdMarket
import com.mirkori.inplacex.ads.AdPreloadAttempt
import com.mirkori.inplacex.ads.AdPreloadResult
import com.mirkori.inplacex.ads.AdPresentationResult
import com.mirkori.inplacex.ads.AdProvider
import com.mirkori.inplacex.ads.AdProviderId
import com.mirkori.inplacex.ads.AdRequest
import com.mirkori.inplacex.ads.AdRouter
import com.mirkori.inplacex.ads.AdRuntimeContext
import com.mirkori.inplacex.ads.RoutedAdPresentation
import com.mirkori.inplacex.platform.config.AdsProviderConfig
import com.mirkori.inplacex.platform.logging.AppLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

fun interface AdMarketResolver {
    suspend fun resolveMarket(): AdMarket
}

fun interface AdProviderAvailability {
    fun availableProviders(
        configuredProviders: List<AdProviderId>,
    ): List<AdProviderId>
}

object AllConfiguredAdProviderAvailability : AdProviderAvailability {
    override fun availableProviders(
        configuredProviders: List<AdProviderId>,
    ): List<AdProviderId> = configuredProviders
}

object UnknownAdMarketResolver : AdMarketResolver {
    override suspend fun resolveMarket(): AdMarket = AdMarket.UNKNOWN
}

class AndroidAdRuntime(
    private val router: AdRouter,
    private val marketResolver: AdMarketResolver,
    private val configuredProviders: List<AdProviderId>,
    private val providerAvailability: AdProviderAvailability =
        AllConfiguredAdProviderAvailability,
) {
    suspend fun preload(request: AdRequest): List<AdPreloadAttempt> = coroutineScope {
        val context = runtimeContext()
        router.plan(context, request).providers
            .map { providerId ->
                async {
                    router.preload(providerId, request)
                }
            }
            .awaitAll()
    }

    suspend fun show(request: AdRequest): RoutedAdPresentation {
        val context = runtimeContext()
        return router.show(context, request).also { presentation ->
            AppLog.info(
                tag = "AdRuntime",
                message = "Ad route completed",
                attributes = mapOf(
                    "market" to context.market.name,
                    "placement" to request.placement.wireName,
                    "result" to presentation.result.javaClass.simpleName,
                    "provider" to (presentation.shownBy?.name ?: "none"),
                    "beneficiary" to (presentation.beneficiary?.name ?: "none"),
                    "attempt_count" to presentation.attempts.size.toString(),
                ),
            )
        }
    }

    private suspend fun runtimeContext(): AdRuntimeContext {
        val available = providerAvailability
            .availableProviders(configuredProviders)
            .filter { it in configuredProviders }
            .distinct()
        return AdRuntimeContext(
            market = marketResolver.resolveMarket(),
            availableProviders = available,
        )
    }
}

class FailClosedAdProvider(
    override val id: AdProviderId,
) : AdProvider {
    override suspend fun preload(request: AdRequest): AdPreloadResult =
        AdPreloadResult.PROVIDER_UNAVAILABLE

    override suspend fun show(request: AdRequest): AdPresentationResult =
        AdPresentationResult.ProviderUnavailable
}

fun createFailClosedAdRuntime(
    config: AdsProviderConfig,
    marketResolver: AdMarketResolver = UnknownAdMarketResolver,
): AndroidAdRuntime {
    val providers = AdProviderId.values().map(::FailClosedAdProvider)
    return AndroidAdRuntime(
        router = AdRouter(providers),
        marketResolver = marketResolver,
        configuredProviders = config.configuredProviderIds(),
    )
}
