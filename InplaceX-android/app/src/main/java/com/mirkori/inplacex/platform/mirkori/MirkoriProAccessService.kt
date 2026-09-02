package com.mirkori.inplacex.platform.mirkori

import com.mirkori.platform.sdk.GameIdentitySession
import com.mirkori.platform.sdk.PlatformApiException
import com.mirkori.platform.sdk.PlatformAuthMode
import com.mirkori.platform.sdk.PlatformIdempotencyKey
import com.mirkori.platform.sdk.PlatformProBenefitUnavailableException
import com.mirkori.platform.sdk.PlatformProBenefitUnavailableReason
import com.mirkori.platform.sdk.PlatformProConcurrencyLimitException
import com.mirkori.platform.sdk.PlatformProConfigurationUnavailableException
import com.mirkori.platform.sdk.PlatformProMembershipSnapshot
import com.mirkori.platform.sdk.PlatformProSessionLease
import com.mirkori.platform.sdk.PlatformProSessionLeaseStatus
import com.mirkori.platform.sdk.PlatformRecoveryAction
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException

enum class MirkoriProAvailability {
    CACHED,
    READY,
    OFFLINE,
    RETRYABLE,
    SIGN_IN_REQUIRED,
    UNAVAILABLE,
}

enum class MirkoriProNotice {
    NONE,
    MEMBERSHIP_INACTIVE,
    SESSION_ACTIVE,
    SESSION_RELEASED,
    CONCURRENCY_LIMIT,
    CONFIGURATION_UNAVAILABLE,
    INVALID_SNAPSHOT,
}

data class MirkoriProAccessState(
    val availability: MirkoriProAvailability,
    val active: Boolean,
    val validUntilEpochMs: Long? = null,
    val nextAccessExpiryDelayMs: Long? = null,
    val benefitContentId: String? = null,
    val onlineSessionActive: Boolean = false,
    val leaseExpiresAtEpochMs: Long? = null,
    val maxConcurrentSessions: Int? = null,
    val notice: MirkoriProNotice = MirkoriProNotice.NONE,
) {
    companion object {
        val Unavailable = MirkoriProAccessState(MirkoriProAvailability.UNAVAILABLE, active = false)
    }
}

