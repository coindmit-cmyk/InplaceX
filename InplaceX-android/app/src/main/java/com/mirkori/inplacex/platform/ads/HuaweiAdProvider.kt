package com.mirkori.inplacex.platform.ads

import com.huawei.hms.ads.AdListener
import com.huawei.hms.ads.AdParam
import com.huawei.hms.ads.HwAds
import com.huawei.hms.ads.InterstitialAd
import com.huawei.hms.ads.RequestOptions
import com.huawei.hms.ads.reward.Reward
import com.huawei.hms.ads.reward.RewardAd
import com.huawei.hms.ads.reward.RewardAdLoadListener
import com.huawei.hms.ads.reward.RewardAdStatusListener
import com.mirkori.inplacex.ads.AdFormat
import com.mirkori.inplacex.ads.AdPreloadResult
import com.mirkori.inplacex.ads.AdPresentationResult
import com.mirkori.inplacex.ads.AdProvider
import com.mirkori.inplacex.ads.AdProviderId
import com.mirkori.inplacex.ads.AdRequest as RuntimeAdRequest
import com.mirkori.inplacex.platform.config.AdSdkConfig
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HuaweiAdProvider(
    private val appContext: android.content.Context,
    private val config: AdSdkConfig,
    private val consentProvider: AdConsentProvider,
    private val activityHost: AdActivityHost,
) : AdProvider, AutoCloseable {
    override val id: AdProviderId = AdProviderId.COMPANY_HUAWEI

    private val initializationMutex = Mutex()
    private val rewardedLoadMutex = Mutex()
    private val interstitialLoadMutex = Mutex()
    private var initialized = false
    private var rewardedAd: RewardAd? = null
    private var interstitialAd: InterstitialAd? = null

    override suspend fun preload(request: RuntimeAdRequest): AdPreloadResult {
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

    override suspend fun show(request: RuntimeAdRequest): AdPresentationResult {
        if (!ensureInitialized()) return AdPresentationResult.ProviderUnavailable
        return when (request.format) {
            AdFormat.BANNER -> AdPresentationResult.ProviderUnavailable
            AdFormat.REWARDED -> showRewarded()
            AdFormat.INTERSTITIAL -> showInterstitial()
        }
    }

    override fun close() {
        rewardedAd?.destroy()
        rewardedAd = null
        interstitialAd?.adListener = null
        interstitialAd = null
    }

    private suspend fun ensureInitialized(): Boolean {
        val decision = consentProvider.currentDecision()
        if (decision == AdConsentDecision.UNDECIDED || config.applicationId.isBlank()) return false
        return initializationMutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                HwAds.setRequestOptions(
                    RequestOptions.Builder()
                        .setNonPersonalizedAd(
                            if (decision == AdConsentDecision.ACCEPTED) {
                                PersonalizedAds
                            } else {
                                NonPersonalizedAds
                            },
                        )
                        .setRequestLocation(false)
                        .build(),
                )
                if (!initialized) {
                    HwAds.init(appContext, config.applicationId)
                    initialized = true
                }
                true
            }
        }
    }

    private suspend fun preloadRewarded(): AdPreloadResult = rewardedLoadMutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            if (rewardedAd?.isLoaded == true) return@withContext AdPreloadResult.ALREADY_READY
            val adUnitId = config.rewardedAdUnitId.takeIf(String::isNotBlank)
                ?: return@withContext AdPreloadResult.PROVIDER_UNAVAILABLE
            val ad = RewardAd(appContext, adUnitId)
            suspendCancellableCoroutine { continuation ->
                ad.loadAd(
                    buildHuaweiAdRequest(consentProvider.currentDecision()),
                    object : RewardAdLoadListener() {
                        override fun onRewardedLoaded() {
                            rewardedAd = ad
                            if (continuation.isActive) continuation.resume(AdPreloadResult.READY)
                        }

                        override fun onRewardAdFailedToLoad(errorCode: Int) {
                            ad.destroy()
                            if (continuation.isActive) continuation.resume(AdPreloadResult.FAILED)
                        }
                    },
                )
                continuation.invokeOnCancellation {
                    ad.destroy()
                }
            }
        }
    }

    private suspend fun preloadInterstitial(): AdPreloadResult = interstitialLoadMutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            if (interstitialAd?.isLoaded == true) return@withContext AdPreloadResult.ALREADY_READY
            val adUnitId = config.postMatchInterstitialAdUnitId.takeIf(String::isNotBlank)
                ?: return@withContext AdPreloadResult.PROVIDER_UNAVAILABLE
            val ad = InterstitialAd(appContext).apply {
                adId = adUnitId
            }
            suspendCancellableCoroutine { continuation ->
                ad.adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        interstitialAd = ad
                        if (continuation.isActive) continuation.resume(AdPreloadResult.READY)
                    }

                    override fun onAdFailed(errorCode: Int) {
                        ad.adListener = null
                        if (continuation.isActive) continuation.resume(AdPreloadResult.FAILED)
                    }
                }
                ad.loadAd(buildHuaweiAdRequest(consentProvider.currentDecision()))
                continuation.invokeOnCancellation {
                    ad.adListener = null
                }
            }
        }
    }

    private suspend fun showRewarded(): AdPresentationResult =
        withContext(Dispatchers.Main.immediate) {
            val activity = activityHost.currentActivity()
                ?: return@withContext AdPresentationResult.ProviderUnavailable
            val ad = rewardedAd ?: return@withContext AdPresentationResult.NotReady
            rewardedAd = null
            suspendCancellableCoroutine { continuation ->
                var rewarded = false
                ad.show(
                    activity,
                    object : RewardAdStatusListener() {
                        override fun onRewarded(reward: Reward) {
                            rewarded = true
                        }

                        override fun onRewardAdClosed() {
                            ad.destroy()
                            if (continuation.isActive) {
                                continuation.resume(
                                    if (rewarded) {
                                        AdPresentationResult.Completed
                                    } else {
                                        AdPresentationResult.Dismissed
                                    },
                                )
                            }
                        }

                        override fun onRewardAdFailedToShow(errorCode: Int) {
                            ad.destroy()
                            if (continuation.isActive) {
                                continuation.resume(AdPresentationResult.Failed)
                            }
                        }
                    },
                )
            }
        }

    private suspend fun showInterstitial(): AdPresentationResult =
        withContext(Dispatchers.Main.immediate) {
            val activity = activityHost.currentActivity()
                ?: return@withContext AdPresentationResult.ProviderUnavailable
            val ad = interstitialAd ?: return@withContext AdPresentationResult.NotReady
            interstitialAd = null
            suspendCancellableCoroutine { continuation ->
                ad.adListener = object : AdListener() {
                    override fun onAdClosed() {
                        ad.adListener = null
                        if (continuation.isActive) {
                            continuation.resume(AdPresentationResult.Dismissed)
                        }
                    }

                    override fun onAdFailed(errorCode: Int) {
                        ad.adListener = null
                        if (continuation.isActive) {
                            continuation.resume(AdPresentationResult.Failed)
                        }
                    }
                }
                ad.show(activity)
                continuation.invokeOnCancellation {
                    ad.adListener = null
                }
            }
        }
}

internal fun buildHuaweiAdRequest(decision: AdConsentDecision): AdParam =
    AdParam.Builder()
        .setNonPersonalizedAd(
            if (decision == AdConsentDecision.ACCEPTED) {
                PersonalizedAds
            } else {
                NonPersonalizedAds
            },
        )
        .setRequestLocation(false)
        .build()

private const val PersonalizedAds = 0
private const val NonPersonalizedAds = 1
