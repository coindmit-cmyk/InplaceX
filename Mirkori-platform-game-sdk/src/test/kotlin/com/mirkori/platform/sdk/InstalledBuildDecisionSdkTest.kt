package com.mirkori.platform.sdk

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class InstalledBuildDecisionSdkTest {
    @Test
    fun sdkVerifiesPinnedSignatureAndRejectsTamperedDecision() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = Instant.parse("2026-08-30T15:00:00Z")
        val response = signedEnvelope(keys.private, requiredPayload(now))
        val transport = DecisionTransport(response)
        val sdk = MirkoriGameSdk(
            config = MirkoriGameSdkConfig(
                platformBaseUrl = "https://games.dmit.life",
                gameId = "inplacex",
                redirectUri = "https://games.dmit.life/connect/inplacex/callback",
                distributionId = "rf-mirkori",
            ),
            transport = transport,
            releaseDecisionVerifier = Rs256PlatformReleaseDecisionVerifier(mapOf(KeyId to keys.public)),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        val decision = runDecision {
            sdk.checkInstalledBuildDecision("inplacex-rf-40", 40)
        }

        assertEquals(PlatformInstalledBuildStatus.REQUIRED, decision.status)
        assertFalse(decision.launchAllowed)
        assertEquals("inplacex-rf-50", decision.release?.id)
        assertEquals(KeyId, decision.signatureKeyId)
        assertEquals(
            "https://games.dmit.life/api/v3/catalog/games/inplacex/installed-build-decision" +
                "?distributionId=rf-mirkori&channel=stable&releaseId=inplacex-rf-40&versionCode=40",
            transport.request?.url,
        )

        val tampered = response.replaceFirst("\"payload\":\"", "\"payload\":\"A")
        val tamperedSdk = MirkoriGameSdk(
            config = sdk.config,
            transport = DecisionTransport(tampered),
            releaseDecisionVerifier = Rs256PlatformReleaseDecisionVerifier(mapOf(KeyId to keys.public)),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        assertThrows(IllegalArgumentException::class.java) {
            runDecision { tamperedSdk.checkInstalledBuildDecision("inplacex-rf-40", 40) }
        }
    }

    @Test
    fun sdkUsesTransportValidatedServerTimeWhenDeviceClockIsWrong() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val serverTime = Instant.parse("2026-08-30T15:00:00Z")
        val deviceTime = serverTime.minusSeconds(86_400)
        val sdk = MirkoriGameSdk(
            config = MirkoriGameSdkConfig(
                platformBaseUrl = "https://games.dmit.life",
                gameId = "inplacex",
                redirectUri = "https://games.dmit.life/connect/inplacex/callback",
                distributionId = "rf-mirkori",
            ),
            transport = DecisionTransport(
                response = signedEnvelope(keys.private, requiredPayload(serverTime)),
                serverTime = serverTime,
            ),
            releaseDecisionVerifier = Rs256PlatformReleaseDecisionVerifier(mapOf(KeyId to keys.public)),
            clock = Clock.fixed(deviceTime, ZoneOffset.UTC),
        )

        val decision = runDecision {
            sdk.checkInstalledBuildDecision("inplacex-rf-40", 40)
        }

        assertEquals(PlatformInstalledBuildStatus.REQUIRED, decision.status)
        assertEquals(serverTime.toEpochMilli(), sdk.latestServerTimeObservation()?.serverEpochMs)
    }

    private fun requiredPayload(now: Instant): String {
        val fingerprint = "AA:".repeat(31) + "AA"
        return """
            {"schemaVersion":3,"gameId":"inplacex","distributionId":"rf-mirkori","channel":"stable",
            "installedReleaseId":"inplacex-rf-40","installedVersionCode":40,"status":"required",
            "launchAllowed":false,"policyVersion":4,"issuedAt":"$now","expiresAt":"${now.plusSeconds(300)}",
            "distribution":{"id":"rf-mirkori","gameId":"inplacex","platform":"android","marketScope":"rf",
            "packageName":"com.mirkori.inplacex.rf","signingIdentityRef":"inplacex-rf-signing",
            "signingCertificateSha256Fingerprints":["$fingerprint"],"paymentChannel":"mirkori",
            "deliveryChannel":"direct_apk","releaseChannels":["stable"],"status":"active","effectiveConfigurationVersion":1},
            "release":{"id":"inplacex-rf-50","gameId":"inplacex","distributionId":"rf-mirkori","platform":"android",
            "channel":"stable","versionName":"1.50","versionCode":50,"minimumSupportedVersionCode":45,"minimumAndroidSdk":26,
            "publishedAt":"2026-08-30T12:00:00Z","changelogs":{"ru":"Релиз 50","en":"Release 50"},
            "fileName":"InplaceX-rf-50.apk","sizeBytes":123,"sha256":"${"a".repeat(64)}",
            "downloadUrl":"https://games.dmit.life/downloads/inplacex-rf-50/InplaceX-rf-50.apk",
            "packageName":"com.mirkori.inplacex.rf","signingIdentityRef":"inplacex-rf-signing",
            "signingCertificateSha256Fingerprints":["$fingerprint"]}}
        """.trimIndent().replace("\n", "")
    }

    private fun signedEnvelope(privateKey: java.security.PrivateKey, payload: String): String {
        val encodedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(encodedPayload.toByteArray(StandardCharsets.US_ASCII))
            sign()
        }
        val encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        return """{"schemaVersion":3,"payload":"$encodedPayload","signature":{"algorithm":"RS256","keyId":"$KeyId","value":"$encodedSignature"}}"""
    }

    private companion object {
        const val KeyId = "release-key-2026-01"
    }
}

private class DecisionTransport(
    private val response: String,
    private val serverTime: Instant? = null,
) : PlatformTransport {
    var request: PlatformHttpRequest? = null

    override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse {
        this.request = request
        return PlatformHttpResponse(200, response, serverTime)
    }
}

private fun <T> runDecision(block: suspend () -> T): T {
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
