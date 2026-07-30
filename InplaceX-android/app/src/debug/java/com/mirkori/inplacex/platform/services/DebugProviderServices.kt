package com.mirkori.inplacex.platform.services

import android.content.Context
import com.mirkori.inplacex.platform.config.PlatformConfig

class StubAdService(
    private val rewardedResult: Boolean = true,
) : AdService {
    override fun showBanner(slotId: String): Boolean = true

    override fun showRewardedAd(placement: RewardedPlacement): Boolean = rewardedResult

    override fun shouldShowPostGameInterstitial(
        matchesPlayed: Int,
        entitlements: MonetizationEntitlements,
    ): Boolean =
        AdPlacementPolicy.shouldShowPostMatchInterstitial(matchesPlayed, entitlements)

    override fun showInterstitial(placement: InterstitialPlacement): Boolean = true
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
    override fun purchase(productId: BillingProductId): Boolean = true
}

object ProviderServicesFactory {
    fun create(
        context: Context,
        platformConfig: PlatformConfig,
    ): ProviderServices {
        val auth = StubGooglePlayAuthService()
        return ProviderServices(
            authService = auth,
            profileService = auth,
            adService = StubAdService(),
            billingService = StubBillingService(),
        )
    }
}
