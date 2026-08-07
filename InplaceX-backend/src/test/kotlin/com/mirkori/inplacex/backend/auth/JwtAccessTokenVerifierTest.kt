package com.mirkori.inplacex.backend.auth

import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JwtAccessTokenVerifierTest {
    private val now = Instant.parse("2026-07-26T12:00:00Z")
    private val trustedKeys = rsaKeys()
    private val verifier = JwtAccessTokenVerifier(
        verificationKey = trustedKeys.public,
        policy = JwtVerificationPolicy.platformGame(
            issuer = Issuer,
            audience = Audience,
            gameId = GameId,
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
        assertEquals(AccountId, result.principal.accountId)
        assertEquals(GameId, result.principal.gameId)
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
        val tamperedPayload = encoder.encodeToString(
            validPayload(subject = OtherPlayerId).toByteArray(StandardCharsets.UTF_8),
        )

        assertRejected(
            "${segments[0]}.$tamperedPayload.${segments[2]}",
            AccessTokenRejection.INVALID_SIGNATURE,
        )
        assertRejected(
            token(payloadBytes = byteArrayOf(0x7b, 0x22, 0x78, 0x22, 0x3a, 0xc3.toByte(), 0x28, 0x7d)),
            AccessTokenRejection.INVALID_CLAIMS,
        )
        assertRejected(
            token(
                payload = validPayload().replace(
                    "\"iss\":\"$Issuer\"",
                    "\"iss\":\"$Issuer\",\"iss\":\"attacker\"",
                ),
            ),
            AccessTokenRejection.INVALID_CLAIMS,
        )
    }

    @Test
    fun `algorithm confusion wrong game and non canonical ids are rejected`() {
        assertRejected(
            token(
                header = """{"alg":"none","typ":"JWT"}""",
                payload = validPayload(),
            ),
            AccessTokenRejection.INVALID_CLAIMS,
        )
        assertRejected(
            token(payload = validPayload(subject = AccountId.uppercase())),
            AccessTokenRejection.INVALID_CLAIMS,
        )
        assertRejected(
            token(payload = validPayload(playerId = PlayerId.uppercase())),
            AccessTokenRejection.INVALID_CLAIMS,
        )
        assertRejected(
            token(payload = validPayload(gameId = "another-game")),
            AccessTokenRejection.INVALID_CLAIMS,
        )
        assertRejected(
            token(payload = validPayload(tokenId = "not-a-token-id")),
            AccessTokenRejection.INVALID_CLAIMS,
        )
    }

    @Test
    fun `signed additive platform claims are accepted but required pid remains mandatory`() {
        val extended = token(
            payload = validPayload().replace(
                "}",
                """, "sid":"00000000-0000-4000-8000-000000000099","amr":"google","roles":["player"]}""",
            ),
        )

        val accepted = verifier.authenticate("Bearer $extended") as AccessTokenAuthentication.Accepted

        assertEquals(AccountId, accepted.principal.accountId)
        assertEquals(PlayerId, accepted.principal.playerId)
        assertRejected(
            token(payload = validPayload().replace("\"pid\":\"$PlayerId\",", "")),
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
    fun `policy key type and public verifier surface are bounded`() {
        assertThrows(IllegalArgumentException::class.java) {
            JwtVerificationPolicy.platformGame(
                issuer = "issuer with spaces",
                audience = Audience,
                gameId = GameId,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            JwtAccessTokenVerifier(
                verificationKey = KeyPairGenerator.getInstance("EC")
                    .apply { initialize(256) }
                    .generateKeyPair()
                    .public,
                policy = JwtVerificationPolicy.platformGame(Issuer, Audience, GameId),
                clock = Clock.fixed(now, ZoneOffset.UTC),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            JwtAccessTokenVerifier(
                verificationKey = KeyPairGenerator.getInstance("RSA")
                    .apply { initialize(1024) }
                    .generateKeyPair()
                    .public,
                policy = JwtVerificationPolicy.platformGame(Issuer, Audience, GameId),
                clock = Clock.fixed(now, ZoneOffset.UTC),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            JwtVerificationPolicy.platformGame(
                issuer = Issuer,
                audience = Audience,
                gameId = GameId,
                maximumTokenLifetime = Duration.ofHours(2),
            )
        }

        val publicMethods = JwtAccessTokenVerifier::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertEquals(setOf("authenticate", "toString"), publicMethods)
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
        header: String = """{"alg":"RS256","typ":"JWT"}""",
        payload: String? = null,
        payloadBytes: ByteArray? = null,
        signingKey: PrivateKey = trustedKeys.private,
    ): String {
        val headerSegment = encoder.encodeToString(header.toByteArray(StandardCharsets.UTF_8))
        val payloadSegment = encoder.encodeToString(
            payloadBytes ?: requireNotNull(payload).toByteArray(StandardCharsets.UTF_8),
        )
        val unsigned = "$headerSegment.$payloadSegment"
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(signingKey)
            update(unsigned.toByteArray(StandardCharsets.US_ASCII))
            sign()
        }
        return "$unsigned.${encoder.encodeToString(signature)}"
    }

    private fun validPayload(
        issuer: String = Issuer,
        audience: String = Audience,
        subject: String = AccountId,
        playerId: String = PlayerId,
        gameId: String = GameId,
        issuedAt: Instant = now,
        expiresAt: Instant = now.plusSeconds(900),
        tokenId: String = TokenId,
    ): String =
        """{"iss":"$issuer","aud":"$audience","sub":"$subject","pid":"$playerId","gid":"$gameId","iat":${issuedAt.epochSecond},"exp":${expiresAt.epochSecond},"jti":"$tokenId"}"""

    private fun rsaKeys(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private companion object {
        const val Issuer = "mirkori-platform"
        const val Audience = "mirkori-games"
        const val GameId = "inplacex"
        const val AccountId = "00000000-0000-4000-8000-abcdefabc001"
        const val PlayerId = "00000000-0000-4000-8000-abcdefabcdef"
        const val OtherPlayerId = "00000000-0000-4000-8000-000000000002"
        const val TokenId = "00000000-0000-4000-8000-000000000003"
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
