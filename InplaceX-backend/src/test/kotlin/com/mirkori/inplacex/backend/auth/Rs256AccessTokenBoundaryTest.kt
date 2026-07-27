package com.mirkori.inplacex.backend.auth

import com.mirkori.inplacex.backend.identity.CredentialPolicy
import com.mirkori.inplacex.backend.identity.Rs256AccessTokenIssuer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Rs256AccessTokenBoundaryTest {
    private val now = Instant.parse("2026-07-27T10:00:00Z")
    private val policy = CredentialPolicy(
        issuer = "inplacex-identity",
        audience = "inplacex-game-api",
    )
    private val verificationPolicy = JwtVerificationPolicy(
        issuer = policy.issuer,
        audience = policy.audience,
        maximumTokenLifetime = policy.accessTtl,
        allowedClockSkew = Duration.ZERO,
    )

    @Test
    fun `identity issued token is accepted by public-key-only game api`() {
        val keys = rsaKeys()
        val playerId = UUID.randomUUID().toString()
        val token = Rs256AccessTokenIssuer(keys.private, policy)
            .issue(playerId, now, now.plus(policy.accessTtl))
        val verifier = JwtAccessTokenVerifier(
            verificationKey = keys.public,
            policy = verificationPolicy,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        val result = verifier.authenticate("Bearer $token")

        assertTrue(result is AccessTokenAuthentication.Accepted)
        assertEquals(playerId, (result as AccessTokenAuthentication.Accepted).principal.playerId)
        assertFalse(result.principal.toString().contains(playerId))
    }

    @Test
    fun `token signed by a caller-owned key is rejected`() {
        val trusted = rsaKeys()
        val attacker = rsaKeys()
        val forged = Rs256AccessTokenIssuer(attacker.private, policy).issue(
            UUID.randomUUID().toString(),
            now,
            now.plus(policy.accessTtl),
        )
        val verifier = JwtAccessTokenVerifier(
            verificationKey = trusted.public,
            policy = verificationPolicy,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        assertEquals(
            AccessTokenAuthentication.Rejected(AccessTokenRejection.INVALID_SIGNATURE),
            verifier.authenticate("Bearer $forged"),
        )
    }

    @Test
    fun `malformed UTF-8 and algorithm substitution fail closed`() {
        val keys = rsaKeys()
        val verifier = JwtAccessTokenVerifier(
            verificationKey = keys.public,
            policy = verificationPolicy,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val malformedHeader = encoder.encodeToString(byteArrayOf(0xC3.toByte(), 0x28))
        val noneHeader = encoder.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString("{}".toByteArray())
        val signature = encoder.encodeToString(ByteArray(256) { 1 })

        assertEquals(
            AccessTokenAuthentication.Rejected(AccessTokenRejection.MALFORMED_TOKEN),
            verifier.authenticate("Bearer $malformedHeader.$payload.$signature"),
        )
        assertEquals(
            AccessTokenAuthentication.Rejected(AccessTokenRejection.INVALID_CLAIMS),
            verifier.authenticate("Bearer $noneHeader.$payload.$signature"),
        )
    }

    @Test
    fun `verification composition contains no private signing key`() {
        val verifier = JwtAccessTokenVerifier(
            verificationKey = rsaKeys().public,
            policy = verificationPolicy,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        val reachableFields = generateSequence(verifier.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .map { field ->
                field.isAccessible = true
                field.get(verifier)
            }
            .toList()

        assertTrue(reachableFields.none { it is PrivateKey })
        assertTrue(reachableFields.none { it is ByteArray })
    }

    private fun rsaKeys(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
}
