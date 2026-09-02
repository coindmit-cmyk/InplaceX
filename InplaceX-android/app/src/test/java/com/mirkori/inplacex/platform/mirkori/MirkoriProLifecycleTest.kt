package com.mirkori.inplacex.platform.mirkori

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MirkoriProLifecycleTest {
    @Test
    fun gameplayRefreshesStartsHeartbeatsAndReleasesOneLease() = runBlocking {
        val calls = mutableListOf<String>()
        val emitted = mutableListOf<MirkoriProAccessState>()
        var heartbeatWaits = 0
        val ready = readyState(leaseActive = false)
        val leased = readyState(leaseActive = true)

        runMirkoriProLifecycle(
            operations = MirkoriProLifecycleOperations(
                refresh = { calls += "refresh"; ready },
                startGameplaySession = { calls += "start"; leased },
                heartbeatGameplaySession = { calls += "heartbeat"; leased },
                releaseGameplaySession = {
                    calls += "release"
                    ready.copy(notice = MirkoriProNotice.SESSION_RELEASED)
                },
            ),
            gameplayActive = true,
            awaitRetryOrHeartbeat = { heartbeatWaits++ == 0 },
            onState = emitted::add,
        )

        assertEquals(listOf("refresh", "start", "heartbeat", "release"), calls)
        assertEquals(MirkoriProNotice.SESSION_RELEASED, emitted.last().notice)
    }

    @Test
    fun offlineGameplayKeepsSignedAccessWithoutClaimingServerCapacity() = runBlocking {
        val calls = mutableListOf<String>()
        val offline = readyState(leaseActive = false).copy(
            availability = MirkoriProAvailability.OFFLINE,
        )

        runMirkoriProLifecycle(
            operations = MirkoriProLifecycleOperations(
                refresh = { calls += "refresh"; offline },
                startGameplaySession = { error("must not start offline") },
                heartbeatGameplaySession = { error("must not heartbeat offline") },
                releaseGameplaySession = { error("must not release absent lease") },
            ),
            gameplayActive = true,
            awaitRetryOrHeartbeat = { false },
            onState = {},
        )

        assertEquals(listOf("refresh"), calls)
    }

    private fun readyState(leaseActive: Boolean) = MirkoriProAccessState(
        availability = MirkoriProAvailability.READY,
        active = true,
        validUntilEpochMs = 2_000L,
        onlineSessionActive = leaseActive,
    )
}
