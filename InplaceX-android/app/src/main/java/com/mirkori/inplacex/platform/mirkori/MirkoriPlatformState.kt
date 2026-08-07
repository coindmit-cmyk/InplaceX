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
) {
    override fun toString(): String = "PendingMirkoriPurchase(productId=$productId, [redacted])"
}

data class MirkoriFeatureGrant(
    val active: Boolean,
    val validUntilEpochMs: Long? = null,
) {
    init {
        require(active || validUntilEpochMs == null)
    }

    fun activeAt(nowMs: Long): Boolean = active && (validUntilEpochMs == null || validUntilEpochMs > nowMs)
}

data class ConfirmedMirkoriEntitlements(
    val accountId: String,
    val gamePlayerId: String,
    val confirmedAtEpochMs: Long,
    val removeAds: MirkoriFeatureGrant,
    val pro: MirkoriFeatureGrant,
    val proPlus: MirkoriFeatureGrant,
) {
    fun nextExpiryAfter(nowMs: Long): Long? =
        listOfNotNull(
            removeAds.validUntilEpochMs,
            pro.validUntilEpochMs,
            proPlus.validUntilEpochMs,
        ).filter { it > nowMs }.minOrNull()

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
)

sealed interface MirkoriLoginResult {
    data class BrowserReady(val connectUrl: String) : MirkoriLoginResult

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
