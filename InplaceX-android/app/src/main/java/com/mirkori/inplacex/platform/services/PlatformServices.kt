package com.mirkori.inplacex.platform.services

enum class AuthProviderType {
    GOOGLE_PLAY,
}

data class AuthSession(
    val isSignedIn: Boolean,
    val provider: AuthProviderType? = null,
    val playerName: String = "Guest",
)

enum class RewardedPlacement {
    GAME_OPEN_POSITION_HINT,
    GAME_CHECK_DIGIT_HINT,
    GAME_CHECK_POSITION_HINT,
    SHOP_COINS_REWARD,
}

enum class InterstitialPlacement {
    POST_MATCH,
}

enum class BillingProductId {
    REMOVE_ADS,
    PRO_SUBSCRIPTION,
    PRO_PLUS_SUBSCRIPTION,
}

data class MonetizationEntitlements(
    val adFreePurchased: Boolean,
    val proSubscriptionActive: Boolean,
    val proPlusSubscriptionActive: Boolean,
) {
    val adsDisabled: Boolean
        get() = adFreePurchased || proSubscriptionActive || proPlusSubscriptionActive

    val autoTableAssistEnabled: Boolean
        get() = proSubscriptionActive || proPlusSubscriptionActive

    val infiniteHintsEnabled: Boolean
        get() = proPlusSubscriptionActive
}

interface AdService {
    fun showBanner(slotId: String): Boolean
    fun showRewardedAd(placement: RewardedPlacement): Boolean
    fun shouldShowPostGameInterstitial(
        matchesPlayed: Int,
        entitlements: MonetizationEntitlements,
    ): Boolean

    fun showInterstitial(placement: InterstitialPlacement): Boolean
}

interface AnalyticsService {
    fun track(event: String, properties: Map<String, String> = emptyMap())
}

interface AuthService {
    fun currentSession(): AuthSession
    fun signInWithGooglePlay(): AuthSession
    fun signOut(): AuthSession
}

interface ProfileService {
    fun currentSession(): AuthSession
}

interface BillingService {
    fun purchase(productId: BillingProductId): Boolean
}

interface SocialService {
    fun openFriends()
}

class StubAdService(
    private val rewardedResult: Boolean = true,
) : AdService {
    override fun showBanner(slotId: String): Boolean = true

    override fun showRewardedAd(placement: RewardedPlacement): Boolean = rewardedResult

    override fun shouldShowPostGameInterstitial(
        matchesPlayed: Int,
        entitlements: MonetizationEntitlements,
    ): Boolean {
        if (entitlements.adsDisabled) return false
        if (matchesPlayed < 20) return false
        return matchesPlayed % 4 == 0
    }

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
