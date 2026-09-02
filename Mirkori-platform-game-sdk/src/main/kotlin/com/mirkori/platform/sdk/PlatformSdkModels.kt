package com.mirkori.platform.sdk

import java.time.Instant

data class MirkoriGameSdkConfig(
    val platformBaseUrl: String,
    val gameId: String,
    val redirectUri: String,
    val allowCleartextLoopback: Boolean = false,
    val distributionId: String? = null,
)

enum class GameClientPlatform(val wireName: String) {
    ANDROID("android"),
    IOS("ios"),
    WINDOWS("windows"),
    LINUX("linux"),
    WEB("web"),
}

enum class PlatformReleasePlatform(val wireName: String) {
    ANDROID("android"),
    WINDOWS("windows");

    internal companion object {
        fun fromWireName(value: String): PlatformReleasePlatform? = entries.firstOrNull { it.wireName == value }
    }
}

enum class PlatformReleaseChannel(val wireName: String) {
    STABLE("stable"),
    BETA("beta");

    internal companion object {
        fun fromWireName(value: String): PlatformReleaseChannel? = entries.firstOrNull { it.wireName == value }
    }
}

enum class PlatformUpdateStatus {
    UP_TO_DATE,
    OPTIONAL,
    REQUIRED,
}

enum class PlatformInstalledBuildStatus(val wireName: String) {
    UP_TO_DATE("up_to_date"),
    OPTIONAL("optional"),
    REQUIRED("required"),
    UNAVAILABLE("unavailable"),
    RECALLED("recalled");

    internal companion object {
        fun fromWireName(value: String): PlatformInstalledBuildStatus? = entries.firstOrNull { it.wireName == value }
    }
}

data class PlatformGameRelease(
    val id: String,
    val gameId: String,
    val platform: PlatformReleasePlatform,
    val channel: PlatformReleaseChannel,
    val versionName: String,
    val versionCode: Long,
    val minimumSupportedVersionCode: Long,
    val minimumAndroidSdk: Int?,
    val publishedAt: Instant,
    val changelog: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val downloadUrl: String,
    val packageName: String?,
    val signingCertificateSha256Fingerprints: List<String>,
)

data class PlatformUpdateDecision(
    val gameId: String,
    val platform: PlatformReleasePlatform,
    val channel: PlatformReleaseChannel,
    val currentVersionCode: Long,
    val updateAvailable: Boolean,
    val required: Boolean,
    val release: PlatformGameRelease?,
) {
    val status: PlatformUpdateStatus
        get() = when {
            required -> PlatformUpdateStatus.REQUIRED
            updateAvailable -> PlatformUpdateStatus.OPTIONAL
            else -> PlatformUpdateStatus.UP_TO_DATE
        }
}

enum class PlatformDistributionMarketScope(val wireName: String) {
    RF("rf"),
    GLOBAL("global");

    internal companion object {
        fun fromWireName(value: String): PlatformDistributionMarketScope? = entries.firstOrNull { it.wireName == value }
    }
}

enum class PlatformDistributionPaymentChannel(val wireName: String) {
    MIRKORI("mirkori"),
    GOOGLE_PLAY("google_play");

