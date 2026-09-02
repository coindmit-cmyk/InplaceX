package com.mirkori.platform.sdk

import java.nio.charset.StandardCharsets
import java.security.PublicKey
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

data class PlatformProMembershipSnapshot(
    val accountId: String,
    val gameId: String,
    val distributionId: String,
    val active: Boolean,
    val validUntil: Instant?,
    val membershipVersion: Long,
    val participationVersion: Long,
    val benefitContentId: String,
    val policyVersion: Long,
    val serverTime: Instant,
    val expiresAt: Instant,
    val signatureKeyId: String,
    val trustedServerTime: Instant,
) {
    fun timeAnchor(monotonicMilliseconds: Long, bootSessionId: String): PlatformProTimeAnchor {
        require(monotonicMilliseconds >= 0)
        require(bootSessionId.matches(BootSessionIdPattern))
        return PlatformProTimeAnchor(trustedServerTime, monotonicMilliseconds, bootSessionId)
    }

    fun hasBenefitAt(
        anchor: PlatformProTimeAnchor,
        monotonicMilliseconds: Long,
        bootSessionId: String,
    ): Boolean {
        if (!active || validUntil == null || bootSessionId != anchor.bootSessionId ||
            monotonicMilliseconds < anchor.monotonicMilliseconds
        ) return false
        val trustedNow = runCatching {
            anchor.serverTime.plusMillis(monotonicMilliseconds - anchor.monotonicMilliseconds)
        }.getOrNull() ?: return false
        return validUntil.isAfter(trustedNow) && expiresAt.isAfter(trustedNow)
    }
}

enum class PlatformProSessionLeaseStatus(val wireName: String) {
    ACTIVE("active"),
    RELEASED("released");

    internal companion object {
        fun fromWireName(value: String) = entries.firstOrNull { it.wireName == value }
    }
}

data class PlatformProSessionLease(
    val id: String,
    val accountId: String,
    val gameId: String,
    val distributionId: String,
    val installationId: String,
    val sessionId: String,
    val benefitContentId: String,
    val membershipVersion: Long,
    val participationVersion: Long,
    val policyVersion: Long,
    val maxConcurrentSessions: Int,
    val status: PlatformProSessionLeaseStatus,
    val createdAt: Instant,
    val lastHeartbeatAt: Instant,
    val expiresAt: Instant,
    val releasedAt: Instant?,
)

class PlatformProConfigurationUnavailableException(
    val recoveryAction: PlatformRecoveryAction = PlatformRecoveryAction.DO_NOT_RETRY,
) :
    IllegalStateException("Pro game configuration is unavailable")
class PlatformProConcurrencyLimitException(
    val recoveryAction: PlatformRecoveryAction = PlatformRecoveryAction.DO_NOT_RETRY,
) :
    IllegalStateException("Pro concurrent-session limit reached")
enum class PlatformProBenefitUnavailableReason {
    MEMBERSHIP,
    LEASE,
}

class PlatformProBenefitUnavailableException(
    val reason: PlatformProBenefitUnavailableReason = PlatformProBenefitUnavailableReason.MEMBERSHIP,
    val recoveryAction: PlatformRecoveryAction = PlatformRecoveryAction.DO_NOT_RETRY,
) :
    IllegalStateException("Pro benefit is unavailable")

class PlatformProTimeAnchor internal constructor(
    internal val serverTime: Instant,
    internal val monotonicMilliseconds: Long,
    internal val bootSessionId: String,
)

fun interface PlatformProSnapshotSignatureVerifier {
    fun verify(keyId: String, algorithm: String, encodedPayload: String, encodedSignature: String): Boolean
}

class Rs256PlatformProSnapshotSignatureVerifier(
    publicKeys: Map<String, PublicKey>,
) : PlatformProSnapshotSignatureVerifier {
    private val publicKeys = publicKeys.toMap()
    private val decoder = Base64.getUrlDecoder()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    init {
        require(this.publicKeys.isNotEmpty())
        require(this.publicKeys.keys.all { it.matches(KeyIdPattern) })
        require(this.publicKeys.values.all { it.algorithm.equals("RSA", ignoreCase = true) })
    }

    override fun verify(keyId: String, algorithm: String, encodedPayload: String, encodedSignature: String): Boolean {
        if (algorithm != "RS256" || !keyId.matches(KeyIdPattern) ||
            !encodedPayload.matches(Base64UrlPattern) || !encodedSignature.matches(Base64UrlPattern)
        ) return false
        val key = publicKeys[keyId] ?: return false
        val signatureBytes = runCatching { decoder.decode(encodedSignature) }.getOrNull() ?: return false
        if (encoder.encodeToString(signatureBytes) != encodedSignature || signatureBytes.size !in 128..1024) {
            signatureBytes.fill(0)
            return false
        }
        return try {
            Signature.getInstance(SignatureAlgorithm).run {
                initVerify(key)
                update("$SignatureDomain.$encodedPayload".toByteArray(StandardCharsets.US_ASCII))
                verify(signatureBytes)
            }
        } catch (_: Exception) {
            false
        } finally {
            signatureBytes.fill(0)
        }
    }

    override fun toString(): String =
        "Rs256PlatformProSnapshotSignatureVerifier(keyIds=${publicKeys.keys.sorted()}, [redacted])"
}

