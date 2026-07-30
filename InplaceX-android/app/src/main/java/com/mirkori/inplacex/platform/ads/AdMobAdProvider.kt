package com.mirkori.inplacex.platform.ads

import android.os.Bundle
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.ConsentInformation.PrivacyOptionsRequirementStatus
import com.google.android.ump.UserMessagingPlatform
import com.mirkori.inplacex.ads.AdFormat
import com.mirkori.inplacex.ads.AdPreloadResult
import com.mirkori.inplacex.ads.AdPresentationResult
import com.mirkori.inplacex.ads.AdProvider
import com.mirkori.inplacex.ads.AdProviderId
import com.mirkori.inplacex.ads.AdRequest as RuntimeAdRequest
import com.mirkori.inplacex.platform.config.AdSdkConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AdMobAdProvider(
    private val appContext: android.content.Context,
    private val config: AdSdkConfig,
    private val consentProvider: AdConsentProvider,
    private val activityHost: AdActivityHost,
) : AdProvider, AdPrivacyOptionsHandler, AutoCloseable {
    override val id: AdProviderId = AdProviderId.COMPANY_ADMOB

    private val initializationMutex = Mutex()
    private val rewardedLoadMutex = Mutex()
    private val interstitialLoadMutex = Mutex()
    private var initialized = false
    private var rewardedAd: RewardedAd? = null
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
        rewardedAd?.fullScreenContentCallback = null
        rewardedAd = null
        interstitialAd?.fullScreenContentCallback = null
        interstitialAd = null
    }

    override suspend fun showPrivacyOptions(): Boolean {
        val activity = activityHost.currentActivity() ?: return false
        val consentInformation = UserMessagingPlatform.getConsentInformation(appContext)
        if (
            consentInformation.privacyOptionsRequirementStatus !=
            PrivacyOptionsRequirementStatus.REQUIRED
        ) {
            return false
        }
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
                    if (continuation.isActive) continuation.resume(formError == null)
                }
            }
        }
    }

    private suspend fun ensureInitialized(): Boolean {
        if (config.applicationId.isBlank()) return false
        val localDecision = consentProvider.currentDecision()
        if (localDecision == AdConsentDecision.UNDECIDED) return false
        return initializationMutex.withLock {
            if (initialized) return@withLock true
            val activity = activityHost.currentActivity() ?: return@withLock false
            val consentInformation = UserMessagingPlatform.getConsentInformation(appContext)
            val canRequestAds = withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    val parameters = ConsentRequestParameters.Builder().build()
                    consentInformation.requestConsentInfoUpdate(
                        activity,
                        parameters,
                        {
                            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                                if (continuation.isActive) {
                                    continuation.resume(consentInformation.canRequestAds())
                                }
                            }
                        },
                        {
                            if (continuation.isActive) {
                                continuation.resume(consentInformation.canRequestAds())
                            }
                        },
                    )
                }
            }
            if (!canRequestAds) return@withLock false
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    MobileAds.initialize(appContext) {
                        initialized = true
                        if (continuation.isActive) continuation.resume(true)
                    }
                }
            }
        }
    }

    private suspend fun preloadRewarded(): AdPreloadResult = rewardedLoadMutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            if (rewardedAd != null) return@withContext AdPreloadResult.ALREADY_READY
            val adUnitId = config.rewardedAdUnitId.takeIf(String::isNotBlank)
                ?: return@withContext AdPreloadResult.PROVIDER_UNAVAILABLE
            suspendCancellableCoroutine { continuation ->
                RewardedAd.load(
                    appContext,
                    adUnitId,
                    buildAdMobRequest(consentProvider.currentDecision()),
                    object : RewardedAdLoadCallback() {
                        override fun onAdLoaded(ad: RewardedAd) {
                            rewardedAd = ad
                            if (continuation.isActive) continuation.resume(AdPreloadResult.READY)
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            rewardedAd = null
                            if (continuation.isActive) continuation.resume(AdPreloadResult.FAILED)
                        }
                    },
                )
            }
        }
    }

    private suspend fun preloadInterstitial(): AdPreloadResult = interstitialLoadMutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            if (interstitialAd != null) return@withContext AdPreloadResult.ALREADY_READY
            val adUnitId = config.postMatchInterstitialAdUnitId.takeIf(String::isNotBlank)
                ?: return@withContext AdPreloadResult.PROVIDER_UNAVAILABLE
            suspendCancellableCoroutine { continuation ->
                InterstitialAd.load(
                    appContext,
                    adUnitId,
                    buildAdMobRequest(consentProvider.currentDecision()),
                    object : InterstitialAdLoadCallback() {
                        override fun onAdLoaded(ad: InterstitialAd) {
                            interstitialAd = ad
                            if (continuation.isActive) continuation.resume(AdPreloadResult.READY)
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            interstitialAd = null
                            if (continuation.isActive) continuation.resume(AdPreloadResult.FAILED)
                        }
                    },
                )
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
                val rewarded = AtomicBoolean(false)
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        ad.fullScreenContentCallback = null
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

                    override fun onAdFailedToShowFullScreenContent(error: AdError) {
                        ad.fullScreenContentCallback = null
                        if (continuation.isActive) {
                            continuation.resume(AdPresentationResult.Failed)
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    ad.fullScreenContentCallback = null
                }
                ad.show(activity) {
                    rewarded.set(true)
                }
            }
        }

    private suspend fun showInterstitial(): AdPresentationResult =
        withContext(Dispatchers.Main.immediate) {
            val activity = activityHost.currentActivity()
                ?: return@withContext AdPresentationResult.ProviderUnavailable
            val ad = interstitialAd ?: return@withContext AdPresentationResult.NotReady
            interstitialAd = null
            suspendCancellableCoroutine { continuation ->
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        ad.fullScreenContentCallback = null
                        if (continuation.isActive) {
                            continuation.resume(AdPresentationResult.Dismissed)
                        }
                    }

                    override fun onAdFailedToShowFullScreenContent(error: AdError) {
                        ad.fullScreenContentCallback = null
                        if (continuation.isActive) {
                            continuation.resume(AdPresentationResult.Failed)
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    ad.fullScreenContentCallback = null
                }
                ad.show(activity)
            }
        }
}

internal fun buildAdMobRequest(decision: AdConsentDecision): AdRequest =
    AdRequest.Builder().apply {
        if (decision == AdConsentDecision.DECLINED) {
            addNetworkExtrasBundle(
                AdMobAdapter::class.java,
                Bundle().apply { putString("npa", "1") },
            )
        }
    }.build()
