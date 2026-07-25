package com.mirkori.inplacex.platform.services

import android.content.Context
import com.mirkori.inplacex.platform.config.AdsProviderConfig
import com.mirkori.inplacex.platform.config.BillingProviderConfig
import com.mirkori.inplacex.platform.config.GooglePlayProviderConfig

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
    private val session = AuthSession(
        isSignedIn = false,
        provider = null,
        playerName = initialPlayerName,
    )

    override fun currentSession(): AuthSession = session

    override fun signInWithGooglePlay(): AuthSession {
        // До подключения SDK нельзя считать наличие идентификатора успешной авторизацией.
        return session
    }

    override fun signOut(): AuthSession {
        return session
    }
}

class AdMobService(
    private val appContext: Context,
    private val config: AdsProviderConfig,
) : AdService {
    override fun showBanner(slotId: String): Boolean {
        return false
    }

    override fun showRewardedAd(placement: RewardedPlacement): Boolean {
        return false
    }

    override fun shouldShowPostGameInterstitial(
        matchesPlayed: Int,
        entitlements: MonetizationEntitlements,
    ): Boolean {
        return false
    }

    override fun showInterstitial(placement: InterstitialPlacement): Boolean {
        return false
    }
}

class GooglePlayBillingService(
    private val appContext: Context,
    private val config: BillingProviderConfig,
) : BillingService {
    override fun purchase(productId: BillingProductId): Boolean {
        return false
    }
}
