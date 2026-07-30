package com.mirkori.inplacex.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthProviderSecurityTest {
    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `email addresses normalize without becoming permissive`() {
        assertEquals("player@example.com", EmailAddressPolicy.normalize(" Player@Example.COM "))
        listOf(
            "missing-at.example.com",
            "@example.com",
            "player@localhost",
            ".player@example.com",
            "player..name@example.com",
        ).forEach { value ->
            assertTrue(runCatching { EmailAddressPolicy.normalize(value) }.isFailure)
        }
    }

    @Test
    fun `email code is verified once policy inputs match and expires closed`() {
        val now = Instant.parse("2026-07-30T00:00:00Z")
        val policy = PasswordlessEmailCodePolicy(
            secureRandom = DeterministicSecureRandom(),
            ttl = Duration.ofMinutes(10),
        )
        val issued = policy.issue(
            challengeId = "challenge_1234567890",
            email = "Player@Example.com",
            key = key,
            now = now,
        )

        assertTrue(
            policy.verify(
                challengeId = "challenge_1234567890",
                email = "player@example.com",
                code = issued.code,
                expectedProof = issued.proof,
                expiresAt = issued.expiresAt,
                key = key,
                now = now.plusSeconds(60),
            ),
        )
        assertFalse(
            policy.verify(
                challengeId = "challenge_1234567890",
                email = "player@example.com",
                code = "000000",
                expectedProof = issued.proof,
                expiresAt = issued.expiresAt,
                key = key,
                now = now.plusSeconds(60),
            ),
        )
        assertFalse(
            policy.verify(
                challengeId = "challenge_1234567890",
                email = "player@example.com",
                code = issued.code,
                expectedProof = issued.proof,
                expiresAt = issued.expiresAt,
                key = key,
                now = issued.expiresAt.plusMillis(1),
            ),
        )
        assertFalse(
            policy.verify(
                challengeId = "challenge_1234567890",
                email = "player@example.com",
                code = issued.code,
                expectedProof = issued.proof,
                expiresAt = issued.expiresAt,
                key = key,
                now = issued.expiresAt,
            ),
        )
        assertFalse(issued.toString().contains(issued.code))
    }

    @Test
    fun `provider subjects are deterministic domain separated and redact external ids`() {
        val email = ProviderSubjectDeriver.derive(AuthProvider.EMAIL, "player@example.com", key)
        val same = ProviderSubjectDeriver.derive(AuthProvider.EMAIL, "player@example.com", key)
        val telegram = ProviderSubjectDeriver.derive(AuthProvider.TELEGRAM, "player@example.com", key)

        assertEquals(email, same)
        assertNotEquals(email.subject, telegram.subject)
        assertFalse(email.subject.contains("player"))
        assertFalse(email.toString().contains(email.subject))
    }

    @Test
    fun `telegram payload verifies signature freshness and rejects tampering`() {
        val now = Instant.parse("2026-07-30T00:00:00Z")
        val token = "123456789:fixture_bot_token"
        val unsigned = mapOf(
            "id" to "123456789",
            "first_name" to "Mira",
            "auth_date" to now.epochSecond.toString(),
        )
        val fields = unsigned + ("hash" to telegramHash(unsigned, token))
        val verifier = TelegramLoginVerifier()

        val verified = verifier.verify(fields, token, now)

        assertEquals("123456789", verified?.externalUserId)
        assertEquals("Mira", verified?.displayName)
        assertFalse(verified.toString().contains("123456789"))
        assertNull(verifier.verify(fields + ("first_name" to "Mallory"), token, now))
        assertNull(verifier.verify(fields, token, now.plus(Duration.ofHours(1))))
        assertNull(verifier.verify(fields + ("unexpected" to "value"), token, now))
    }

    private fun telegramHash(fields: Map<String, String>, botToken: String): String {
        val check = fields.toSortedMap().entries.joinToString("\n") { (key, value) -> "$key=$value" }
        val secret = MessageDigest.getInstance("SHA-256")
            .digest(botToken.toByteArray(StandardCharsets.UTF_8))
        return ProviderSubjectDeriver.hmacSha256(
            secret,
            check.toByteArray(StandardCharsets.UTF_8),
        ).toLowerHex()
    }
}

private class DeterministicSecureRandom : SecureRandom() {
    override fun nextInt(bound: Int): Int = bound / 2
}
