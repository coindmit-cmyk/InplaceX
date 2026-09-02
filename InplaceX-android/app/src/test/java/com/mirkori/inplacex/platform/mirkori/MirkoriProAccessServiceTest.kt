package com.mirkori.inplacex.platform.mirkori

import com.mirkori.platform.sdk.GameIdentitySession
import com.mirkori.platform.sdk.InstallationIdentity
import com.mirkori.platform.sdk.MirkoriGameSdk
import com.mirkori.platform.sdk.MirkoriGameSdkConfig
import com.mirkori.platform.sdk.PlatformAuthMode
import com.mirkori.platform.sdk.PlatformCredentials
import com.mirkori.platform.sdk.PlatformHttpRequest
import com.mirkori.platform.sdk.PlatformHttpResponse
import com.mirkori.platform.sdk.PlatformProMembershipSnapshotVerifier
import com.mirkori.platform.sdk.PlatformTransport
import com.mirkori.platform.sdk.Rs256PlatformProSnapshotSignatureVerifier
import com.mirkori.platform.sdk.SecureEntropy
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirkoriProAccessServiceTest {
    @Test
    fun refreshPersistsVerifiedExpiryAndCachedAccessFailsClosedAfterReboot() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val transport = RuntimeProTransport(
            { PlatformHttpResponse(200, snapshotEnvelope(keys.private)) },
            { PlatformHttpResponse(200, snapshotEnvelope(keys.private)) },
        )
        val store = ProMemoryStore(linkedState())
        var monotonicMs = 10_000L
        var bootMarker = 7L
        val service = service(keys.public, transport, store, { monotonicMs }, { bootMarker })

        val refreshed = runProRuntime { service.refresh() }

        assertEquals(MirkoriProAvailability.READY, refreshed.availability)
        assertTrue(refreshed.active)
        assertEquals(ValidUntil.toEpochMilli(), refreshed.validUntilEpochMs)
        assertEquals(3_600_000L, refreshed.nextAccessExpiryDelayMs)
        assertEquals("inplacex-pro-v3", refreshed.benefitContentId)
        assertNotNull(store.value?.confirmedProAccess)
        monotonicMs += 1_000L
        val refreshedAgain = runProRuntime { service.refresh() }
        assertTrue(refreshedAgain.active)
        assertEquals(
            ServerTime.plusMillis(1_000L).toEpochMilli(),
            store.value?.confirmedProAccess?.trustedTimeAnchor?.serverEpochMs,
        )

        bootMarker += 1L
        assertFalse(service.cachedState().active)
    }

    @Test
    fun startsHeartbeatsAndReleasesOneExactOnlineLease() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        var sessionId = ""
        val transport = RuntimeProTransport(
            { PlatformHttpResponse(200, snapshotEnvelope(keys.private)) },
            { PlatformHttpResponse(401, """{"error":"pro_unavailable"}""") },
            { PlatformHttpResponse(200, refreshedCredentialsJson()) },
            { PlatformHttpResponse(503, """{"error":"provider_unavailable"}""") },
            { PlatformHttpResponse(200, snapshotEnvelope(keys.private)) },
            { throw IOException("lease response lost after acceptance") },
            { PlatformHttpResponse(200, snapshotEnvelope(keys.private)) },
            { request ->
                sessionId = requireNotNull(SessionIdPattern.find(request.body)?.groupValues?.get(1))
                PlatformHttpResponse(201, leaseJson(sessionId, "active"))
            },
            { PlatformHttpResponse(200, leaseJson(sessionId, "active", heartbeatOffsetSeconds = 20)) },
            { PlatformHttpResponse(200, leaseJson(sessionId, "released", heartbeatOffsetSeconds = 20)) },
        )
        val service = service(keys.public, transport, ProMemoryStore(linkedState()))

        val retryable = runProRuntime { service.startOnlineSession() }
        val ambiguous = runProRuntime { service.startOnlineSession() }
        val started = runProRuntime { service.startOnlineSession() }
        val duplicateStart = runProRuntime { service.startOnlineSession() }
        val heartbeat = runProRuntime { service.heartbeatOnlineSession() }
        val released = runProRuntime { service.releaseOnlineSession() }

        assertEquals(MirkoriProAvailability.RETRYABLE, retryable.availability)
        assertTrue(retryable.active)
        assertEquals(MirkoriProAvailability.OFFLINE, ambiguous.availability)
        assertTrue(ambiguous.active)
        assertTrue(started.active)
        assertTrue(started.onlineSessionActive)
        assertEquals(2, started.maxConcurrentSessions)
        assertTrue(duplicateStart.onlineSessionActive)
        assertEquals(MirkoriProNotice.SESSION_ACTIVE, heartbeat.notice)
        assertTrue(heartbeat.onlineSessionActive)
        assertEquals(MirkoriProNotice.SESSION_RELEASED, released.notice)
        assertFalse(released.onlineSessionActive)
        assertEquals(10, transport.requests.size)
        assertTrue(transport.requests[0].url.endsWith("/api/v1/pro/snapshot?distributionId=rf-mirkori"))
        assertTrue(transport.requests[1].url.endsWith("/api/v1/pro/leases"))
        assertTrue(transport.requests[2].url.endsWith("/api/v1/auth/refresh"))
        assertTrue(transport.requests[3].url.endsWith("/api/v1/pro/leases"))
        assertTrue(transport.requests[4].url.endsWith("/api/v1/pro/snapshot?distributionId=rf-mirkori"))
        assertTrue(transport.requests[5].url.endsWith("/api/v1/pro/leases"))
        assertTrue(transport.requests[6].url.endsWith("/api/v1/pro/snapshot?distributionId=rf-mirkori"))
        assertTrue(transport.requests[7].url.endsWith("/api/v1/pro/leases"))
        assertTrue(transport.requests[8].url.endsWith("/api/v1/pro/leases/$LeaseId/heartbeat"))
        assertTrue(transport.requests[9].url.endsWith("/api/v1/pro/leases/$LeaseId/release"))
        assertEquals(transport.requests[1].body, transport.requests[3].body)
        assertEquals(transport.requests[1].body, transport.requests[5].body)
        assertEquals(transport.requests[1].body, transport.requests[7].body)
        assertEquals(
            transport.requests[1].headers["Idempotency-Key"],
            transport.requests[3].headers["Idempotency-Key"],
        )
        assertEquals(
            transport.requests[1].headers["Idempotency-Key"],
            transport.requests[5].headers["Idempotency-Key"],
        )
        assertEquals(
            transport.requests[1].headers["Idempotency-Key"],
            transport.requests[7].headers["Idempotency-Key"],
        )
        assertTrue(listOf(1, 3, 5, 7, 8, 9).all { index ->
            transport.requests[index].body.contains("\"sessionId\":\"$sessionId\"")
        })
        assertTrue(listOf(1, 3, 5, 7, 8, 9).all { index ->
            !transport.requests[index].headers["Idempotency-Key"].isNullOrBlank()
        })
    }

    @Test
    fun unavailableHeartbeatDropsOnlyLeaseAndKeepsVerifiedMembershipForRetry() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        var sessionId = ""
        val transport = RuntimeProTransport(
            { PlatformHttpResponse(200, snapshotEnvelope(keys.private)) },
            { request ->
                sessionId = requireNotNull(SessionIdPattern.find(request.body)?.groupValues?.get(1))
                PlatformHttpResponse(201, leaseJson(sessionId, "active"))
            },
            { PlatformHttpResponse(409, """{"error":"pro_lease_unavailable"}""") },
        )
        val store = ProMemoryStore(linkedState())
        val service = service(keys.public, transport, store)

        val started = runProRuntime { service.startOnlineSession() }
        val unavailable = runProRuntime { service.heartbeatOnlineSession() }

        assertTrue(started.onlineSessionActive)
        assertEquals(MirkoriProAvailability.RETRYABLE, unavailable.availability)
        assertTrue(unavailable.active)
        assertFalse(unavailable.onlineSessionActive)
        assertNotNull(store.value?.confirmedProAccess)
    }

    @Test
    fun nonRetryableLeaseLossDuringStartEndsTheAttempt() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val service = service(
            keys.public,
            RuntimeProTransport(
                { PlatformHttpResponse(200, snapshotEnvelope(keys.private)) },
                { PlatformHttpResponse(409, """{"error":"pro_lease_unavailable"}""") },
            ),
            ProMemoryStore(linkedState()),
        )

        val result = runProRuntime { service.startOnlineSession() }

        assertEquals(MirkoriProAvailability.UNAVAILABLE, result.availability)
        assertTrue(result.active)
        assertFalse(result.onlineSessionActive)
    }

    @Test
    fun unavailableMembershipHeartbeatFailsClosed() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        var sessionId = ""
        val store = ProMemoryStore(linkedState())
        val service = service(
            keys.public,
            RuntimeProTransport(
                { PlatformHttpResponse(200, snapshotEnvelope(keys.private)) },
                { request ->
                    sessionId = requireNotNull(SessionIdPattern.find(request.body)?.groupValues?.get(1))
                    PlatformHttpResponse(201, leaseJson(sessionId, "active"))
                },
                { PlatformHttpResponse(409, """{"error":"pro_unavailable"}""") },
            ),
            store,
        )

        runProRuntime { service.startOnlineSession() }
        val unavailable = runProRuntime { service.heartbeatOnlineSession() }

        assertEquals(MirkoriProAvailability.UNAVAILABLE, unavailable.availability)
        assertFalse(unavailable.active)
        assertEquals(MirkoriProNotice.MEMBERSHIP_INACTIVE, unavailable.notice)
        assertEquals(null, store.value?.confirmedProAccess)
    }

    @Test
    fun failedHeartbeatIsRetriedWithSameIdentity() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        var sessionId = ""
        val transport = RuntimeProTransport(
            { PlatformHttpResponse(200, snapshotEnvelope(keys.private)) },
            { request ->
                sessionId = requireNotNull(SessionIdPattern.find(request.body)?.groupValues?.get(1))
                PlatformHttpResponse(201, leaseJson(sessionId, "active"))
            },
            { throw IOException("heartbeat response lost") },
            { PlatformHttpResponse(200, leaseJson(sessionId, "active", heartbeatOffsetSeconds = 20)) },
        )
        val service = service(keys.public, transport, ProMemoryStore(linkedState()))

        runProRuntime { service.startOnlineSession() }
        val failed = runProRuntime { service.heartbeatOnlineSession() }
        val recovered = runProRuntime { service.heartbeatOnlineSession() }

        assertEquals(MirkoriProAvailability.OFFLINE, failed.availability)
        assertTrue(failed.onlineSessionActive)
        assertEquals(MirkoriProAvailability.READY, recovered.availability)
        assertTrue(recovered.onlineSessionActive)
        assertEquals(transport.requests[2].url, transport.requests[3].url)
        assertEquals(
            transport.requests[2].headers["Idempotency-Key"],
            transport.requests[3].headers["Idempotency-Key"],
        )
    }

    @Test
    fun nonRetryableHeartbeatDropsTheLeaseAndStopsRetrying() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        var sessionId = ""
        val transport = RuntimeProTransport(
            { PlatformHttpResponse(200, snapshotEnvelope(keys.private)) },
            { request ->
                sessionId = requireNotNull(SessionIdPattern.find(request.body)?.groupValues?.get(1))
                PlatformHttpResponse(201, leaseJson(sessionId, "active"))
            },
            { PlatformHttpResponse(403, """{"error":"forbidden"}""") },
            { PlatformHttpResponse(200, leaseJson(sessionId, "released")) },
        )
        val service = service(
            keys.public,
            transport,
            ProMemoryStore(linkedState()),
        )

        runProRuntime { service.startOnlineSession() }
        val result = runProRuntime { service.heartbeatOnlineSession() }
        val released = runProRuntime { service.releaseOnlineSession() }

        assertEquals(MirkoriProAvailability.UNAVAILABLE, result.availability)
        assertTrue(result.active)
        assertFalse(result.onlineSessionActive)
        assertEquals(MirkoriProNotice.SESSION_RELEASED, released.notice)
        assertTrue(service.cachedState().active)
        assertFalse(service.cachedState().onlineSessionActive)
        assertTrue(transport.requests[3].url.endsWith("/api/v1/pro/leases/$LeaseId/release"))
    }

    @Test
    fun retryableTypedReleaseIsRetriedWithSameIdentityBeforeNextClaim() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        var sessionId = ""
        val transport = RuntimeProTransport(
            { PlatformHttpResponse(200, snapshotEnvelope(keys.private)) },
            { request ->
                sessionId = requireNotNull(SessionIdPattern.find(request.body)?.groupValues?.get(1))
                PlatformHttpResponse(201, leaseJson(sessionId, "active"))
            },
            { PlatformHttpResponse(503, """{"error":"pro_lease_unavailable"}""") },
            { PlatformHttpResponse(200, leaseJson(sessionId, "released")) },
            { PlatformHttpResponse(200, snapshotEnvelope(keys.private)) },
            { request ->
                sessionId = requireNotNull(SessionIdPattern.find(request.body)?.groupValues?.get(1))
                PlatformHttpResponse(201, leaseJson(sessionId, "active"))
            },
        )
        val service = service(keys.public, transport, ProMemoryStore(linkedState()))

        val started = runProRuntime { service.startOnlineSession() }
        val failedRelease = runProRuntime { service.releaseOnlineSession() }
        val restarted = runProRuntime { service.startOnlineSession() }

        assertTrue(started.onlineSessionActive)
        assertEquals(MirkoriProAvailability.RETRYABLE, failedRelease.availability)
        assertTrue(restarted.onlineSessionActive)
        assertTrue(transport.requests[2].url.endsWith("/api/v1/pro/leases/$LeaseId/release"))
        assertEquals(transport.requests[2].url, transport.requests[3].url)
        assertEquals(
            transport.requests[2].headers["Idempotency-Key"],
            transport.requests[3].headers["Idempotency-Key"],
        )
        assertTrue(transport.requests[4].url.contains("/api/v1/pro/snapshot"))
        assertTrue(transport.requests[5].url.endsWith("/api/v1/pro/leases"))
    }

    @Test
    fun temporaryConfigurationFailureRetainsVerifiedMembershipForRetry() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val store = ProMemoryStore(linkedState())
        val service = service(
            keys.public,
            RuntimeProTransport(
                { PlatformHttpResponse(200, snapshotEnvelope(keys.private)) },
                { PlatformHttpResponse(503, """{"error":"pro_configuration_unavailable"}""") },
            ),
            store,
        )

        val result = runProRuntime { service.startOnlineSession() }

        assertEquals(MirkoriProAvailability.RETRYABLE, result.availability)
        assertTrue(result.active)
        assertFalse(result.onlineSessionActive)
        assertNotNull(store.value?.confirmedProAccess)
    }

    @Test
    fun temporaryTypedMembershipAndConcurrencyFailuresRemainRetryable() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        listOf("pro_unavailable", "pro_concurrency_limit").forEach { errorCode ->
            val store = ProMemoryStore(linkedState())
            val service = service(
                keys.public,
                RuntimeProTransport(
                    { PlatformHttpResponse(200, snapshotEnvelope(keys.private)) },
                    { PlatformHttpResponse(503, """{"error":"$errorCode"}""") },
                ),
                store,
            )

            val result = runProRuntime { service.startOnlineSession() }

            assertEquals(MirkoriProAvailability.RETRYABLE, result.availability)
            assertTrue(result.active)
            assertFalse(result.onlineSessionActive)
            assertNotNull(store.value?.confirmedProAccess)
        }
    }

    @Test
    fun reportsConcurrencyLimitWithoutClaimingAnOnlineSession() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val transport = RuntimeProTransport(
            { PlatformHttpResponse(200, snapshotEnvelope(keys.private)) },
            { PlatformHttpResponse(409, """{"error":"pro_concurrency_limit"}""") },
        )
        val service = service(keys.public, transport, ProMemoryStore(linkedState()))

        val result = runProRuntime { service.startOnlineSession() }

        assertTrue(result.active)
        assertFalse(result.onlineSessionActive)
        assertEquals(MirkoriProNotice.CONCURRENCY_LIMIT, result.notice)
    }

    private fun service(
        publicKey: java.security.PublicKey,
        transport: PlatformTransport,
        store: ProMemoryStore,
        monotonicClockMs: () -> Long = { 10_000L },
        bootMarker: () -> Long? = { 7L },
    ): MirkoriProAccessService {
        val sdk = MirkoriGameSdk(
            config = MirkoriGameSdkConfig(
                platformBaseUrl = "https://games.dmit.life",
                gameId = "inplacex",
                redirectUri = MirkoriPlatformRuntime.RedirectUri,
                distributionId = "rf-mirkori",
            ),
            transport = transport,
            entropy = ProEntropy,
            proSnapshotVerifier = PlatformProMembershipSnapshotVerifier(
                Rs256PlatformProSnapshotSignatureVerifier(mapOf(KeyId to publicKey)),
            ),
        )
        return MirkoriProAccessService(
            MirkoriPlatformRuntime(
                sdk = sdk,
                store = store,
                clockMs = { ServerTime.toEpochMilli() },
                monotonicClockMs = monotonicClockMs,
                bootMarker = bootMarker,
            ),
        )
    }

    private fun linkedState() = MirkoriPersistedState(
        installation = InstallationIdentity(InstallationId, "I".repeat(43)),
        session = GameIdentitySession(
            accountId = AccountId,
            gamePlayerId = PlayerId,
            gameId = "inplacex",
            installationId = null,
            authMode = PlatformAuthMode.LOCAL,
            credentials = PlatformCredentials(
                accessToken = "linked.${"a".repeat(43)}",
                refreshToken = "linked-${"r".repeat(43)}",
                accessExpiresAt = ServerTime.plusSeconds(3_600),
                refreshExpiresAt = ServerTime.plusSeconds(86_400),
            ),
        ),
    )

    private fun snapshotEnvelope(privateKey: PrivateKey): String {
        val payload = """{"schemaVersion":1,"type":"mirkori.pro.game-membership","accountId":"$AccountId","gameId":"inplacex","distributionId":"rf-mirkori","participating":true,"active":true,"validUntil":"$ValidUntil","membershipVersion":2,"participationVersion":3,"benefitContentId":"inplacex-pro-v3","policyVersion":4,"serverTime":"$ServerTime","expiresAt":"$ValidUntil"}"""
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update("mirkori.pro.game-membership.v1.$encoded".toByteArray(StandardCharsets.US_ASCII))
            sign()
        }
        val encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        return """{"schemaVersion":1,"type":"mirkori.pro.game-membership","payload":"$encoded","signature":{"algorithm":"RS256","keyId":"$KeyId","value":"$encodedSignature"}}"""
    }

    private fun refreshedCredentialsJson(): String =
        """{"accessToken":"refreshed.${"a".repeat(43)}","refreshToken":"refreshed-${"r".repeat(43)}","accessExpiresAtEpochMs":${ServerTime.plusSeconds(3_600).toEpochMilli()},"refreshExpiresAtEpochMs":${ServerTime.plusSeconds(86_400).toEpochMilli()}}"""

    private fun leaseJson(
        sessionId: String,
        status: String,
        heartbeatOffsetSeconds: Long = 0L,
    ): String {
        val heartbeat = ServerTime.plusSeconds(heartbeatOffsetSeconds)
        val releasedAt = if (status == "released") ",\"releasedAt\":\"$heartbeat\"" else ""
        return """{"schemaVersion":1,"leaseId":"$LeaseId","accountId":"$AccountId","gameId":"inplacex","distributionId":"rf-mirkori","installationId":"$InstallationId","sessionId":"$sessionId","benefitContentId":"inplacex-pro-v3","membershipVersion":2,"participationVersion":3,"policyVersion":4,"maxConcurrentSessions":2,"status":"$status","createdAt":"$ServerTime","lastHeartbeatAt":"$heartbeat","expiresAt":"${heartbeat.plusSeconds(60)}"$releasedAt}"""
    }

    private companion object {
        const val AccountId = "00000000-0000-4000-8000-000000000901"
        const val PlayerId = "00000000-0000-4000-8000-000000000902"
        const val InstallationId = "00000000-0000-4000-8000-000000000903"
        const val LeaseId = "00000000-0000-4000-8000-000000000904"
        const val KeyId = "pro-key-2026-01"
        val ServerTime: Instant = Instant.parse("2026-09-02T12:00:00Z")
        val ValidUntil: Instant = ServerTime.plusSeconds(3_600)
        val SessionIdPattern = Regex("\\\"sessionId\\\":\\\"([^\\\"]+)\\\"")
    }
}

private class ProMemoryStore(initial: MirkoriPersistedState) : SecureMirkoriStateStore {
    var value: MirkoriPersistedState? = initial

    override fun read(): MirkoriPersistedState? = value

    override fun write(state: MirkoriPersistedState) {
        value = state
    }

    override fun clear() {
        value = null
    }
}

private class RuntimeProTransport(
    vararg handlers: (PlatformHttpRequest) -> PlatformHttpResponse,
) : PlatformTransport {
    private val handlers = handlers.toMutableList()
    val requests = mutableListOf<PlatformHttpRequest>()

    override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse {
        requests += request
        return handlers.removeFirstOrNull()?.invoke(request) ?: error("No queued Pro response")
    }
}

private object ProEntropy : SecureEntropy {
    override fun bytes(count: Int): ByteArray = ByteArray(count) { 9 }
}

private fun <T> runProRuntime(block: suspend () -> T): T {
    val latch = CountDownLatch(1)
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<T>) {
            outcome = result
            latch.countDown()
        }
    })
    check(latch.await(5, TimeUnit.SECONDS))
    return requireNotNull(outcome).getOrThrow()
}
