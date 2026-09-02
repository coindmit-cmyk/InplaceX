package com.mirkori.inplacex.platform.mirkori

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class MirkoriProLifecycleOperations(
    val refresh: suspend () -> MirkoriProAccessState,
    val startGameplaySession: suspend () -> MirkoriProAccessState,
    val heartbeatGameplaySession: suspend () -> MirkoriProAccessState,
    val releaseGameplaySession: suspend () -> MirkoriProAccessState,
)

internal fun MirkoriProAccessService.lifecycleOperations(): MirkoriProLifecycleOperations =
    MirkoriProLifecycleOperations(
        refresh = ::refresh,
        startGameplaySession = ::startOnlineSession,
        heartbeatGameplaySession = ::heartbeatOnlineSession,
        releaseGameplaySession = ::releaseOnlineSession,
    )

internal suspend fun runMirkoriProLifecycle(
    operations: MirkoriProLifecycleOperations,
    gameplayActive: Boolean,
    awaitRetryOrHeartbeat: suspend () -> Boolean = {
        delay(MirkoriProHeartbeatIntervalMs)
        true
    },
    onState: (MirkoriProAccessState) -> Unit,
) {
    var state = operations.refresh()
    onState(state)
    if (!gameplayActive) return

    var leaseHeld = false
    var releaseRequired = false
    try {
        while (state.active || state.availability == MirkoriProAvailability.RETRYABLE) {
            if (leaseHeld) {
                if (!awaitRetryOrHeartbeat()) return
                state = operations.heartbeatGameplaySession()
                leaseHeld = state.onlineSessionActive
                onState(state)
                continue
            }
            if (state.availability == MirkoriProAvailability.READY) {
                state = operations.startGameplaySession()
                leaseHeld = state.onlineSessionActive
                releaseRequired = releaseRequired || leaseHeld
                onState(state)
                if (state.notice == MirkoriProNotice.CONCURRENCY_LIMIT) return
                if (leaseHeld) continue
            } else if (
                state.availability != MirkoriProAvailability.OFFLINE &&
                state.availability != MirkoriProAvailability.RETRYABLE
            ) {
                return
            }
            if (!awaitRetryOrHeartbeat()) return
            state = operations.refresh()
            onState(state)
        }
    } finally {
        if (releaseRequired) {
            withContext(NonCancellable) {
                onState(operations.releaseGameplaySession())
            }
        }
    }
}

private const val MirkoriProHeartbeatIntervalMs = 30_000L
