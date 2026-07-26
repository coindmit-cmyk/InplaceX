package com.mirkori.inplacex.backend.auth

import java.nio.charset.StandardCharsets
import java.lang.reflect.Modifier
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JwtAccessTokenVerifierTest {
    private val now: Instant = Instant.parse("2026-07-26T12:00:00Z")
    private val signingSecret: ByteArray = ByteArray(32) { 17 }
    private val verifier = JwtAccessTokenVerifier(
        signingSecret = signingSecret,
        policy = JwtVerificationPolicy(
            issuer = Issuer,
            audience = Audience,
            maximumTokenLifetime = Duration.ofMinutes(15),
            allowedClockSkew = Duration.ZERO,
        ),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )

    @Test
    fun `accepts exact server token and returns opaque principal`() {
        val token = token(payload = validPayload())

        val result = verifier.authenticate("Bearer $token") as AccessTokenAuthentication.Accepted

        assertEquals(PlayerId, result.principal.playerId)
        assertEquals(TokenId, result.principal.tokenId)
        assertEquals("AuthenticatedPrincipal([redacted])", result.principal.toString())
        assertFalse(Modifier.isPublic(result.principal.javaClass.modifiers))
        assertFalse(verifier.toString().contains(token))
    }

    @Test
    fun `authorization header is exact and fail closed`() {
        val token = token(payload = validPayload())
        val rejected = listOf(
            null,
            "",
            " $token",
            "Bearer  $token",
            "Bearer\t$token",
            "Basic $token",
            "Bearer $token trailing",
            "Bearer $token\r\nInjected: value",
        )

        rejected.forEach { header ->
            assertTrue(verifier.authenticate(header) is AccessTokenAuthentication.Rejected)
        }
        assertTrue(verifier.authenticate("bEaReR $token") is AccessTokenAuthentication.Accepted)
    }

    @Test
    fun `tampering malformed utf8 and duplicate claims are rejected`() {
        val valid = token(payload = validPayload())
        val segments = valid.split('.')
        val tamperedPayload = encoder.encodeToString(validPayload(subject = OtherPlayerId).toByteArray())
        val tampered = "${segments[0]}.$tamperedPayload.${segments[2]}"

        assertRejected(tampered, AccessTokenRejection.INVALID_SIGNATURE)
        assertRejected(
            token(payloadBytes = byteArrayOf(0x7b, 0x22, 0x78, 0x22, 0x3a, 0xc3.toByte(), 0x28, 0x7d)),
            AccessTokenRejection.MALFORMED_TOKEN,
        )
        assertRejected(
            token(
                payload = validPayload().replace(
                    "\"iss\":\"$Issuer\"",
                    "\"iss\":\"$Issuer\",\"iss\":\"attacker\"",
                ),
            ),
            AccessTokenRejection.MALFORMED_TOKEN,
        )
    }

    @Test
    fun `algorithm confusion unknown claims and non canonical ids are rejected`() {
        assertRejected(
            token(
                header = """{"alg":"none","typ":"JWT"}""",
                payload = validPayload(),
            ),
            AccessTokenRejection.INVALID_CLAIMS,
        )
        assertRejected(
            token(payload = validPayload().replace("}", ""","role":"admin"}""")),
            AccessTokenRejection.INVALID_CLAIMS,
        )
        assertRejected(
            token(payload = validPayload(subject = PlayerId.uppercase())),
            AccessTokenRejection.INVALID_CLAIMS,
        )
    }

    @Test
    fun `issuer audience time window and maximum lifetime are verified`() {
        assertRejected(
            token(payload = validPayload(issuer = "wrong-issuer")),
            AccessTokenRejection.INVALID_CLAIMS,
        )
        assertRejected(
            token(payload = validPayload(audience = "wrong-audience")),
            AccessTokenRejection.INVALID_CLAIMS,
        )
        assertRejected(
            token(payload = validPayload(issuedAt = now.plusSeconds(1), expiresAt = now.plusSeconds(60))),
            AccessTokenRejection.NOT_YET_VALID,
        )
        assertRejected(
            token(payload = validPayload(issuedAt = now.minusSeconds(900), expiresAt = now)),
            AccessTokenRejection.EXPIRED,
        )
        assertRejected(
            token(payload = validPayload(expiresAt = now.plusSeconds(901))),
            AccessTokenRejection.INVALID_CLAIMS,
        )
    }

    @Test
    fun `policy and key material are bounded`() {
        assertThrows(IllegalArgumentException::class.java) {
            JwtVerificationPolicy(issuer = "issuer with spaces", audience = Audience)
        }
        assertThrows(IllegalArgumentException::class.java) {
            JwtAccessTokenVerifier(
                signingSecret = ByteArray(31),
                policy = JwtVerificationPolicy(Issuer, Audience),
                clock = Clock.fixed(now, ZoneOffset.UTC),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            JwtVerificationPolicy(
                issuer = Issuer,
                audience = Audience,
                maximumTokenLifetime = Duration.ofHours(2),
            )
        }
    }

    @Test
    fun `one verifier safely authenticates concurrent requests`() {
        val token = token(payload = validPayload())
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = (1..200).map {
                executor.submit<AccessTokenAuthentication> {
                    verifier.authenticate("Bearer $token")
                }
            }

            assertTrue(results.all { it.get(10, TimeUnit.SECONDS) is AccessTokenAuthentication.Accepted })
        } finally {
            executor.shutdownNow()
        }
    }

    private fun assertRejected(token: String, expected: AccessTokenRejection) {
        val result = verifier.authenticate("Bearer $token") as AccessTokenAuthentication.Rejected
        assertEquals(expected, result.reason)
    }

    private fun token(
        header: String = """{"alg":"HS256","typ":"JWT"}""",
        payload: String? = null,
        payloadBytes: ByteArray? = null,
    ): String {
        val headerSegment = encoder.encodeToString(header.toByteArray(StandardCharsets.UTF_8))
        val payloadSegment = encoder.encodeToString(
            payloadBytes ?: requireNotNull(payload).toByteArray(StandardCharsets.UTF_8),
        )
        val unsigned = "$headerSegment.$payloadSegment"
        val signature = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(signingSecret, "HmacSHA256"))
            doFinal(unsigned.toByteArray(StandardCharsets.US_ASCII))
        }
        return "$unsigned.${encoder.encodeToString(signature)}"
    }

    private fun validPayload(
        issuer: String = Issuer,
        audience: String = Audience,
        subject: String = PlayerId,
        issuedAt: Instant = now,
        expiresAt: Instant = now.plusSeconds(900),
    ): String =
        """{"iss":"$issuer","aud":"$audience","sub":"$subject","iat":${issuedAt.epochSecond},"exp":${expiresAt.epochSecond},"jti":"$TokenId"}"""

    private companion object {
        const val Issuer = "inplacex-test"
        const val Audience = "inplacex-client"
        const val PlayerId = "00000000-0000-4000-8000-abcdefabcdef"
        const val OtherPlayerId = "00000000-0000-4000-8000-000000000002"
        const val TokenId = "00000000-0000-4000-8000-000000000003"
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
