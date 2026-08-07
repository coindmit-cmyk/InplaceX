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

    companion object {
        val None = MonetizationEntitlements(
            adFreePurchased = false,
            proSubscriptionActive = false,
            proPlusSubscriptionActive = false,
        )
    }
}

enum class BillingAvailability {
    INITIALIZING,
    READY,
    OFFLINE,
    UNAVAILABLE,
}

enum class BillingNotice {
    NONE,
    CHECKOUT_OPENED,
    AWAITING_PAYMENT,
    AWAITING_ENTITLEMENT,
    PAYMENT_CONFIRMED,
    PAYMENT_CANCELLED,
    PAYMENT_REFUNDED,
    CHECKOUT_EXPIRED,
    LINKED_ACCOUNT_REQUIRED,
    PROVIDER_UNAVAILABLE,
    OFFLINE,
    RETRY_REQUIRED,
    PRODUCT_ALREADY_ACTIVE,
    BUSY,
    CONFIGURATION_REQUIRED,
}

data class BillingProduct(
    val platformProductId: String,
    val displayName: String,
    val description: String,
    val currency: String,
    val amountMinor: Long,
) {
    init {
        require(platformProductId.isNotBlank())
        require(currency.matches(Regex("[A-Z]{3}")))
        require(amountMinor > 0)
    }
}

data class BillingState(
    val availability: BillingAvailability = BillingAvailability.INITIALIZING,
    val products: Map<BillingProductId, BillingProduct> = emptyMap(),
    val entitlements: MonetizationEntitlements = MonetizationEntitlements.None,
    val pendingProduct: BillingProductId? = null,
    val pendingOrderId: String? = null,
    val notice: BillingNotice = BillingNotice.NONE,
    val nextEntitlementExpiryAtMs: Long? = null,
)

sealed interface BillingPurchaseResult {
    val state: BillingState

    data class OpenExternalCheckout(
        val checkoutUrl: String,
        override val state: BillingState,
    ) : BillingPurchaseResult

    data class StateUpdated(
        override val state: BillingState,
    ) : BillingPurchaseResult
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
    fun cachedState(): BillingState

    suspend fun refresh(): BillingState

    suspend fun purchase(productId: BillingProductId): BillingPurchaseResult
}

interface SocialService {
    fun openFriends()
}
