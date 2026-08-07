package com.mirkori.inplacex.platform.ads

import android.content.Context
import com.mirkori.inplacex.ads.AdFormat
import com.mirkori.inplacex.ads.AdPreloadResult
import com.mirkori.inplacex.ads.AdPresentationResult
import com.mirkori.inplacex.ads.AdProvider
import com.mirkori.inplacex.ads.AdProviderId
import com.mirkori.inplacex.ads.AdRequest
import com.mirkori.inplacex.platform.config.AdSdkConfig
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequest as YandexAdRequest
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.YandexAds
import com.yandex.mobile.ads.interstitial.InterstitialAd
import com.yandex.mobile.ads.interstitial.InterstitialAdEventListener
import com.yandex.mobile.ads.interstitial.InterstitialAdLoadResult
import com.yandex.mobile.ads.interstitial.InterstitialAdLoader
import com.yandex.mobile.ads.rewarded.Reward
import com.yandex.mobile.ads.rewarded.RewardedAd
import com.yandex.mobile.ads.rewarded.RewardedAdEventListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoadResult
import com.yandex.mobile.ads.rewarded.RewardedAdLoader
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class YandexAdProvider(
    private val appContext: Context,
    private val config: AdSdkConfig,
    private val consentProvider: AdConsentProvider,
    private val activityHost: AdActivityHost,
) : AdProvider, AdConsentChangeHandler, AutoCloseable {
    override val id: AdProviderId = AdProviderId.OWNER_YANDEX

    private val initializationMutex = Mutex()
    private val rewardedLoadMutex = Mutex()
    private val interstitialLoadMutex = Mutex()
    private var initialized = false
    private var rewardedLoader: RewardedAdLoader? = null
    private var rewardedAd: RewardedAd? = null
    private var interstitialLoader: InterstitialAdLoader? = null
    private var interstitialAd: InterstitialAd? = null

    override suspend fun preload(request: AdRequest): AdPreloadResult {
        if (!ensureInitialized()) return AdPreloadResult.PROVIDER_UNAVAILABLE
        return when (request.format) {
            AdFormat.BANNER -> if (config.gameBannerAdUnitId.isNotBlank()) {
                AdPreloadResult.READY
            } else {
                AdPreloadResult.PROVIDER_UNAVAILABLE
            }
            AdFormat.REWARDED -> preloadRewarded()
            AdFormat.INTERSTITIAL -> preloadInterstitial()
        }
    }

    override suspend fun show(request: AdRequest): AdPresentationResult {
        if (!ensureInitialized()) return AdPresentationResult.ProviderUnavailable
        return when (request.format) {
            AdFormat.BANNER -> AdPresentationResult.ProviderUnavailable
            AdFormat.REWARDED -> showRewarded()
            AdFormat.INTERSTITIAL -> showInterstitial()
        }
    }

    override fun close() {
        clearLoadedAds()
    }

    override suspend fun onConsentChanged(decision: AdConsentDecision) {
        rewardedLoadMutex.withLock {
            interstitialLoadMutex.withLock {
                withContext(Dispatchers.Main.immediate) {
                    clearLoadedAds()
                    YandexAds.setUserConsent(decision == AdConsentDecision.ACCEPTED)
                }
            }
        }
    }

    private fun clearLoadedAds() {
        rewardedAd?.setAdEventListener(null)
        rewardedAd = null
        interstitialAd?.setAdEventListener(null)
        interstitialAd = null
        rewardedLoader?.cancelLoading()
        rewardedLoader = null
        interstitialLoader?.cancelLoading()
        interstitialLoader = null
    }

    private suspend fun ensureInitialized(): Boolean {
        val decision = consentProvider.currentDecision()
        if (decision == AdConsentDecision.UNDECIDED) return false
        return initializationMutex.withLock {
            withTimeoutOrNull(LoadTimeoutMillis) {
                withContext(Dispatchers.Main.immediate) {
                    YandexAds.setUserConsent(decision == AdConsentDecision.ACCEPTED)
                    if (initialized) {
                        true
                    } else {
                        suspendCancellableCoroutine { continuation ->
                            YandexAds.initialize(appContext) {
                                initialized = true
                                if (continuation.isActive) continuation.resume(true)
                            }
                        }
                    }
                }
            } ?: false
        }
    }

    private suspend fun preloadRewarded(): AdPreloadResult = rewardedLoadMutex.withLock {
        withTimeoutOrNull(LoadTimeoutMillis) {
            withContext(Dispatchers.Main.immediate) {
                if (rewardedAd != null) return@withContext AdPreloadResult.ALREADY_READY
                val adUnitId = config.rewardedAdUnitId.takeIf(String::isNotBlank)
                    ?: return@withContext AdPreloadResult.PROVIDER_UNAVAILABLE
                val loader = rewardedLoader ?: RewardedAdLoader(appContext).also {
                    rewardedLoader = it
                }
                when (val result = loader.loadAd(YandexAdRequest.Builder(adUnitId).build())) {
                    is RewardedAdLoadResult.Success -> {
                        rewardedAd = result.ad
                        AdPreloadResult.READY
                    }
                    is RewardedAdLoadResult.Failure -> AdPreloadResult.FAILED
                }
            }
        } ?: AdPreloadResult.FAILED
    }

    private suspend fun preloadInterstitial(): AdPreloadResult = interstitialLoadMutex.withLock {
        withTimeoutOrNull(LoadTimeoutMillis) {
            withContext(Dispatchers.Main.immediate) {
                if (interstitialAd != null) return@withContext AdPreloadResult.ALREADY_READY
                val adUnitId = config.postMatchInterstitialAdUnitId.takeIf(String::isNotBlank)
                    ?: return@withContext AdPreloadResult.PROVIDER_UNAVAILABLE
                val loader = interstitialLoader ?: InterstitialAdLoader(appContext).also {
                    interstitialLoader = it
                }
                when (val result = loader.loadAd(YandexAdRequest.Builder(adUnitId).build())) {
                    is InterstitialAdLoadResult.Success -> {
                        interstitialAd = result.ad
                        AdPreloadResult.READY
                    }
                    is InterstitialAdLoadResult.Failure -> AdPreloadResult.FAILED
                }
            }
        } ?: AdPreloadResult.FAILED
    }

    private suspend fun showRewarded(): AdPresentationResult =
        withTimeoutOrNull(PresentationTimeoutMillis) {
            withContext(Dispatchers.Main.immediate) {
                val activity = activityHost.currentActivity()
                    ?: return@withContext AdPresentationResult.ProviderUnavailable
                val ad = rewardedAd ?: return@withContext AdPresentationResult.NotReady
                rewardedAd = null
                suspendCancellableCoroutine { continuation ->
                    val rewarded = AtomicBoolean(false)
                    ad.setAdEventListener(
                        object : RewardedAdEventListener {
                        override fun onAdShown() = Unit

                        override fun onAdFailedToShow(adError: AdError) {
                            ad.setAdEventListener(null)
                            if (continuation.isActive) {
                                continuation.resume(AdPresentationResult.Failed)
                            }
                        }

                        override fun onAdDismissed() {
                            ad.setAdEventListener(null)
                            if (continuation.isActive) {
                                continuation.resume(
                                    if (rewarded.get()) {
                                        AdPresentationResult.Completed
                                    } else {
                                        AdPresentationResult.Dismissed
                                    },
                                )
                            }
                        }

                        override fun onAdClicked() = Unit

                        override fun onAdImpression(impressionData: ImpressionData?) = Unit

                        override fun onRewarded(reward: Reward) {
                            rewarded.set(true)
                        }
                        },
                    )
                    continuation.invokeOnCancellation {
                        ad.setAdEventListener(null)
                    }
                    ad.show(activity)
                }
            }
        } ?: AdPresentationResult.Failed

    private suspend fun showInterstitial(): AdPresentationResult =
        withTimeoutOrNull(PresentationTimeoutMillis) {
            withContext(Dispatchers.Main.immediate) {
                val activity = activityHost.currentActivity()
                    ?: return@withContext AdPresentationResult.ProviderUnavailable
                val ad = interstitialAd ?: return@withContext AdPresentationResult.NotReady
                interstitialAd = null
                suspendCancellableCoroutine { continuation ->
                    ad.setAdEventListener(
                        object : InterstitialAdEventListener {
                        override fun onAdShown() = Unit

                        override fun onAdFailedToShow(adError: AdError) {
                            ad.setAdEventListener(null)
                            if (continuation.isActive) {
                                continuation.resume(AdPresentationResult.Failed)
                            }
                        }

                        override fun onAdDismissed() {
                            ad.setAdEventListener(null)
                            if (continuation.isActive) {
                                continuation.resume(AdPresentationResult.Dismissed)
                            }
                        }

                        override fun onAdClicked() = Unit

                        override fun onAdImpression(impressionData: ImpressionData?) = Unit
                        },
                    )
                    continuation.invokeOnCancellation {
                        ad.setAdEventListener(null)
                    }
                    ad.show(activity)
                }
            }
        } ?: AdPresentationResult.Failed

    private companion object {
        const val LoadTimeoutMillis = 15_000L
        const val PresentationTimeoutMillis = 120_000L
    }
}
