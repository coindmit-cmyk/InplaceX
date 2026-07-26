package com.mirkori.inplacex.backend.auth

import com.mirkori.inplacex.backend.session.codec.BoundedJsonScanner
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Opaque proof that the canonical verifier accepted a server-issued access token.
 *
 * The implementation is private so callers outside the trusted backend module
 * cannot construct a principal from request fields. Session membership remains
 * a separate authoritative lookup.
 */
sealed interface AuthenticatedPrincipal {
    val playerId: String
    val tokenId: String
}

sealed interface AccessTokenAuthentication {
    data class Accepted(val principal: AuthenticatedPrincipal) : AccessTokenAuthentication

    data class Rejected(val reason: AccessTokenRejection) : AccessTokenAuthentication
}

enum class AccessTokenRejection {
    MISSING_AUTHORIZATION,
    MALFORMED_AUTHORIZATION,
    MALFORMED_TOKEN,
    INVALID_SIGNATURE,
    INVALID_CLAIMS,
    NOT_YET_VALID,
    EXPIRED,
}

data class JwtVerificationPolicy(
    val issuer: String,
    val audience: String,
    val maximumTokenLifetime: Duration = Duration.ofMinutes(15),
    val allowedClockSkew: Duration = Duration.ofSeconds(30),
) {
    init {
        require(issuer.isSafePolicyValue()) { "issuer has an invalid format" }
        require(audience.isSafePolicyValue()) { "audience has an invalid format" }
        require(!maximumTokenLifetime.isNegative && !maximumTokenLifetime.isZero)
        require(maximumTokenLifetime <= MaximumAcceptedLifetime)
        require(!allowedClockSkew.isNegative)
        require(allowedClockSkew <= MaximumAcceptedClockSkew)
    }

    private companion object {
        val MaximumAcceptedLifetime: Duration = Duration.ofHours(1)
        val MaximumAcceptedClockSkew: Duration = Duration.ofMinutes(2)
    }
}

/**
 * Verification-only JWT boundary.
 *
 * Token issuance stays private to the identity service. This verifier accepts
 * HS256 tokens with an exact, bounded claim set and never queries persistence;
 * membership and player status are resolved by later authoritative services.
 */