class PlatformProMembershipSnapshotVerifier(
    private val signatureVerifier: PlatformProSnapshotSignatureVerifier,
) {
    private val json = Json { ignoreUnknownKeys = false; isLenient = false }
    private val decoder = Base64.getUrlDecoder()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun verify(
        envelopeJson: String,
        expectedAccountId: String,
        expectedGameId: String,
        expectedDistributionId: String,
        trustedServerTimeFloor: Instant? = null,
    ): PlatformProMembershipSnapshot {
        requireCanonicalUuid(expectedAccountId)
        require(expectedGameId.matches(GameIdPattern))
        require(expectedDistributionId.matches(DistributionIdPattern))
        val envelope = parseObject(envelopeJson, MaximumEnvelopeBytes)
        require(envelope.keys == setOf("schemaVersion", "type", "payload", "signature"))
        require(envelope.long("schemaVersion") == 1L && envelope.string("type", 64) == SnapshotType)
        val encodedPayload = envelope.string("payload", MaximumEncodedPayloadBytes)
        require(encodedPayload.matches(Base64UrlPattern))
        val signature = envelope["signature"]?.jsonObject ?: reject()
        require(signature.keys == setOf("algorithm", "keyId", "value"))
        val algorithm = signature.string("algorithm", 16)
        val keyId = signature.string("keyId", 64)
        val encodedSignature = signature.string("value", MaximumEncodedSignatureBytes)
        require(signatureVerifier.verify(keyId, algorithm, encodedPayload, encodedSignature))
        val payloadBytes = runCatching { decoder.decode(encodedPayload) }.getOrNull() ?: reject()
        val payload = try {
            require(payloadBytes.size in 2..MaximumDecodedPayloadBytes)
            require(encoder.encodeToString(payloadBytes) == encodedPayload)
            parseObject(payloadBytes.toString(StandardCharsets.UTF_8), MaximumDecodedPayloadBytes)
        } finally {
            payloadBytes.fill(0)
        }
        require(payload.keys == PayloadFields)
        require(payload.long("schemaVersion") == 1L && payload.string("type", 64) == SnapshotType)
        require(payload.boolean("participating"))
        val accountId = payload.string("accountId", 64).also(::requireCanonicalUuid)
        val gameId = payload.string("gameId", 64)
        val distributionId = payload.string("distributionId", 64)
        require(accountId == expectedAccountId && gameId == expectedGameId && distributionId == expectedDistributionId)
        val active = payload.boolean("active")
        val validUntil = payload.nullableInstant("validUntil")
        val membershipVersion = payload.long("membershipVersion")
        val participationVersion = payload.long("participationVersion")
        val benefitContentId = payload.string("benefitContentId", 128)
        val policyVersion = payload.long("policyVersion")
        val serverTime = payload.instant("serverTime")
        val expiresAt = payload.instant("expiresAt")
        require(gameId.matches(GameIdPattern) && distributionId.matches(DistributionIdPattern))
        require(benefitContentId.matches(ContentIdPattern) && participationVersion > 0 && policyVersion > 0)
        require((validUntil == null && membershipVersion == 0L && !active) ||
            (validUntil != null && membershipVersion > 0 && active == validUntil.isAfter(serverTime)))
        val trustedServerTime = maxOf(serverTime, trustedServerTimeFloor ?: serverTime)
        require(expiresAt.isAfter(trustedServerTime))
        return PlatformProMembershipSnapshot(
            accountId, gameId, distributionId, active, validUntil, membershipVersion,
            participationVersion, benefitContentId, policyVersion, serverTime, expiresAt,
            keyId, trustedServerTime,
        )
    }

    private fun parseObject(value: String, maximumBytes: Int): JsonObject {
        require(value.toByteArray(StandardCharsets.UTF_8).size <= maximumBytes)
        return runCatching { json.parseToJsonElement(value).jsonObject }.getOrElse { reject() }
    }
}

private fun JsonObject.string(name: String, maximum: Int): String {
    val value = (get(name) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: reject()
    require(value.length in 1..maximum && value.none(Char::isISOControl))
    return value
}

private fun JsonObject.long(name: String): Long = (get(name) as? JsonPrimitive)?.longOrNull ?: reject()
private fun JsonObject.boolean(name: String): Boolean = (get(name) as? JsonPrimitive)?.booleanOrNull ?: reject()
private fun JsonObject.instant(name: String): Instant = runCatching { Instant.parse(string(name, 40)) }.getOrElse { reject() }
private fun JsonObject.nullableInstant(name: String): Instant? = when (val value = get(name)) {
    JsonNull -> null
    is JsonPrimitive -> runCatching { Instant.parse(value.content) }.getOrElse { reject() }
    else -> reject()
}

private fun requireCanonicalUuid(value: String) = require(
    runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false),
)

private fun reject(): Nothing = throw IllegalArgumentException("Invalid Pro membership snapshot")

private const val SnapshotType = "mirkori.pro.game-membership"
private const val SignatureDomain = "mirkori.pro.game-membership.v1"
private const val SignatureAlgorithm = "SHA256withRSA"
private const val MaximumEnvelopeBytes = 32 * 1024
private const val MaximumEncodedPayloadBytes = 24 * 1024
private const val MaximumDecodedPayloadBytes = 16 * 1024
private const val MaximumEncodedSignatureBytes = 4 * 1024
private val KeyIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")
private val Base64UrlPattern = Regex("[A-Za-z0-9_-]{2,65535}")
private val GameIdPattern = Regex("[a-z0-9][a-z0-9._-]{1,63}")
private val DistributionIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{1,63}")
private val ContentIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val BootSessionIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")
private val PayloadFields = setOf(
    "schemaVersion", "type", "accountId", "gameId", "distributionId", "participating",
    "active", "validUntil", "membershipVersion", "participationVersion", "benefitContentId",
    "policyVersion", "serverTime", "expiresAt",
)
