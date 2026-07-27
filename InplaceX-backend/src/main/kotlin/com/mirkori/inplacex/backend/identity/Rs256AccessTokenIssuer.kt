package com.mirkori.inplacex.backend.identity

import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Identity-process-only access-token issuer.
 *
 * Production game-api composition must not receive the private key or construct
 * this class. It verifies tokens with the corresponding public key.
 */
class Rs256AccessTokenIssuer(
    private val privateKey: PrivateKey,
    private val policy: CredentialPolicy,
    private val random: SecureRandom = SecureRandom(),
) : AccessTokenIssuer {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    init {
        require(privateKey.algorithm.equals("RSA", ignoreCase = true)) {
            "RS256 issuer requires an RSA private key"
        }
    }

    override fun issue(playerId: String, issuedAt: Instant, expiresAt: Instant): String {
        require(runCatching { UUID.fromString(playerId).toString() == playerId }.getOrDefault(false)) {
            "playerId must be a canonical UUID"
        }
        require(expiresAt.isAfter(issuedAt)) { "access token expiry must be after issuance" }
        require(expiresAt <= issuedAt.plus(policy.accessTtl)) {
            "access token lifetime exceeds policy"
        }

        val header = encode("""{"alg":"RS256","typ":"JWT"}""")
        val payload = encode(
            """{"iss":"${json(policy.issuer)}","aud":"${json(policy.audience)}","sub":"$playerId","iat":${issuedAt.epochSecond},"exp":${expiresAt.epochSecond},"jti":"${UUID.randomUUID()}"}""",
        )
        val unsigned = "$header.$payload"
        val signature = Signature.getInstance(SignatureAlgorithm).run {
            initSign(privateKey, random)
            update(unsigned.toByteArray(StandardCharsets.US_ASCII))
            sign()
        }
        return "$unsigned.${encoder.encodeToString(signature)}"
    }

    override fun toString(): String = "Rs256AccessTokenIssuer([redacted])"

    private fun encode(value: String): String =
        encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun json(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(character)
            }
        }
    }

    private companion object {
        const val SignatureAlgorithm = "SHA256withRSA"
    }
}