class JwtAccessTokenVerifier(
    signingSecret: ByteArray,
    private val policy: JwtVerificationPolicy,
    private val clock: Clock,
) {
    private val signingKey = signingSecret.copyOf().let { keyCopy ->
        try {
            require(keyCopy.size >= MinimumSigningKeyBytes) {
                "JWT signing key must contain at least $MinimumSigningKeyBytes bytes"
            }
            SecretKeySpec(keyCopy, HmacAlgorithm)
        } finally {
            keyCopy.fill(0)
        }
    }
    private val decoder = Base64.getUrlDecoder()
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    fun authenticate(authorizationHeader: String?): AccessTokenAuthentication {
        if (authorizationHeader == null) {
            return AccessTokenAuthentication.Rejected(AccessTokenRejection.MISSING_AUTHORIZATION)
        }
        val token = parseBearerToken(authorizationHeader)
            ?: return AccessTokenAuthentication.Rejected(AccessTokenRejection.MALFORMED_AUTHORIZATION)
        return authenticateToken(token)
    }

    private fun authenticateToken(token: String): AccessTokenAuthentication {
        if (token.length > MaximumTokenCharacters) return rejectedMalformedToken()
        val segments = token.split('.')
        if (segments.size != JwtSegmentCount || segments.any { !it.matches(Base64UrlSegment) }) {
            return rejectedMalformedToken()
        }

        val headerBytes = decodeSegment(segments[0], MaximumHeaderBytes) ?: return rejectedMalformedToken()
        val payloadBytes = decodeSegment(segments[1], MaximumPayloadBytes) ?: return rejectedMalformedToken()
        val signature = decodeSegment(segments[2], Sha256Bytes) ?: return rejectedMalformedToken()
        if (signature.size != Sha256Bytes) return rejectedMalformedToken()

        val header = decodeJsonObject(headerBytes) ?: return rejectedMalformedToken()
        if (!header.hasExactStringClaims(ExpectedHeader)) {
            return AccessTokenAuthentication.Rejected(AccessTokenRejection.INVALID_CLAIMS)
        }

        val expectedSignature = sign("${segments[0]}.${segments[1]}")
        val signatureAccepted = try {
            MessageDigest.isEqual(expectedSignature, signature)
        } finally {
            expectedSignature.fill(0)
            signature.fill(0)
        }
        if (!signatureAccepted) {
            return AccessTokenAuthentication.Rejected(AccessTokenRejection.INVALID_SIGNATURE)
        }

        val payload = decodeJsonObject(payloadBytes) ?: return rejectedMalformedToken()
        val claims = payload.toVerifiedClaims()
            ?: return AccessTokenAuthentication.Rejected(AccessTokenRejection.INVALID_CLAIMS)
        if (claims.issuer != policy.issuer || claims.audience != policy.audience) {
            return AccessTokenAuthentication.Rejected(AccessTokenRejection.INVALID_CLAIMS)
        }

        val issuedAt = runCatching { Instant.ofEpochSecond(claims.issuedAt) }.getOrNull()
            ?: return AccessTokenAuthentication.Rejected(AccessTokenRejection.INVALID_CLAIMS)
        val expiresAt = runCatching { Instant.ofEpochSecond(claims.expiresAt) }.getOrNull()
            ?: return AccessTokenAuthentication.Rejected(AccessTokenRejection.INVALID_CLAIMS)
        val lifetime = Duration.between(issuedAt, expiresAt)
        if (lifetime.isNegative || lifetime.isZero || lifetime > policy.maximumTokenLifetime) {
            return AccessTokenAuthentication.Rejected(AccessTokenRejection.INVALID_CLAIMS)
        }

        val now = clock.instant()
        if (issuedAt.isAfter(now.plus(policy.allowedClockSkew))) {
            return AccessTokenAuthentication.Rejected(AccessTokenRejection.NOT_YET_VALID)
        }
        if (!expiresAt.isAfter(now.minus(policy.allowedClockSkew))) {
            return AccessTokenAuthentication.Rejected(AccessTokenRejection.EXPIRED)
        }

        return AccessTokenAuthentication.Accepted(
            VerifiedJwtPrincipal(
                playerId = claims.subject,
                tokenId = claims.tokenId,
            ),
        )
    }

    private fun parseBearerToken(header: String): String? {
        if (header.length > MaximumAuthorizationCharacters || header.any(Char::isISOControl)) return null
        val match = BearerHeader.matchEntire(header) ?: return null
        return match.groupValues[1]
    }

    private fun decodeSegment(value: String, maximumBytes: Int): ByteArray? {
        if (value.length > maximumBytes * Base64ExpansionFactor) return null
        return runCatching { decoder.decode(value) }
            .getOrNull()
            ?.takeIf { it.size <= maximumBytes }
    }

    private fun decodeJsonObject(bytes: ByteArray): JsonObject? {
        val source = decodeStrictUtf8(bytes) ?: return null
        return runCatching {
            BoundedJsonScanner(json).requireSafeStructure(source)
            json.parseToJsonElement(source) as? JsonObject
        }.getOrNull()
    }

    private fun decodeStrictUtf8(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun sign(signingInput: String): ByteArray = Mac.getInstance(HmacAlgorithm).run {
        init(signingKey)
        doFinal(signingInput.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun rejectedMalformedToken(): AccessTokenAuthentication =
        AccessTokenAuthentication.Rejected(AccessTokenRejection.MALFORMED_TOKEN)

    override fun toString(): String = "JwtAccessTokenVerifier([redacted])"

}

private data class VerifiedJwtClaims(
    val issuer: String,
    val audience: String,
    val subject: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val tokenId: String,
)

private class VerifiedJwtPrincipal(
    override val playerId: String,
    override val tokenId: String,
) : AuthenticatedPrincipal {
    override fun toString(): String = "AuthenticatedPrincipal([redacted])"
}

private fun JsonObject.hasExactStringClaims(expected: Map<String, String>): Boolean =
    keys == expected.keys && expected.all { (name, value) ->
        val claim = this[name] as? JsonPrimitive
        claim?.isString == true && claim.content == value
    }

private fun JsonObject.toVerifiedClaims(): VerifiedJwtClaims? {
    if (keys != JwtExpectedClaimNames) return null
    val issuer = stringClaim("iss") ?: return null
    val audience = stringClaim("aud") ?: return null
    val subject = stringClaim("sub")?.takeIf(String::isCanonicalUuid) ?: return null
    val tokenId = stringClaim("jti")?.takeIf(String::isCanonicalUuid) ?: return null
    val issuedAt = integerClaim("iat") ?: return null
    val expiresAt = integerClaim("exp") ?: return null
    return VerifiedJwtClaims(issuer, audience, subject, issuedAt, expiresAt, tokenId)
}

private fun JsonObject.stringClaim(name: String): String? =
    (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?.takeIf { it.length in 1..256 && it.none(Char::isISOControl) }

private fun JsonObject.integerClaim(name: String): Long? =
    (this[name] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.longOrNull

private fun String.isCanonicalUuid(): Boolean =
    runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)

private fun String.isSafePolicyValue(): Boolean =
    length in 1..256 && none { it.isISOControl() || it.isWhitespace() }

private const val HmacAlgorithm: String = "HmacSHA256"
private const val MinimumSigningKeyBytes: Int = 32
private const val JwtSegmentCount: Int = 3
private const val Sha256Bytes: Int = 32
private const val MaximumHeaderBytes: Int = 512
private const val MaximumPayloadBytes: Int = 2_048
private const val MaximumTokenCharacters: Int = 4_096
private const val MaximumAuthorizationCharacters: Int = MaximumTokenCharacters + 16
private const val Base64ExpansionFactor: Int = 2

private val Base64UrlSegment = Regex("[A-Za-z0-9_-]+")
private val BearerHeader = Regex(
    pattern = "Bearer ([A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)",
    option = RegexOption.IGNORE_CASE,
)
private val ExpectedHeader: Map<String, String> = mapOf(
    "alg" to "HS256",
    "typ" to "JWT",
)
private val JwtExpectedClaimNames: Set<String> = setOf("iss", "aud", "sub", "iat", "exp", "jti")
