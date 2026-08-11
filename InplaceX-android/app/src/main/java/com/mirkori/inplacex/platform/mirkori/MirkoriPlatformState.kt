package com.mirkori.inplacex.platform.mirkori

import com.mirkori.platform.sdk.GameIdentitySession
import com.mirkori.platform.sdk.InstallationIdentity
import com.mirkori.platform.sdk.PendingGameLogin
import com.mirkori.platform.sdk.PlatformAuthMode
import com.mirkori.platform.sdk.PlatformIdempotencyKey

data class MirkoriPersistedState(
    val installation: InstallationIdentity,
    val session: GameIdentitySession? = null,
    val pendingLogin: PendingGameLogin? = null,
    val pendingRefresh: PendingMirkoriRefresh? = null,
    val pendingPurchase: PendingMirkoriPurchase? = null,
    val confirmedEntitlements: ConfirmedMirkoriEntitlements? = null,
    val trustedTimeAnchor: MirkoriTrustedTimeAnchor? = null,
)

data class PendingMirkoriRefresh(
    val refreshToken: String,
    val idempotencyKey: PlatformIdempotencyKey,
) {
    override fun toString(): String = "PendingMirkoriRefresh([redacted])"
}

data class PendingMirkoriPurchase(
    val accountId: String,
    val gamePlayerId: String,
    val productId: String,
    val currency: String,
    val orderId: String? = null,
    val orderIdempotencyKey: PlatformIdempotencyKey,
    val checkoutIdempotencyKey: PlatformIdempotencyKey,
    val offerSnapshot: PendingMirkoriOfferSnapshot? = null,
) {
    override fun toString(): String = "PendingMirkoriPurchase(productId=$productId, [redacted])"
}

data class PendingMirkoriOfferSnapshot(
    val amountMinor: Long,
    val currency: String,
    val entitlementSchemaVersion: Int,
    val productVersion: Long?,
)

data class MirkoriTrustedTimeAnchor(
    val serverEpochMs: Long,
    val monotonicAtObservationMs: Long,
    val bootMarker: Long,
)

data class MirkoriFeatureGrant(
    val active: Boolean,
    val validUntilEpochMs: Long? = null,
) {
    init {
        require(active || validUntilEpochMs == null)
    }

    fun activeAt(trustedNowMs: Long?): Boolean = active && when (val expiresAt = validUntilEpochMs) {
        null -> true
        else -> trustedNowMs != null && expiresAt > trustedNowMs
    }
}

data class ConfirmedMirkoriEntitlements(
    val accountId: String,
    val gamePlayerId: String,
    val confirmedAtEpochMs: Long,
    val removeAds: MirkoriFeatureGrant,
    val pro: MirkoriFeatureGrant,
    val proPlus: MirkoriFeatureGrant,
) {
    fun nextExpiryDelayMs(trustedNowMs: Long?): Long? = trustedNowMs?.let { nowMs ->
        listOfNotNull(
            removeAds.validUntilEpochMs,
            pro.validUntilEpochMs,
            proPlus.validUntilEpochMs,
        ).filter { it > nowMs }.minOrNull()?.minus(nowMs)
    }

    override fun toString(): String = "ConfirmedMirkoriEntitlements([redacted])"
}

enum class MirkoriAccountStateKind {
    INITIALIZING,
    UNAVAILABLE,
    GUEST,
    LINKED,
}

data class MirkoriAccountState(
    val kind: MirkoriAccountStateKind,
    val gamePlayerId: String? = null,
    val authMode: PlatformAuthMode? = null,
    internal val accountIdentity: String? = null,
) {
    override fun toString(): String =
        "MirkoriAccountState(kind=$kind, authMode=$authMode, identity=[redacted])"
}

data class MirkoriPublicPlayerProfile(
    val gamePlayerId: String,
    val handle: String?,
    val displayName: String,
    val avatarUrl: String?,
)

data class MirkoriFriendRequest(
    val requestId: String,
    val player: MirkoriPublicPlayerProfile,
)

sealed interface MirkoriFriendOperationResult {
    data class Success(val request: MirkoriFriendRequest) : MirkoriFriendOperationResult
    data object Rejected : MirkoriFriendOperationResult
    data object Unavailable : MirkoriFriendOperationResult
}

sealed interface MirkoriIncomingFriendRequestsResult {
    data class Success(val requests: List<MirkoriFriendRequest>) : MirkoriIncomingFriendRequestsResult
    data object Unavailable : MirkoriIncomingFriendRequestsResult
}

sealed interface MirkoriFriendsResult {
    data class Success(val players: List<MirkoriPublicPlayerProfile>) : MirkoriFriendsResult
    data object Unavailable : MirkoriFriendsResult
}

sealed interface MirkoriPublicProfileResult {
    data class Success(val profile: MirkoriPublicPlayerProfile) : MirkoriPublicProfileResult

    data object HandleTaken : MirkoriPublicProfileResult

    data object Rejected : MirkoriPublicProfileResult

    data object Unavailable : MirkoriPublicProfileResult
}

sealed interface MirkoriPlayerSearchResult {
    data class Success(val players: List<MirkoriPublicPlayerProfile>) : MirkoriPlayerSearchResult

    data object Rejected : MirkoriPlayerSearchResult

    data object Unavailable : MirkoriPlayerSearchResult
}

sealed interface MirkoriLoginResult {
    data class BrowserReady(val connectUrl: String) : MirkoriLoginResult

    data class GoogleCredentialRequired(val nonce: String) : MirkoriLoginResult

    data class Connected(val accountState: MirkoriAccountState) : MirkoriLoginResult

    data object AlreadyConnected : MirkoriLoginResult

    data object ProfileConflict : MirkoriLoginResult

    data object Rejected : MirkoriLoginResult

    data object Unavailable : MirkoriLoginResult
}

interface SecureMirkoriStateStore {
    fun read(): MirkoriPersistedState?

    fun write(state: MirkoriPersistedState)

    fun clear()
}
