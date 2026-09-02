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

    @Test
    fun concurrencyRejectionEndsTheCurrentGameplayLifecycleWithoutRetry() = runBlocking {
        val calls = mutableListOf<String>()
        val limited = readyState(leaseActive = false).copy(
            notice = MirkoriProNotice.CONCURRENCY_LIMIT,
        )

        runMirkoriProLifecycle(
            operations = MirkoriProLifecycleOperations(
                refresh = { calls += "refresh"; readyState(leaseActive = false) },
                startGameplaySession = { calls += "start"; limited },
                heartbeatGameplaySession = { error("must not heartbeat rejected lease") },
                releaseGameplaySession = { error("must not release rejected lease") },
            ),
            gameplayActive = true,
            awaitRetryOrHeartbeat = { error("must not retry concurrency rejection") },
            onState = {},
        )

        assertEquals(listOf("refresh", "start"), calls)
    }

    @Test
    fun retryablePlatformFailureRefreshesAndClaimsWithinTheSameGameplayLifecycle() = runBlocking {
        val calls = mutableListOf<String>()
        var refreshCount = 0
        var waits = 0
        val retryable = readyState(leaseActive = false).copy(
            availability = MirkoriProAvailability.RETRYABLE,
        )

        runMirkoriProLifecycle(
            operations = MirkoriProLifecycleOperations(
                refresh = {
                    calls += "refresh"
                    if (refreshCount++ == 0) retryable else readyState(leaseActive = false)
                },
                startGameplaySession = { calls += "start"; readyState(leaseActive = true) },
                heartbeatGameplaySession = { error("heartbeat wait ends the test") },
                releaseGameplaySession = {
                    calls += "release"
                    readyState(leaseActive = false)
                },
            ),
            gameplayActive = true,
            awaitRetryOrHeartbeat = { waits++ == 0 },
            onState = {},
        )

        assertEquals(listOf("refresh", "refresh", "start", "release"), calls)
    }

    private fun readyState(leaseActive: Boolean) = MirkoriProAccessState(
        availability = MirkoriProAvailability.READY,
        active = true,
        validUntilEpochMs = 2_000L,
        onlineSessionActive = leaseActive,
    )
}
