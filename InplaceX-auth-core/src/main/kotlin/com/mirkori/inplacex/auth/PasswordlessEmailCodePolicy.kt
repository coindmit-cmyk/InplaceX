package com.mirkori.inplacex.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Locale

object EmailAddressPolicy {
    fun normalize(source: String): String {
        val value = source.trim().lowercase(Locale.ROOT)
        require(value.length in 3..MaximumEmailCharacters)
        require(value.none(Char::isISOControl))
        require(value.count { it == '@' } == 1)
        val local = value.substringBefore('@')
        val domain = value.substringAfter('@')
        require(local.length in 1..MaximumLocalCharacters)
        require(local.first() != '.' && local.last() != '.' && ".." !in local)
        require(local.all { it.isLetterOrDigit() || it in LocalPunctuation })
        val labels = domain.split('.')
        require(labels.size >= 2)
        require(labels.all { label ->
            label.length in 1..MaximumDomainLabelCharacters &&
                label.first() != '-' &&
                label.last() != '-' &&
                label.all { it.isLetterOrDigit() || it == '-' }
        })
        return value
    }

    private val LocalPunctuation = setOf('.', '!', '#', '$', '%', '&', '\'', '*', '+', '-', '/', '=', '?', '^', '_', '`', '{', '|', '}', '~')
    private const val MaximumEmailCharacters = 254
    private const val MaximumLocalCharacters = 64
    private const val MaximumDomainLabelCharacters = 63
}

data class IssuedEmailCode(
    val code: String,
    val proof: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "IssuedEmailCode([redacted])"
}

class PasswordlessEmailCodePolicy(
    private val secureRandom: SecureRandom = SecureRandom(),
    private val ttl: Duration = DefaultTtl,
) {
    init {
        require(!ttl.isNegative && !ttl.isZero && ttl <= MaximumTtl)
    }

    fun issue(
        challengeId: String,
        email: String,
        key: ByteArray,
        now: Instant,
    ): IssuedEmailCode {
        validateChallengeId(challengeId)
        val normalizedEmail = EmailAddressPolicy.normalize(email)
        val code = (secureRandom.nextInt(CodeRange) + MinimumCode).toString()
        return IssuedEmailCode(
            code = code,
            proof = proof(challengeId, normalizedEmail, code, key),
            expiresAt = now.plus(ttl),
        )
    }

    fun verify(
        challengeId: String,
        email: String,
        code: String,
        expectedProof: String,
        expiresAt: Instant,
        key: ByteArray,
        now: Instant,
    ): Boolean {
        if (runCatching { validateChallengeId(challengeId) }.isFailure) return false
        val normalizedEmail = runCatching { EmailAddressPolicy.normalize(email) }.getOrNull()
            ?: return false
        if (!code.matches(CodePattern) || !now.isBefore(expiresAt)) return false
        val actual = proof(challengeId, normalizedEmail, code, key)
        return MessageDigest.isEqual(
            actual.toByteArray(StandardCharsets.US_ASCII),
            expectedProof.toByteArray(StandardCharsets.US_ASCII),
        )
    }

    private fun proof(
        challengeId: String,
        normalizedEmail: String,
        code: String,
        key: ByteArray,
    ): String {
        require(key.size >= MinimumKeyBytes)
        val payload = "$ProofVersion\n$challengeId\n$normalizedEmail\n$code"
            .toByteArray(StandardCharsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ProviderSubjectDeriver.hmacSha256(key, payload))
    }

    private fun validateChallengeId(challengeId: String) {
        require(challengeId.matches(ChallengeIdPattern))
    }

    companion object {
        val DefaultTtl: Duration = Duration.ofMinutes(10)
        val MaximumTtl: Duration = Duration.ofMinutes(30)
        private val ChallengeIdPattern = Regex("[A-Za-z0-9_-]{16,128}")
        private val CodePattern = Regex("[0-9]{6}")
        private const val ProofVersion = "email-code-v1"
        private const val MinimumKeyBytes = 32
        private const val MinimumCode = 100_000
        private const val CodeRange = 900_000
    }
}
