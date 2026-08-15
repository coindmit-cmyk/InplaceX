package com.mirkori.inplacex.platform.services

import android.content.Context
import com.mirkori.inplacex.ads.AdMarket
import com.mirkori.inplacex.BuildConfig
import com.mirkori.inplacex.platform.ads.AdConsentController
import com.mirkori.inplacex.platform.ads.AdActivityHost
import com.mirkori.inplacex.platform.ads.AdMarketResolver
import com.mirkori.inplacex.platform.ads.YandexAdProvider
import com.mirkori.inplacex.platform.ads.createAdRuntime
import com.mirkori.inplacex.platform.ads.SharedPreferencesAdConsentController
import com.mirkori.inplacex.platform.ads.createBackendAdMarketResolverOrUnknown
import com.mirkori.inplacex.platform.config.AdSdkConfig
import com.mirkori.inplacex.platform.config.AdsProviderConfig
import com.mirkori.inplacex.platform.config.PlatformConfig
import com.mirkori.inplacex.platform.config.ProviderEnvironment

class StubAdService(
    private val rewardedResult: Boolean = false,
) : AdService {
    override fun showBanner(slotId: String): Boolean = false

    override fun showRewardedAd(placement: RewardedPlacement): Boolean = rewardedResult

    override fun shouldShowPostGameInterstitial(
        matchesPlayed: Int,
        entitlements: MonetizationEntitlements,
    ): Boolean =
        AdPlacementPolicy.shouldShowPostMatchInterstitial(matchesPlayed, entitlements)

    override fun showInterstitial(placement: InterstitialPlacement): Boolean = false
}

class StubGooglePlayAuthService(
    initialPlayerName: String = "Player_7065",
) : AuthService, ProfileService {
    private var session = AuthSession(
        isSignedIn = false,
        provider = null,
        playerName = initialPlayerName,
    )

    override fun currentSession(): AuthSession = session

    override fun signInWithGooglePlay(): AuthSession {
        session = AuthSession(
            isSignedIn = true,
            provider = AuthProviderType.GOOGLE_PLAY,
            playerName = session.playerName,
        )
        return session
    }

    override fun signOut(): AuthSession {
        session = session.copy(
            isSignedIn = false,
            provider = null,
        )
        return session
    }
}

class StubBillingService : BillingService {
    private val unavailable = BillingState(
        availability = BillingAvailability.UNAVAILABLE,
        notice = BillingNotice.CONFIGURATION_REQUIRED,
    )

    override fun cachedState(): BillingState = unavailable

    override suspend fun refresh(): BillingState = unavailable

    override suspend fun purchase(productId: BillingProductId): BillingPurchaseResult =
        BillingPurchaseResult.StateUpdated(unavailable)
}

object ProviderServicesFactory {
    fun create(
        context: Context,
        platformConfig: PlatformConfig,
        adConsentController: AdConsentController? = null,
        billingService: BillingService? = null,
    ): ProviderServices {
        val auth = StubGooglePlayAuthService()
        val adConsent = adConsentController ?: SharedPreferencesAdConsentController(context)
        val adActivityHost = AdActivityHost()
        val marketResolver = selectDebugAdMarketResolver(
            environment = platformConfig.providers.environment,
            backendResolver = {
                createBackendAdMarketResolverOrUnknown(
                    baseUrl = BuildConfig.ONLINE_BASE_URL,
                    allowCleartextLoopback = BuildConfig.ONLINE_ALLOW_CLEARTEXT_LOOPBACK,
                )
            },
        )
        val effectiveAdsConfig = selectDebugAdsConfig(
            environment = platformConfig.providers.environment,
            configured = platformConfig.providers.ads,
        )
        val effectiveYandexConfig = effectiveAdsConfig.ownerYandex
        val yandex = YandexAdProvider(
            appContext = context,
            config = effectiveYandexConfig,
            consentProvider = adConsent,
            activityHost = adActivityHost,
        )
        return ProviderServices(
            authService = auth,
            profileService = auth,
            adService = StubAdService(),
            adRuntime = createAdRuntime(
                config = effectiveAdsConfig,
                providers = listOf(yandex),
                marketResolver = marketResolver,
                consentProvider = adConsent,
            ),
            adConsent = adConsent,
            adActivityHost = adActivityHost,
            gameBannerAdUnitId = effectiveYandexConfig.gameBannerAdUnitId,
            billingService = billingService ?: StubBillingService(),
        )
    }
}

internal fun selectDebugAdMarketResolver(
    environment: ProviderEnvironment,
    backendResolver: () -> AdMarketResolver,
): AdMarketResolver = when (environment) {
    ProviderEnvironment.SANDBOX -> AdMarketResolver { AdMarket.RUSSIA }
    ProviderEnvironment.LIVE -> backendResolver()
}

internal fun selectDebugYandexConfig(
    environment: ProviderEnvironment,
    configured: AdSdkConfig,
): AdSdkConfig = when (environment) {
    ProviderEnvironment.SANDBOX -> AdSdkConfig(
        gameBannerAdUnitId = "demo-banner-yandex",
        rewardedAdUnitId = "demo-rewarded-yandex",
        postMatchInterstitialAdUnitId = "demo-interstitial-yandex",
    )
    ProviderEnvironment.LIVE -> configured
}

internal fun selectDebugAdsConfig(
    environment: ProviderEnvironment,
    configured: AdsProviderConfig,
): AdsProviderConfig = configured.copy(
    ownerYandex = selectDebugYandexConfig(
        environment = environment,
        configured = configured.ownerYandex,
    ),
)
