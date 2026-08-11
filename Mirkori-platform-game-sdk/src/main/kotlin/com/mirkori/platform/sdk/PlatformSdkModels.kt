package com.mirkori.platform.sdk

import java.time.Instant

data class MirkoriGameSdkConfig(
    val platformBaseUrl: String,
    val gameId: String,
    val redirectUri: String,
    val allowCleartextLoopback: Boolean = false,
)

enum class GameClientPlatform(val wireName: String) {
    ANDROID("android"),
    IOS("ios"),
    WINDOWS("windows"),
    LINUX("linux"),
    WEB("web"),
}

enum class PlatformAuthMode(val wireName: String) {
    GUEST("guest"),
    GOOGLE("google"),
    LOCAL("local"),
    TELEGRAM("telegram");

    companion object {
        internal fun fromWireName(value: String): PlatformAuthMode? = entries.firstOrNull { it.wireName == value }
    }
}

enum class PlatformProductKind(val wireName: String) {
    GAME("game"),
    ADDON("addon"),
    CURRENCY("currency");

    companion object {
        internal fun fromWireName(value: String): PlatformProductKind? = entries.firstOrNull { it.wireName == value }
    }
}

enum class PlatformEntitlementType(val wireName: String) {
    DURABLE("durable"),
    CONSUMABLE("consumable"),
    TIMED("timed");

    companion object {
        internal fun fromWireName(value: String): PlatformEntitlementType? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

enum class PlatformOrderStatus(val wireName: String) {
    PENDING("pending"),
    PAID("paid"),
    REFUNDED("refunded"),
    CANCELLED("cancelled");

    companion object {
        internal fun fromWireName(value: String): PlatformOrderStatus? = entries.firstOrNull { it.wireName == value }
    }
}

enum class PlatformCheckoutStatus(val wireName: String) {
    CREATING("creating"),
    READY("ready"),
    EXPIRED("expired");

    companion object {
        internal fun fromWireName(value: String): PlatformCheckoutStatus? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

data class PlatformProductPrice(
    val currency: String,
    val amountMinor: Long,
)

data class PlatformProductGrant(
    val entitlementKey: String,
    val type: PlatformEntitlementType,
    val quantity: Long,
    val durationSeconds: Long?,
)

data class PlatformProductOffer(
    val id: String,
    val gameId: String,
    val slug: String,
    val displayName: String,
    val description: String,
    val kind: PlatformProductKind,
    val version: Long,
    val price: PlatformProductPrice,
    val grants: List<PlatformProductGrant>,
)

data class PlatformOrder(
    val id: String,
    val gameId: String,
    val gamePlayerId: String,
    val productId: String,
    val currency: String,
    val amountMinor: Long,
    val status: PlatformOrderStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class PlatformEntitlement(
    val key: String,
    val type: PlatformEntitlementType,
    val quantity: Long,
    val validUntil: Instant?,
)

data class PlatformServerTimeObservation(
    val serverEpochMs: Long,
    val revision: Long,
)

class PlatformCheckout(
    val id: String,
    val orderId: String,
    val provider: String,
    val status: PlatformCheckoutStatus,
    val paymentUrl: String,
    val expiresAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun toString(): String =
        "PlatformCheckout(id=$id, orderId=$orderId, status=${status.wireName}, expiresAt=$expiresAt, [redacted])"
}

data class PlatformConsumptionReceipt(
    val id: String,
    val entitlementKey: String,
    val quantity: Long,
    val remainingQuantity: Long,
    val createdAt: Instant,
)

data class PlatformPublicPlayerProfile(
    val gamePlayerId: String,
    val handle: String?,
    val displayName: String,
    val avatarUrl: String?,
)

class InstallationIdentity(
    val installationId: String,
    val installationSecret: String,
) {
    override fun toString(): String = "InstallationIdentity([redacted])"
}

class PlatformCredentials(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Instant,
    val refreshExpiresAt: Instant,
) {
    override fun toString(): String = "PlatformCredentials([redacted])"
}

class GameIdentitySession(
    val accountId: String,
    val gamePlayerId: String,
    val gameId: String,
    val installationId: String?,
    val authMode: PlatformAuthMode,
    val credentials: PlatformCredentials,
) {
    override fun toString(): String =
        "GameIdentitySession(gameId=$gameId, authMode=${authMode.wireName}, [redacted])"
}

class PendingGameLogin(
    val session: String,
    val state: String,
    val codeVerifier: String,
    val connectUrl: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "PendingGameLogin(expiresAt=$expiresAt, [redacted])"
}

class PlatformIdempotencyKey(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9._~-]{1,128}")))
    }

    override fun toString(): String = "PlatformIdempotencyKey([redacted])"
}

class PlatformApiException(
    val status: Int,
    val errorCode: String,
) : IllegalStateException("Platform request failed: HTTP $status ($errorCode)")

class PlatformCallbackRejectedException : IllegalArgumentException("Game login callback is not accepted")

class PlatformProfileConflictException : IllegalStateException("The platform account already has a game profile")