    internal companion object {
        fun fromWireName(value: String): PlatformDistributionPaymentChannel? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

enum class PlatformDistributionDeliveryChannel(val wireName: String) {
    DIRECT_APK("direct_apk"),
    GOOGLE_PLAY("google_play");

    internal companion object {
        fun fromWireName(value: String): PlatformDistributionDeliveryChannel? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

enum class PlatformDistributionStatus(val wireName: String) {
    ACTIVE("active");

    internal companion object {
        fun fromWireName(value: String): PlatformDistributionStatus? = entries.firstOrNull { it.wireName == value }
    }
}

data class PlatformDistributionVariant(
    val id: String,
    val gameId: String,
    val platform: PlatformReleasePlatform,
    val marketScope: PlatformDistributionMarketScope,
    val packageName: String,
    val signingIdentityRef: String,
    val signingCertificateSha256Fingerprints: List<String>,
    val paymentChannel: PlatformDistributionPaymentChannel,
    val deliveryChannel: PlatformDistributionDeliveryChannel,
    val releaseChannels: Set<PlatformReleaseChannel>,
    val status: PlatformDistributionStatus,
    val effectiveConfigurationVersion: Long,
)

data class PlatformDistributionGameRelease(
    val id: String,
    val gameId: String,
    val distributionId: String,
    val platform: PlatformReleasePlatform,
    val channel: PlatformReleaseChannel,
    val versionName: String,
    val versionCode: Long,
    val minimumSupportedVersionCode: Long,
    val minimumAndroidSdk: Int,
    val publishedAt: Instant,
    val changelogs: Map<String, String>,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val downloadUrl: String?,
    val packageName: String,
    val signingIdentityRef: String,
    val signingCertificateSha256Fingerprints: List<String>,
)

data class PlatformDistributionUpdateDecision(
    val gameId: String,
    val distribution: PlatformDistributionVariant,
    val channel: PlatformReleaseChannel,
    val currentVersionCode: Long,
    val updateAvailable: Boolean,
    val required: Boolean,
    val release: PlatformDistributionGameRelease?,
) {
    val status: PlatformUpdateStatus
        get() = when {
            required -> PlatformUpdateStatus.REQUIRED
            updateAvailable -> PlatformUpdateStatus.OPTIONAL
            else -> PlatformUpdateStatus.UP_TO_DATE
        }
}

data class PlatformInstalledBuildDecision(
    val gameId: String,
    val distributionId: String,
    val channel: PlatformReleaseChannel,
    val installedReleaseId: String,
    val installedVersionCode: Long,
    val status: PlatformInstalledBuildStatus,
    val launchAllowed: Boolean,
    val policyVersion: Long,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val distribution: PlatformDistributionVariant?,
    val reasonCode: String?,
    val supportPath: String?,
    val release: PlatformDistributionGameRelease?,
    val signatureKeyId: String,
)

enum class PlatformAuthMode(val wireName: String) {
    GUEST("guest"),
    GOOGLE("google"),
    LOCAL("local"),
    TELEGRAM("telegram"),
    YANDEX("yandex");

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

enum class PlatformPaymentChannel(val wireName: String) {
    WEB("web"),
    ANDROID("android"),
    IOS("ios"),
    DESKTOP("desktop"),
    TELEGRAM("telegram");

    companion object {
        internal fun fromWireName(value: String): PlatformPaymentChannel? = entries.firstOrNull { it.wireName == value }
    }
}

enum class PlatformPaymentMethodCategory(val wireName: String) {
    CARD("card"),
    BANK_TRANSFER("bank_transfer"),
    WALLET("wallet"),
    MOBILE("mobile"),
    STORE("store");

    companion object {
        internal fun fromWireName(value: String): PlatformPaymentMethodCategory? =
            entries.firstOrNull { it.wireName == value }
    }
}

enum class PlatformPaymentNextActionType(val wireName: String) {
    REDIRECT("redirect"),
    DEEP_LINK("deep_link"),
    EMBEDDED_SDK("embedded_sdk");

    companion object {
        internal fun fromWireName(value: String): PlatformPaymentNextActionType? =
            entries.firstOrNull { it.wireName == value }
    }
}

enum class PlatformPaymentStatus(val wireName: String) {
    CREATING("creating"),
    REQUIRES_ACTION("requires_action"),
    PROCESSING("processing"),
    SUCCEEDED("succeeded"),
    CANCELLED("cancelled"),
    FAILED("failed"),
    EXPIRED("expired");

    companion object {
        internal fun fromWireName(value: String): PlatformPaymentStatus? = entries.firstOrNull { it.wireName == value }
    }
}

data class PlatformPaymentMethod(
    val id: String,
    val category: PlatformPaymentMethodCategory,
    val displayName: String,
    val nextActionTypes: Set<PlatformPaymentNextActionType>,
)

data class PlatformPaymentMethods(
    val orderId: String,
    val currency: String,
    val amountMinor: Long,
    val countryCode: String?,
    val methods: List<PlatformPaymentMethod>,
)

class PlatformPaymentNextAction(
    val type: PlatformPaymentNextActionType,
    val url: String? = null,
    val fallbackUrl: String? = null,
    val sdkAdapter: String? = null,
    val clientToken: String? = null,
) {
    override fun toString(): String = "PlatformPaymentNextAction(type=${type.wireName}, [redacted])"
}

class PlatformPayment(
    val id: String,
    val orderId: String,
    val status: PlatformPaymentStatus,
    val paymentMethodId: String,
    val channel: PlatformPaymentChannel,
    val currency: String,
    val amountMinor: Long,
    val expiresAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val nextAction: PlatformPaymentNextAction?,
) {
    override fun toString(): String =
        "PlatformPayment(id=$id, orderId=$orderId, status=${status.wireName}, [redacted])"
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

class PlatformGuestCheckoutHandoff(
    val id: String,
    val productId: String,
    val currency: String,
    val checkoutUrl: String,
    val expiresAt: Instant,
) {
    override fun toString(): String =
        "PlatformGuestCheckoutHandoff(id=$id, productId=$productId, currency=$currency, " +
            "expiresAt=$expiresAt, checkoutUrl=[redacted])"
}

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

enum class PlatformGameDeliveryAction(val wireName: String) {
    GRANT("grant"),
    REVOKE("revoke");

    companion object {
        fun fromWireName(value: String): PlatformGameDeliveryAction? = entries.firstOrNull { it.wireName == value }
    }
}

enum class PlatformEntitlementKind(val wireName: String) {
    PERMANENT_GAME("permanent_game"),
    DURABLE_ADDON("durable_addon"),
    CONSUMABLE_BALANCE("consumable_balance"),
    TIME_BOUNDED_PRO("time_bounded_pro");

    companion object {
        fun fromWireName(value: String): PlatformEntitlementKind? = entries.firstOrNull { it.wireName == value }
    }
}

data class PlatformGameEntitlementDelivery(
    val id: String,
    val entitlementEventId: String,
    val entitlementId: String,
    val sequenceNumber: Long,
    val action: PlatformGameDeliveryAction,
    val gameId: String,
    val productId: String,
    val orderId: String,
    val entitlementKey: String,
    val entitlementKind: PlatformEntitlementKind,
    val quantityDelta: Long,
    val validFrom: Instant,
    val expiresAt: Instant?,
    val correctionQuantity: Long,
    val payloadSha256: String,
    val createdAt: Instant,
)

data class PlatformGameDeliveryAcknowledgement(
    val deliveryId: String,
    val acknowledgedAt: Instant,
)

data class PlatformPublicPlayerProfile(
    val gamePlayerId: String,
    val handle: String?,
    val displayName: String,
    val avatarUrl: String?,
)

enum class PlatformFriendRequestStatus(val wireName: String) {
    PENDING("pending"),
    ACCEPTED("accepted");

    companion object {
        fun fromWireName(value: String): PlatformFriendRequestStatus? = entries.firstOrNull { it.wireName == value }
    }
}

data class PlatformFriendRequest(
    val requestId: String,
    val status: PlatformFriendRequestStatus,
    val player: PlatformPublicPlayerProfile,
    val createdAt: Instant,
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

enum class PlatformRecoveryAction {
    RETRY_SAME_REQUEST,
    REAUTHENTICATE,
    RESOLVE_CONFLICT,
    DO_NOT_RETRY;

    internal companion object {
        fun forResponse(status: Int): PlatformRecoveryAction = when {
            status == 401 -> REAUTHENTICATE
            status == 408 || status == 425 || status == 429 || status in 500..599 -> RETRY_SAME_REQUEST
            status == 409 -> RESOLVE_CONFLICT
            else -> DO_NOT_RETRY
        }
    }
}

class PlatformApiException private constructor(
    val status: Int,
    val errorCode: String,
    val recoveryAction: PlatformRecoveryAction,
) : IllegalStateException("Platform request failed: HTTP $status ($errorCode)") {
    constructor(status: Int, errorCode: String) : this(
        status,
        errorCode,
        PlatformRecoveryAction.forResponse(status),
    )
}

class PlatformCallbackRejectedException : IllegalArgumentException("Game login callback is not accepted")

enum class PlatformProfileConflictResolution(val wireName: String) {
    KEEP_CURRENT_PROFILE("keep_current_profile"),
    USE_EXISTING_PROFILE("use_existing_profile"),
}

class PlatformProfileConflictException : IllegalStateException("The platform account already has a game profile")
