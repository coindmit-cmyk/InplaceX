package com.mirkori.inplacex.platform.mirkori

import com.mirkori.platform.sdk.GameIdentitySession
import com.mirkori.platform.sdk.PlatformApiException
import com.mirkori.platform.sdk.PlatformAuthMode
import com.mirkori.platform.sdk.PlatformProBenefitUnavailableException
import com.mirkori.platform.sdk.PlatformProConcurrencyLimitException
import com.mirkori.platform.sdk.PlatformProConfigurationUnavailableException
import com.mirkori.platform.sdk.PlatformProMembershipSnapshot
import com.mirkori.platform.sdk.PlatformProSessionLease
import com.mirkori.platform.sdk.PlatformProSessionLeaseStatus
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException

enum class MirkoriProAvailability {
    CACHED,
    READY,
    OFFLINE,
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

    fun cachedState(): MirkoriProAccessState = stateFromCache(MirkoriProAvailability.CACHED)

    suspend fun refresh(): MirkoriProAccessState = runtime.withOperationLock {
        try {
            refreshLocked()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PlatformProConfigurationUnavailableException) {
            failClosedLocked(error, MirkoriProNotice.CONFIGURATION_UNAVAILABLE)
        } catch (error: PlatformProBenefitUnavailableException) {
            failClosedLocked(error, MirkoriProNotice.MEMBERSHIP_INACTIVE)
        } catch (error: IllegalArgumentException) {
            failClosedLocked(error, MirkoriProNotice.INVALID_SNAPSHOT)
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
            val currentLease = stateFromCache(
                availability = MirkoriProAvailability.READY,
                notice = MirkoriProNotice.SESSION_ACTIVE,
            )
            if (currentLease.onlineSessionActive) return@withOperationLock currentLease
            val refreshed = refreshLocked()
            if (!refreshed.active) return@withOperationLock refreshed
            val session = requireLinkedSession()
            val installationId = requireNotNull(currentPersistedState()).installation.installationId
            require(session.installationId == null || session.installationId == installationId)
            val sessionId = sdk.newProSessionId()
            val idempotencyKey = sdk.newIdempotencyKey()
            val leaseResult = authenticated(session) { accessToken, current ->
                sdk.startProSession(
                    profileAccessToken = accessToken,
                    accountId = current.accountId,
                    installationId = installationId,
                    sessionId = sessionId,
                    idempotencyKey = idempotencyKey,
                )
            }
            activeLease = leaseResult.value
            stateFromCache(
                availability = MirkoriProAvailability.READY,
                notice = MirkoriProNotice.SESSION_ACTIVE,
                lease = leaseResult.value,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PlatformProConcurrencyLimitException) {
            logFailure(error)
            activeLease = null
            stateFromCache(MirkoriProAvailability.READY, MirkoriProNotice.CONCURRENCY_LIMIT)
        } catch (error: PlatformProConfigurationUnavailableException) {
            failClosedLocked(error, MirkoriProNotice.CONFIGURATION_UNAVAILABLE)
        } catch (error: PlatformProBenefitUnavailableException) {
            failClosedLocked(error, MirkoriProNotice.MEMBERSHIP_INACTIVE)
        } catch (error: IllegalArgumentException) {
            failClosedLocked(error, MirkoriProNotice.INVALID_SNAPSHOT)
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
            ?: return@withOperationLock stateFromCache(MirkoriProAvailability.READY)
        try {
            val session = requireLinkedSession()
            val installationId = requireNotNull(currentPersistedState()).installation.installationId
            require(session.accountId == lease.accountId && installationId == lease.installationId)
            val idempotencyKey = sdk.newIdempotencyKey()
            val heartbeat = authenticated(session) { accessToken, _ ->
                sdk.heartbeatProSession(accessToken, lease, idempotencyKey)
            }.value
            activeLease = heartbeat
            stateFromCache(
                availability = MirkoriProAvailability.READY,
                notice = MirkoriProNotice.SESSION_ACTIVE,
                lease = heartbeat,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PlatformProConfigurationUnavailableException) {
            failClosedLocked(error, MirkoriProNotice.CONFIGURATION_UNAVAILABLE)
        } catch (error: PlatformProBenefitUnavailableException) {
            failClosedLocked(error, MirkoriProNotice.MEMBERSHIP_INACTIVE)
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
        val lease = activeLease
            ?: return@withOperationLock stateFromCache(MirkoriProAvailability.READY)
        try {
            val session = requireLinkedSession()
            val installationId = requireNotNull(currentPersistedState()).installation.installationId
            require(session.accountId == lease.accountId && installationId == lease.installationId)
            val idempotencyKey = sdk.newIdempotencyKey()
            authenticated(session) { accessToken, _ ->
                sdk.releaseProSession(accessToken, lease, idempotencyKey)
            }
            activeLease = null
            stateFromCache(MirkoriProAvailability.READY, MirkoriProNotice.SESSION_RELEASED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logFailure(error)
            activeLease = null
            stateFromCache(
                availability = if (error is IOException) {
                    MirkoriProAvailability.OFFLINE
                } else {
                    MirkoriProAvailability.UNAVAILABLE
                },
            )
        }
    }

    private suspend fun MirkoriPlatformRuntime.refreshLocked(): MirkoriProAccessState {
        val session = ensureFreshSession()
        if (session.authMode == PlatformAuthMode.GUEST) {
            activeLease = null
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
                trustedServerTimeFloor = existing?.trustedTimeAnchor?.serverEpochMs?.let(Instant::ofEpochMilli),
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
        clearConfirmedLocked()
        return MirkoriProAccessState(MirkoriProAvailability.UNAVAILABLE, active = false, notice = notice)
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

private class MirkoriProProfileChangedException : IllegalStateException("Mirkori Pro profile changed")

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
