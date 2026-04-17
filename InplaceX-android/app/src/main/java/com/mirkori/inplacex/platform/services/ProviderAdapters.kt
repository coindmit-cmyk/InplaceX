package com.mirkori.inplacex.platform.services

import android.content.Context
import com.mirkori.inplacex.platform.config.AdsProviderConfig
import com.mirkori.inplacex.platform.config.BillingProviderConfig
import com.mirkori.inplacex.platform.config.GooglePlayProviderConfig
import com.mirkori.inplacex.platform.config.PlatformConfig
import com.mirkori.inplacex.platform.config.ProviderEnvironment

data class ProviderServices(
    val authService: AuthService,
    val profileService: ProfileService,
    val adService: AdService,
    val billingService: BillingService,
)

class GooglePlayAuthService(
    private val appContext: Context,
    private val config: GooglePlayProviderConfig,
    initialPlayerName: String = "Player_7065",
) : AuthService, ProfileService {
    private var session = AuthSession(
        isSignedIn = false,
        provider = null,
        playerName = initialPlayerName,
    )

    override fun currentSession(): AuthSession = session

    override fun signInWithGooglePlay(): AuthSession {
        // SDK-ready placeholder:
        // later this method should call Google Sign-In / Play Games Services.
        if (!config.isConfigured) return session
        session = AuthSession(
            isSignedIn = true,
            provider = AuthProviderType.GOOGLE_PLAY,
            playerName = session.playerName,
        )
        return session
    }

    override fun signOut(): AuthSession {
        // SDK-ready placeholder for Google sign-out.
        session = session.copy(
            isSignedIn = false,
            provider = null,
        )
        return session
    }
}

class AdMobService(
    private val appContext: Context,
    private val config: AdsProviderConfig,
) : AdService {
    override fun showBanner(slotId: String): Boolean {
        // SDK-ready placeholder for banner ad request / view binding.
        return config.isConfigured
    }

    override fun showRewardedAd(placement: RewardedPlacement): Boolean {
        // SDK-ready placeholder for rewarded ad flow.
        return config.isConfigured
    }

    override fun shouldShowPostGameInterstitial(
        matchesPlayed: Int,
        entitlements: MonetizationEntitlements,
    ): Boolean {
        if (entitlements.adsDisabled) return false
        if (!config.isConfigured) return false
        if (matchesPlayed < 20) return false
        return matchesPlayed % 4 == 0
    }

    override fun showInterstitial(placement: InterstitialPlacement): Boolean {
        // SDK-ready placeholder for interstitial display.
        return config.isConfigured
    }
}

class GooglePlayBillingService(
    private val appContext: Context,
    private val config: BillingProviderConfig,
) : BillingService {
    override fun purchase(productId: BillingProductId): Boolean {
        // SDK-ready placeholder for Google Play Billing launch flow.
        return when (productId) {
            BillingProductId.REMOVE_ADS -> config.removeAdsProductId.isNotBlank()
            BillingProductId.PRO_SUBSCRIPTION -> config.proSubscriptionId.isNotBlank()
            BillingProductId.PRO_PLUS_SUBSCRIPTION -> config.proPlusSubscriptionId.isNotBlank()
        }
    }
}

object ProviderServicesFactory {
    fun create(
        context: Context,
        platformConfig: PlatformConfig,
    ): ProviderServices {
        val providers = platformConfig.providers
        return if (providers.environment == ProviderEnvironment.LIVE) {
            val auth = GooglePlayAuthService(context.applicationContext, providers.googlePlay)
            ProviderServices(
                authService = auth,
                profileService = auth,
                adService = AdMobService(context.applicationContext, providers.ads),
                billingService = GooglePlayBillingService(context.applicationContext, providers.billing),
            )
        } else {
            val auth = StubGooglePlayAuthService()
            ProviderServices(
                authService = auth,
                profileService = auth,
                adService = StubAdService(),
                billingService = StubBillingService(),
            )
        }
    }
}
