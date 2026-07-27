package com.mirkori.inplacex.backend.auth

import com.mirkori.inplacex.backend.session.codec.BoundedJsonScanner
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.PublicKey
import java.security.Signature
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

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
        require(issuer.isSafePolicyValue())
        require(audience.isSafePolicyValue())
        require(!maximumTokenLifetime.isNegative && !maximumTokenLifetime.isZero)
        require(maximumTokenLifetime <= Duration.ofHours(1))
        require(!allowedClockSkew.isNegative && allowedClockSkew <= Duration.ofMinutes(2))
    }
}

/**
 * Game-API verification boundary. Only an RSA public key is accepted here;
 * the identity signing key lives in a separate process composition.
 */
class JwtAccessTokenVerifier(
    private val verificationKey: PublicKey,
    private val policy: JwtVerificationPolicy,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val decoder = Base64.getUrlDecoder()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    init {
        require(verificationKey.algorithm.equals("RSA", ignoreCase = true)) {
            "RS256 verifier requires an RSA public key"
        }
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
        if (token.length > MaximumTokenCharacters) return malformed()
        val segments = token.split('.')
        if (segments.size != JwtSegmentCount || segments.any { !it.matches(Base64UrlSegment) }) {
            return malformed()
        }

        val headerBytes = decodeCanonical(segments[0], MaximumHeaderBytes) ?: return malformed()
        val payloadBytes = decodeCanonical(segments[1], MaximumPayloadBytes) ?: return malformed()
        val signatureBytes = decodeCanonical(segments[2], MaximumSignatureBytes) ?: return malformed()
        val header = decodeJsonObject(headerBytes) ?: return malformed()
        if (!header.hasExactStringClaims(ExpectedHeader)) {
            return AccessTokenAuthentication.Rejected(AccessTokenRejection.INVALID_CLAIMS)
        }

        val acceptedSignature = runCatching {
            Signature.getInstance(SignatureAlgorithm).run {
                initVerify(verificationKey)
                update("${segments[0]}.${segments[1]}".toByteArray(StandardCharsets.US_ASCII))
                verify(signatureBytes)
            }
        }.getOrDefault(false)
        signatureBytes.fill(0)
        if (!acceptedSignature) {
            return AccessTokenAuthentication.Rejected(AccessTokenRejection.INVALID_SIGNATURE)
        }

        val claims = decodeJsonObject(payloadBytes)?.toVerifiedClaims()
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
            VerifiedJwtPrincipal(claims.subject, claims.tokenId),
        )
    }

    private fun parseBearerToken(header: String): String? {
        if (header.length > MaximumAuthorizationCharacters || header.any(Char::isISOControl)) return null
        return BearerHeader.matchEntire(header)?.groupValues?.get(1)
    }

    private fun decodeCanonical(value: String, maximumBytes: Int): ByteArray? {
        if (value.length > maximumBytes * Base64ExpansionFactor) return null
        val decoded = runCatching { decoder.decode(value) }.getOrNull() ?: return null
        return decoded.takeIf { it.size <= maximumBytes && encoder.encodeToString(it) == value }
    }

    private fun decodeJsonObject(bytes: ByteArray): JsonObject? {
        val source = runCatching {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull() ?: return null
        return runCatching {
            BoundedJsonScanner(json).requireSafeStructure(source)
            json.parseToJsonElement(source) as? JsonObject
        }.getOrNull()
    }

    private fun malformed(): AccessTokenAuthentication =
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
    if (keys != ExpectedClaimNames) return null
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

private const val JwtSegmentCount = 3
private const val MaximumHeaderBytes = 512
private const val MaximumPayloadBytes = 2_048
private const val MaximumSignatureBytes = 512
private const val MaximumTokenCharacters = 4_096
private const val MaximumAuthorizationCharacters = MaximumTokenCharacters + 16
private const val Base64ExpansionFactor = 2
private const val SignatureAlgorithm = "SHA256withRSA"
private val Base64UrlSegment = Regex("[A-Za-z0-9_-]+")
private val BearerHeader = Regex(
    pattern = "Bearer ([A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)",
    option = RegexOption.IGNORE_CASE,
)
private val ExpectedHeader = mapOf("alg" to "RS256", "typ" to "JWT")
private val ExpectedClaimNames = setOf("iss", "aud", "sub", "iat", "exp", "jti")
