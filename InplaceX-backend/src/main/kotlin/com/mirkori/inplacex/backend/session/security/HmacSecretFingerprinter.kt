package com.mirkori.inplacex.backend.session.security

import com.mirkori.inplacex.backend.session.contract.PublicParticipantId
import com.mirkori.inplacex.backend.session.contract.PublicSessionId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

class SecretFingerprint internal constructor(
    private val digest: ByteArray,
) {
    fun asStorageValue(): String = STORAGE_PREFIX + BASE64_ENCODER.encodeToString(digest)

    internal fun matchesDigest(candidate: ByteArray): Boolean =
        MessageDigest.isEqual(digest, candidate)

    override fun equals(other: Any?): Boolean =
        other is SecretFingerprint && MessageDigest.isEqual(digest, other.digest)

    override fun hashCode(): Int = digest.contentHashCode()

    override fun toString(): String = "SecretFingerprint([redacted])"

    private companion object {
        const val STORAGE_PREFIX: String = "hmac-sha256-v1:"
        val BASE64_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}

class HmacSecretFingerprinter(pepper: ByteArray) {
    private val hmacKey = HmacSha256Key(pepper)

    fun fingerprint(
        sessionId: PublicSessionId,
        participantId: PublicParticipantId,
        secret: CharArray,
    ): SecretFingerprint = withScopedBytes(sessionId, participantId) { sessionBytes, participantBytes ->
        withDigitSecretBytes(secret) { secretBytes ->
            SecretFingerprint(
                hmacKey.digest(
                    SECRET_FINGERPRINT_DOMAIN,
                    sessionBytes,
                    participantBytes,
                    secretBytes,
                ),
            )
        }
    }

    fun matches(
        fingerprint: SecretFingerprint,
        sessionId: PublicSessionId,
        participantId: PublicParticipantId,
        candidate: CharArray,
    ): Boolean = withScopedBytes(sessionId, participantId) { sessionBytes, participantBytes ->
        withDigitSecretBytes(candidate) { candidateBytes ->
            val candidateDigest = hmacKey.digest(
                SECRET_FINGERPRINT_DOMAIN,
                sessionBytes,
                participantBytes,
                candidateBytes,
            )
            try {
                fingerprint.matchesDigest(candidateDigest)
            } finally {
                candidateDigest.fill(0)
            }
        }
    }

    override fun toString(): String = "HmacSecretFingerprinter(pepper=[redacted])"

    private companion object {
        const val SECRET_FINGERPRINT_DOMAIN: String = "inplacex.session.secret-fingerprint.v1"
    }
}

internal inline fun <T> withDigitSecretBytes(
    secret: CharArray,
    block: (ByteArray) -> T,
): T {
    require(secret.size in 4..20 && secret.all { it in '0'..'9' }) {
        "Secret must contain 4..20 ASCII digits"
    }
    val encoded = ByteArray(secret.size) { index -> secret[index].code.toByte() }
    return try {
        block(encoded)
    } finally {
        encoded.fill(0)
    }
}

private inline fun <T> withScopedBytes(
    sessionId: PublicSessionId,
    participantId: PublicParticipantId,
    block: (ByteArray, ByteArray) -> T,
): T {
    val sessionBytes = sessionId.value.toByteArray(StandardCharsets.UTF_8)
    val participantBytes = participantId.value.toByteArray(StandardCharsets.UTF_8)
    return try {
        block(sessionBytes, participantBytes)
    } finally {
        sessionBytes.fill(0)
        participantBytes.fill(0)
    }
}
