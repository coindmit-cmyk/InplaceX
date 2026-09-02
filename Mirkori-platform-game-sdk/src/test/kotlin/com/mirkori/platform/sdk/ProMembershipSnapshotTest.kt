package com.mirkori.platform.sdk

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProMembershipSnapshotTest {
    @Test
    fun verifiesPinnedScopeAndUsesRollbackResistantMonotonicAnchor() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val accountId = UUID.randomUUID().toString()
        val serverTime = Instant.parse("2026-09-02T12:00:00Z")
        val validUntil = serverTime.plusSeconds(120)
        val envelope = envelope(keys.private, accountId, serverTime, validUntil)
        val verifier = PlatformProMembershipSnapshotVerifier(
            Rs256PlatformProSnapshotSignatureVerifier(mapOf(KeyId to keys.public)),
        )

        val snapshot = verifier.verify(envelope, accountId, "inplacex", "rf-mirkori")
        val anchor = snapshot.timeAnchor(10_000, "boot-session-2026-09-02-a")
        assertTrue(snapshot.hasBenefitAt(anchor, 129_999, "boot-session-2026-09-02-a"))
        assertFalse(snapshot.hasBenefitAt(anchor, 130_000, "boot-session-2026-09-02-a"))
        assertFalse(snapshot.hasBenefitAt(anchor, 9_999, "boot-session-2026-09-02-a"))
        assertFalse(snapshot.hasBenefitAt(anchor, 11_000, "boot-session-after-reboot"))
        assertThrows(IllegalArgumentException::class.java) {
            verifier.verify(envelope, UUID.randomUUID().toString(), "inplacex", "rf-mirkori")
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifier.verify(envelope, accountId, "inplacex", "global-google")
        }
    }

    @Test
    fun rejectsUnknownKeyTamperingAndExpiredTrustedFloor() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val otherKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val accountId = UUID.randomUUID().toString()
        val serverTime = Instant.parse("2026-09-02T12:00:00Z")
        val envelope = envelope(keys.private, accountId, serverTime, serverTime.plusSeconds(120))
        val verifier = PlatformProMembershipSnapshotVerifier(
            Rs256PlatformProSnapshotSignatureVerifier(mapOf(KeyId to keys.public)),
        )
        val unknownKeyVerifier = PlatformProMembershipSnapshotVerifier(
            Rs256PlatformProSnapshotSignatureVerifier(mapOf("pro-key-other" to otherKeys.public)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            unknownKeyVerifier.verify(envelope, accountId, "inplacex", "rf-mirkori")
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifier.verify(envelope.replaceFirst("\"payload\":\"", "\"payload\":\"A"), accountId, "inplacex", "rf-mirkori")
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifier.verify(envelope, accountId, "inplacex", "rf-mirkori", serverTime.plusSeconds(120))
        }
    }

    private fun envelope(privateKey: PrivateKey, accountId: String, serverTime: Instant, validUntil: Instant): String {
        val payload = """{"schemaVersion":1,"type":"mirkori.pro.game-membership","accountId":"$accountId","gameId":"inplacex","distributionId":"rf-mirkori","participating":true,"active":true,"validUntil":"$validUntil","membershipVersion":2,"participationVersion":3,"benefitContentId":"inplacex-pro-v3","policyVersion":4,"serverTime":"$serverTime","expiresAt":"$validUntil"}"""
        val encodedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update("mirkori.pro.game-membership.v1.$encodedPayload".toByteArray(StandardCharsets.US_ASCII))
            sign()
        }
        return """{"schemaVersion":1,"type":"mirkori.pro.game-membership","payload":"$encodedPayload","signature":{"algorithm":"RS256","keyId":"$KeyId","value":"${Base64.getUrlEncoder().withoutPadding().encodeToString(signature)}"}}"""
    }

    private companion object {
        const val KeyId = "pro-key-2026-01"
    }
}
