package com.mirkori.platform.sdk

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProGameApiSdkTest {
    @Test
    fun fetchesVerifiedSnapshotAndControlsExactLeaseScope() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val accountId = UUID.randomUUID().toString()
        val installationId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val leaseId = UUID.randomUUID().toString()
        val now = Instant.parse("2026-09-02T12:00:00Z")
        val transport = ProQueueTransport(
            PlatformHttpResponse(200, snapshotEnvelope(keys.private, accountId, now)),
            PlatformHttpResponse(201, leaseJson(leaseId, accountId, installationId, sessionId, now, "active")),
            PlatformHttpResponse(200, leaseJson(leaseId, accountId, installationId, sessionId, now, "active", 20)),
            PlatformHttpResponse(200, leaseJson(leaseId, accountId, installationId, sessionId, now, "released", 20)),
        )
        val sdk = MirkoriGameSdk(
            config = MirkoriGameSdkConfig(
                "https://games.dmit.life", "inplacex",
                "https://games.dmit.life/connect/inplacex/callback", distributionId = "rf-mirkori",
            ),
            transport = transport,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            proSnapshotVerifier = PlatformProMembershipSnapshotVerifier(
                Rs256PlatformProSnapshotSignatureVerifier(mapOf(KeyId to keys.public)),
            ),
        )
        val accessToken = "access." + "a".repeat(40)

        val snapshot = runPro { sdk.proMembershipSnapshot(accessToken, accountId) }
        assertTrue(snapshot.active)
        val lease = runPro {
            sdk.startProSession(
                accessToken, accountId, installationId, sessionId, PlatformIdempotencyKey("pro-start-1"),
            )
        }
        val heartbeat = runPro {
            sdk.heartbeatProSession(accessToken, lease, PlatformIdempotencyKey("pro-heartbeat-1"))
        }
        val released = runPro {
            sdk.releaseProSession(accessToken, heartbeat, PlatformIdempotencyKey("pro-release-1"))
        }

        assertEquals(PlatformProSessionLeaseStatus.RELEASED, released.status)
        assertEquals(
            "https://games.dmit.life/api/v1/pro/snapshot?distributionId=rf-mirkori",
            transport.requests[0].url,
        )
        assertEquals("Bearer $accessToken", transport.requests[1].headers["Authorization"])
        assertEquals("pro-start-1", transport.requests[1].headers["Idempotency-Key"])
        assertEquals(
            """{"distributionId":"rf-mirkori","installationId":"$installationId","sessionId":"$sessionId"}""",
            transport.requests[1].body,
        )
        assertTrue(transport.requests[2].url.endsWith("/api/v1/pro/leases/$leaseId/heartbeat"))
        assertTrue(transport.requests[3].url.endsWith("/api/v1/pro/leases/$leaseId/release"))
    }

    @Test
    fun mapsFailClosedAndConcurrencyResponsesToTypedFailures() {
        val config = MirkoriGameSdkConfig(
            "https://games.dmit.life", "inplacex",
            "https://games.dmit.life/connect/inplacex/callback", distributionId = "rf-mirkori",
        )
        val accountId = UUID.randomUUID().toString()
        val installationId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val accessToken = "access." + "b".repeat(40)
        val unavailable = MirkoriGameSdk(
            config,
            ProQueueTransport(PlatformHttpResponse(503, """{"error":"pro_configuration_unavailable"}""")),
        )
        val configurationError = assertThrows(PlatformProConfigurationUnavailableException::class.java) {
            runPro { unavailable.startProSession(accessToken, accountId, installationId, sessionId) }
        }
        assertEquals(PlatformRecoveryAction.RETRY_SAME_REQUEST, configurationError.recoveryAction)
        val limited = MirkoriGameSdk(
            config,
            ProQueueTransport(PlatformHttpResponse(409, """{"error":"pro_concurrency_limit"}""")),
        )
        val limitError = assertThrows(PlatformProConcurrencyLimitException::class.java) {
            runPro { limited.startProSession(accessToken, accountId, installationId, sessionId) }
        }
        assertEquals(PlatformRecoveryAction.RESOLVE_CONFLICT, limitError.recoveryAction)
        val leaseUnavailable = MirkoriGameSdk(
            config,
            ProQueueTransport(PlatformHttpResponse(409, """{"error":"pro_lease_unavailable"}""")),
        )
        val leaseError = assertThrows(PlatformProBenefitUnavailableException::class.java) {
            runPro { leaseUnavailable.startProSession(accessToken, accountId, installationId, sessionId) }
        }
        assertEquals(PlatformProBenefitUnavailableReason.LEASE, leaseError.reason)
        assertEquals(PlatformRecoveryAction.RESOLVE_CONFLICT, leaseError.recoveryAction)
        val unauthorized = MirkoriGameSdk(
            config,
            ProQueueTransport(PlatformHttpResponse(401, """{"error":"pro_unavailable"}""")),
        )
        val authError = assertThrows(PlatformApiException::class.java) {
            runPro { unauthorized.startProSession(accessToken, accountId, installationId, sessionId) }
        }
        assertEquals(PlatformRecoveryAction.REAUTHENTICATE, authError.recoveryAction)
        assertEquals("pro_unavailable", authError.errorCode)
    }

    private fun snapshotEnvelope(privateKey: PrivateKey, accountId: String, now: Instant): String {
        val validUntil = now.plusSeconds(3600)
        val payload = """{"schemaVersion":1,"type":"mirkori.pro.game-membership","accountId":"$accountId","gameId":"inplacex","distributionId":"rf-mirkori","participating":true,"active":true,"validUntil":"$validUntil","membershipVersion":2,"participationVersion":3,"benefitContentId":"inplacex-pro-v3","policyVersion":4,"serverTime":"$now","expiresAt":"$validUntil"}"""
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update("mirkori.pro.game-membership.v1.$encoded".toByteArray(StandardCharsets.US_ASCII))
            sign()
        }
        return """{"schemaVersion":1,"type":"mirkori.pro.game-membership","payload":"$encoded","signature":{"algorithm":"RS256","keyId":"$KeyId","value":"${Base64.getUrlEncoder().withoutPadding().encodeToString(signature)}"}}"""
    }

    private fun leaseJson(
        leaseId: String,
        accountId: String,
        installationId: String,
        sessionId: String,
        createdAt: Instant,
        status: String,
        heartbeatOffset: Long = 0,
    ): String {
        val heartbeat = createdAt.plusSeconds(heartbeatOffset)
        val released = if (status == "released") ",\"releasedAt\":\"$heartbeat\"" else ""
        return """{"schemaVersion":1,"leaseId":"$leaseId","accountId":"$accountId","gameId":"inplacex","distributionId":"rf-mirkori","installationId":"$installationId","sessionId":"$sessionId","benefitContentId":"inplacex-pro-v3","membershipVersion":2,"participationVersion":3,"policyVersion":4,"maxConcurrentSessions":2,"status":"$status","createdAt":"$createdAt","lastHeartbeatAt":"$heartbeat","expiresAt":"${heartbeat.plusSeconds(60)}"$released}"""
    }

    private companion object {
        const val KeyId = "pro-key-2026-01"
    }
}

private class ProQueueTransport(vararg responses: PlatformHttpResponse) : PlatformTransport {
    val requests = mutableListOf<PlatformHttpRequest>()
    private val responses = responses.toMutableList()
    override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse {
        requests += request
        return responses.removeFirstOrNull() ?: error("No queued response")
    }
}

private fun <T> runPro(block: suspend () -> T): T {
    val latch = CountDownLatch(1)
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { outcome = result; latch.countDown() }
    })
    check(latch.await(5, TimeUnit.SECONDS))
    return requireNotNull(outcome).getOrThrow()
}
