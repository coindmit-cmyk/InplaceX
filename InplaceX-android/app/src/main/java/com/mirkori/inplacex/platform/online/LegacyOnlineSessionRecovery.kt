package com.mirkori.inplacex.platform.online

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Одноразовый мост только для незавершённого матча старой InplaceX identity.
 * Обычный online runtime после подтверждённого переноса использует только Platform token.
 */
class LegacyOnlineSessionRecovery(
    private val duel: OnlineDuelClient,
    private val legacyStore: SecureGuestSessionStore,
    private val attemptStore: LegacyMembershipMigrationAttemptStore,
) {
    private val mutex = Mutex()

    suspend fun readSession(
        sessionId: String,
    ): OnlineClientResult<OnlineDuelSnapshotState> = mutex.withLock {
        val current = duel.readSession(sessionId)
        if (current is OnlineClientResult.Success) {
            clearCompletedAttempt(sessionId)
            return@withLock current
        }
        if (current != OnlineClientResult.MembershipRejected) return@withLock current

        val legacySession = legacyStore.read() ?: return@withLock current
        val attempt = attemptStore.read()?.let { pending ->
            if (pending.sessionId != sessionId) return@withLock current
            pending
        } ?: LegacyMembershipMigrationAttempt(
            sessionId = sessionId,
            commandId = UUID.randomUUID().toString(),
        ).also(attemptStore::write)

        return@withLock when (
            duel.migrateLegacyMembership(
                sessionId = sessionId,
                commandId = attempt.commandId,
                legacyRefreshToken = legacySession.refreshToken,
            )
        ) {
            is OnlineClientResult.Success -> confirmTransferredSession(sessionId)
            OnlineClientResult.AuthenticationRequired -> OnlineClientResult.AuthenticationRequired
            OnlineClientResult.MembershipRejected -> {
                clearCompletedAttempt(sessionId)
                OnlineClientResult.MembershipRejected
            }
            OnlineClientResult.RevisionConflict -> OnlineClientResult.RevisionConflict
            OnlineClientResult.Offline -> OnlineClientResult.Offline
            OnlineClientResult.TemporarilyUnavailable,
            OnlineClientResult.InvalidResponse,
            -> OnlineClientResult.TemporarilyUnavailable
        }
    }

    private suspend fun confirmTransferredSession(
        sessionId: String,
    ): OnlineClientResult<OnlineDuelSnapshotState> = when (val confirmed = duel.readSession(sessionId)) {
        is OnlineClientResult.Success -> confirmed.also { clearCompletedAttempt(sessionId) }
        OnlineClientResult.Offline -> OnlineClientResult.Offline
        OnlineClientResult.AuthenticationRequired,
        OnlineClientResult.MembershipRejected,
        OnlineClientResult.RevisionConflict,
        OnlineClientResult.TemporarilyUnavailable,
        OnlineClientResult.InvalidResponse,
        -> OnlineClientResult.TemporarilyUnavailable
    }

    private fun clearCompletedAttempt(sessionId: String) {
        if (attemptStore.read()?.sessionId != sessionId) return
        legacyStore.clear()
        attemptStore.clear()
    }
}