class MirkoriProAccessService(
    private val runtime: MirkoriPlatformRuntime,
) {
    @Volatile
    private var activeLease: PlatformProSessionLease? = null

    @Volatile
    private var pendingStart: PendingProSessionStart? = null

    @Volatile
    private var pendingRelease: PendingProSessionRelease? = null

    @Volatile
    private var pendingHeartbeat: PendingProSessionHeartbeat? = null

    fun cachedState(): MirkoriProAccessState = stateFromCache(MirkoriProAvailability.CACHED)

    suspend fun refresh(): MirkoriProAccessState = runtime.withOperationLock {
        try {
            refreshLocked()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PlatformProConfigurationUnavailableException) {
            handleConfigurationUnavailableLocked(error)
        } catch (error: PlatformProBenefitUnavailableException) {
            handleBenefitUnavailableLocked(error, allowLeaseReacquire = false)
        } catch (error: IllegalArgumentException) {
            failClosedLocked(error, MirkoriProNotice.INVALID_SNAPSHOT)
        } catch (error: PlatformApiException) {
            logFailure(error)
            stateFromCache(error.proAvailability())
        } catch (error: IOException) {
            logFailure(error)
            stateFromCache(MirkoriProAvailability.OFFLINE)
        } catch (error: Exception) {
            logFailure(error)
            stateFromCache(MirkoriProAvailability.UNAVAILABLE)
        }
    }

    suspend fun startOnlineSession(): MirkoriProAccessState = runtime.withOperationLock {
        try {
            completePendingReleaseLocked()
            val currentLease = stateFromCache(
                availability = MirkoriProAvailability.READY,
                notice = MirkoriProNotice.SESSION_ACTIVE,
            )
            if (currentLease.onlineSessionActive) {
                pendingStart = null
                return@withOperationLock currentLease
            }
            val refreshed = refreshLocked()
            if (!refreshed.active) {
                pendingStart = null
                return@withOperationLock refreshed
            }
            val session = requireLinkedSession()
            val installationId = requireNotNull(currentPersistedState()).installation.installationId
            require(session.installationId == null || session.installationId == installationId)
            val attempt = pendingStart?.takeIf {
                it.accountId == session.accountId && it.installationId == installationId
            } ?: PendingProSessionStart(
                accountId = session.accountId,
                installationId = installationId,
                sessionId = sdk.newProSessionId(),
                idempotencyKey = sdk.newIdempotencyKey(),
            ).also { pendingStart = it }
            val leaseResult = authenticated(session) { accessToken, current ->
                sdk.startProSession(
                    profileAccessToken = accessToken,
                    accountId = current.accountId,
                    installationId = installationId,
                    sessionId = attempt.sessionId,
                    idempotencyKey = attempt.idempotencyKey,
                )
            }
            activeLease = leaseResult.value
            pendingStart = null
            stateFromCache(
                availability = MirkoriProAvailability.READY,
                notice = MirkoriProNotice.SESSION_ACTIVE,
                lease = leaseResult.value,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PlatformProConcurrencyLimitException) {
            handleConcurrencyLimitLocked(error)
        } catch (error: PlatformProConfigurationUnavailableException) {
            handleConfigurationUnavailableLocked(error)
        } catch (error: PlatformProBenefitUnavailableException) {
            handleBenefitUnavailableLocked(error, allowLeaseReacquire = false)
        } catch (error: IllegalArgumentException) {
            failClosedLocked(error, MirkoriProNotice.INVALID_SNAPSHOT)
        } catch (error: PlatformApiException) {
            logFailure(error)
            if (error.recoveryAction != PlatformRecoveryAction.RETRY_SAME_REQUEST) {
                pendingStart = null
            }
            stateFromCache(error.proAvailability(), lease = activeLease)
        } catch (error: IOException) {
            logFailure(error)
            stateFromCache(MirkoriProAvailability.OFFLINE, lease = activeLease)
        } catch (error: Exception) {
            logFailure(error)
            stateFromCache(MirkoriProAvailability.UNAVAILABLE, lease = activeLease)
        }
    }

    suspend fun heartbeatOnlineSession(): MirkoriProAccessState = runtime.withOperationLock {
        val lease = activeLease
            ?: return@withOperationLock stateFromCache(MirkoriProAvailability.READY).also {
                pendingHeartbeat = null
            }
        try {
            val session = requireLinkedSession()
            val installationId = requireNotNull(currentPersistedState()).installation.installationId
            require(session.accountId == lease.accountId && installationId == lease.installationId)
            val attempt = pendingHeartbeat?.takeIf { it.leaseId == lease.id }
                ?: PendingProSessionHeartbeat(lease.id, sdk.newIdempotencyKey()).also {
                    pendingHeartbeat = it
                }
            val heartbeat = authenticated(session) { accessToken, _ ->
                sdk.heartbeatProSession(accessToken, lease, attempt.idempotencyKey)
            }.value
            activeLease = heartbeat
            pendingHeartbeat = null
            stateFromCache(
                availability = MirkoriProAvailability.READY,
                notice = MirkoriProNotice.SESSION_ACTIVE,
                lease = heartbeat,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PlatformProConfigurationUnavailableException) {
            handleConfigurationUnavailableLocked(error)
        } catch (error: PlatformProBenefitUnavailableException) {
            handleBenefitUnavailableLocked(error, allowLeaseReacquire = true)
        } catch (error: PlatformApiException) {
            logFailure(error)
            if (error.recoveryAction == PlatformRecoveryAction.RETRY_SAME_REQUEST) {
                stateFromCache(MirkoriProAvailability.RETRYABLE, lease = activeLease)
            } else {
                activeLease = null
                pendingHeartbeat = null
                stateFromCache(MirkoriProAvailability.UNAVAILABLE, lease = null)
            }
        } catch (error: IOException) {
            logFailure(error)
            stateFromCache(MirkoriProAvailability.OFFLINE, lease = activeLease)
        } catch (error: Exception) {
            logFailure(error)
            activeLease = null
            stateFromCache(MirkoriProAvailability.UNAVAILABLE)
        }
    }

    suspend fun releaseOnlineSession(): MirkoriProAccessState = runtime.withOperationLock {
        val attempt = pendingRelease ?: activeLease?.let { lease ->
            PendingProSessionRelease(lease, sdk.newIdempotencyKey()).also { pendingRelease = it }
        }
            ?: return@withOperationLock stateFromCache(MirkoriProAvailability.READY)
        activeLease = null
        pendingHeartbeat = null
        try {
            completePendingReleaseLocked(attempt)
            stateFromCache(MirkoriProAvailability.READY, MirkoriProNotice.SESSION_RELEASED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PlatformProConfigurationUnavailableException) {
            handleConfigurationUnavailableLocked(error)
        } catch (error: PlatformProBenefitUnavailableException) {
            handleBenefitUnavailableLocked(error, allowLeaseReacquire = false)
        } catch (error: PlatformApiException) {
            logFailure(error)
            stateFromCache(error.proAvailability(), lease = null)
        } catch (error: Exception) {
            logFailure(error)
            stateFromCache(
                availability = if (error is IOException) {
                    MirkoriProAvailability.OFFLINE
                } else {
                    MirkoriProAvailability.UNAVAILABLE
                },
            )
        }
    }

    private suspend fun MirkoriPlatformRuntime.completePendingReleaseLocked(
        expected: PendingProSessionRelease? = pendingRelease,
    ) {
        val attempt = expected ?: return
        val session = requireLinkedSession()
        val installationId = requireNotNull(currentPersistedState()).installation.installationId
        if (session.accountId != attempt.lease.accountId || installationId != attempt.lease.installationId) {
            pendingRelease = null
            return
        }
        try {
            authenticated(session) { accessToken, _ ->
                sdk.releaseProSession(accessToken, attempt.lease, attempt.idempotencyKey)
            }
            pendingRelease = null
        } catch (error: PlatformProBenefitUnavailableException) {
            if (
                error.recoveryAction == PlatformRecoveryAction.RETRY_SAME_REQUEST ||
                error.reason != PlatformProBenefitUnavailableReason.LEASE
            ) {
                throw error
            }
            logFailure(error)
            pendingRelease = null
        }
    }

    private suspend fun MirkoriPlatformRuntime.refreshLocked(): MirkoriProAccessState {
        val session = ensureFreshSession()
        if (session.authMode == PlatformAuthMode.GUEST) {
            activeLease = null
            pendingStart = null
            clearConfirmedLocked()
            return MirkoriProAccessState(
                availability = MirkoriProAvailability.SIGN_IN_REQUIRED,
                active = false,
            )
        }
        val existing = currentPersistedState()?.confirmedProAccess?.takeIf {
            it.belongsTo(session, sdk.config.distributionId)
        }
        val snapshotResult = authenticated(session) { accessToken, current ->
            sdk.proMembershipSnapshot(
                profileAccessToken = accessToken,
                accountId = current.accountId,
                trustedServerTimeFloor = existing?.trustedTimeAnchor
                    ?.let(::trustedNowMs)
                    ?.let(Instant::ofEpochMilli),
            )
        }
        val snapshot = snapshotResult.value
        val anchor = requireNotNull(newTrustedTimeAnchor(snapshot.trustedServerTime.toEpochMilli()))
        val confirmed = snapshot.toConfirmed(snapshotResult.session, anchor)
        val state = requireNotNull(currentPersistedState())
        persist(state.copy(confirmedProAccess = confirmed))
        return stateFromCache(
            availability = MirkoriProAvailability.READY,
            notice = if (confirmed.active) MirkoriProNotice.NONE else MirkoriProNotice.MEMBERSHIP_INACTIVE,
        )
    }

    private suspend fun <T> MirkoriPlatformRuntime.authenticated(
        initialSession: GameIdentitySession,
        request: suspend (String, GameIdentitySession) -> T,
    ): ProAuthenticatedResult<T> {
        var session = initialSession
        return try {
            ProAuthenticatedResult(session, request(session.credentials.accessToken, session))
        } catch (error: PlatformApiException) {
            if (error.status != 401) throw error
            val accountId = session.accountId
            val playerId = session.gamePlayerId
            session = ensureFreshSession(forceRefresh = true)
            if (session.accountId != accountId || session.gamePlayerId != playerId ||
                session.authMode == PlatformAuthMode.GUEST
            ) {
                throw MirkoriProProfileChangedException()
            }
            ProAuthenticatedResult(session, request(session.credentials.accessToken, session))
        }
    }

    private fun MirkoriPlatformRuntime.requireLinkedSession(): GameIdentitySession =
        requireNotNull(currentPersistedState()?.session).also { session ->
            require(session.authMode != PlatformAuthMode.GUEST)
        }

    private fun MirkoriPlatformRuntime.failClosedLocked(
        error: Throwable,
        notice: MirkoriProNotice,
    ): MirkoriProAccessState {
        logFailure(error)
        activeLease = null
        pendingStart = null
        pendingRelease = null
        pendingHeartbeat = null
        clearConfirmedLocked()
        return MirkoriProAccessState(MirkoriProAvailability.UNAVAILABLE, active = false, notice = notice)
    }

    private fun MirkoriPlatformRuntime.handleConfigurationUnavailableLocked(
        error: PlatformProConfigurationUnavailableException,
    ): MirkoriProAccessState =
        if (error.recoveryAction == PlatformRecoveryAction.RETRY_SAME_REQUEST) {
            logFailure(error)
            stateFromCache(MirkoriProAvailability.RETRYABLE, lease = activeLease)
        } else {
            failClosedLocked(error, MirkoriProNotice.CONFIGURATION_UNAVAILABLE)
        }

    private fun MirkoriPlatformRuntime.handleBenefitUnavailableLocked(
        error: PlatformProBenefitUnavailableException,
        allowLeaseReacquire: Boolean,
    ): MirkoriProAccessState =
        if (error.recoveryAction == PlatformRecoveryAction.RETRY_SAME_REQUEST) {
            logFailure(error)
            stateFromCache(MirkoriProAvailability.RETRYABLE, lease = activeLease)
        } else if (error.reason == PlatformProBenefitUnavailableReason.LEASE && allowLeaseReacquire) {
            logFailure(error)
            activeLease = null
            pendingHeartbeat = null
            stateFromCache(MirkoriProAvailability.RETRYABLE, lease = null)
        } else if (error.reason == PlatformProBenefitUnavailableReason.LEASE) {
            logFailure(error)
            activeLease = null
            pendingStart = null
            pendingHeartbeat = null
            stateFromCache(MirkoriProAvailability.UNAVAILABLE, lease = null)
        } else {
            failClosedLocked(error, MirkoriProNotice.MEMBERSHIP_INACTIVE)
        }

    private fun MirkoriPlatformRuntime.handleConcurrencyLimitLocked(
        error: PlatformProConcurrencyLimitException,
    ): MirkoriProAccessState =
        if (error.recoveryAction == PlatformRecoveryAction.RETRY_SAME_REQUEST) {
            logFailure(error)
            stateFromCache(MirkoriProAvailability.RETRYABLE, lease = activeLease)
        } else {
            logFailure(error)
            activeLease = null
            pendingStart = null
            pendingHeartbeat = null
            stateFromCache(MirkoriProAvailability.READY, MirkoriProNotice.CONCURRENCY_LIMIT)
        }

    private fun MirkoriPlatformRuntime.clearConfirmedLocked() {
        currentPersistedState()?.takeIf { it.confirmedProAccess != null }?.let { state ->
            persist(state.copy(confirmedProAccess = null))
        }
    }

    private fun stateFromCache(
        availability: MirkoriProAvailability,
        notice: MirkoriProNotice = MirkoriProNotice.NONE,
        lease: PlatformProSessionLease? = activeLease,
    ): MirkoriProAccessState {
        val state = runtime.currentPersistedState() ?: return MirkoriProAccessState.Unavailable
        val session = state.session
        val confirmed = state.confirmedProAccess?.takeIf { pro ->
            session != null && pro.belongsTo(session, runtime.sdk.config.distributionId)
        } ?: return MirkoriProAccessState(availability, active = false, notice = notice)
        val trustedNow = runtime.trustedNowMs(confirmed.trustedTimeAnchor)
        val active = confirmed.activeAt(trustedNow)
        val activeServerLease = lease?.takeIf {
            active && it.status == PlatformProSessionLeaseStatus.ACTIVE &&
                it.accountId == confirmed.accountId &&
                trustedNow != null && it.expiresAt.toEpochMilli() > trustedNow
        }
        return MirkoriProAccessState(
            availability = availability,
            active = active,
            validUntilEpochMs = confirmed.validUntilEpochMs,
            nextAccessExpiryDelayMs = if (active && trustedNow != null) {
                minOf(
                    requireNotNull(confirmed.validUntilEpochMs),
                    confirmed.snapshotExpiresAtEpochMs,
                ).minus(trustedNow).coerceAtLeast(0L)
            } else {
                null
            },
            benefitContentId = confirmed.benefitContentId,
            onlineSessionActive = activeServerLease != null,
            leaseExpiresAtEpochMs = activeServerLease?.expiresAt?.toEpochMilli(),
            maxConcurrentSessions = activeServerLease?.maxConcurrentSessions,
            notice = notice,
        )
    }
}

private data class ProAuthenticatedResult<T>(
    val session: GameIdentitySession,
    val value: T,
)

private data class PendingProSessionStart(
    val accountId: String,
    val installationId: String,
    val sessionId: String,
    val idempotencyKey: PlatformIdempotencyKey,
)

private data class PendingProSessionRelease(
    val lease: PlatformProSessionLease,
    val idempotencyKey: PlatformIdempotencyKey,
)

private data class PendingProSessionHeartbeat(
    val leaseId: String,
    val idempotencyKey: PlatformIdempotencyKey,
)

private class MirkoriProProfileChangedException : IllegalStateException("Mirkori Pro profile changed")

private fun PlatformApiException.proAvailability(): MirkoriProAvailability =
    if (recoveryAction == PlatformRecoveryAction.RETRY_SAME_REQUEST) {
        MirkoriProAvailability.RETRYABLE
    } else {
        MirkoriProAvailability.UNAVAILABLE
    }

private fun ConfirmedMirkoriProAccess.belongsTo(
    session: GameIdentitySession,
    expectedDistributionId: String?,
): Boolean = accountId == session.accountId && gamePlayerId == session.gamePlayerId &&
    expectedDistributionId != null && distributionId == expectedDistributionId

private fun PlatformProMembershipSnapshot.toConfirmed(
    session: GameIdentitySession,
    anchor: MirkoriTrustedTimeAnchor,
): ConfirmedMirkoriProAccess {
    require(accountId == session.accountId && gameId == session.gameId)
    return ConfirmedMirkoriProAccess(
        accountId = accountId,
        gamePlayerId = session.gamePlayerId,
        distributionId = distributionId,
        active = active,
        validUntilEpochMs = validUntil?.toEpochMilli(),
        snapshotExpiresAtEpochMs = expiresAt.toEpochMilli(),
        membershipVersion = membershipVersion,
        participationVersion = participationVersion,
        benefitContentId = benefitContentId,
        policyVersion = policyVersion,
        trustedTimeAnchor = anchor,
    )
}
