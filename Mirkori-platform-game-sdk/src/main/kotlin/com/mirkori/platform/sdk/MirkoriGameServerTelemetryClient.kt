package com.mirkori.platform.sdk

import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class MirkoriGameServerTelemetryConfig(
    val platformBaseUrl: String,
    val gameId: String,
    val allowCleartextLoopback: Boolean = false,
)

class MirkoriGameServerCredential private constructor(
    private val token: String,
) {
    internal fun authorizationHeader(): String = "MirkoriGame $token"

    override fun toString(): String = "MirkoriGameServerCredential([redacted])"

    companion object {
        fun fromToken(value: String): MirkoriGameServerCredential {
            val separator = value.indexOf('.')
            require(separator > 0 && separator == value.lastIndexOf('.') && separator < value.lastIndex)
            require(value.substring(0, separator).isCanonicalTelemetryUuid())
            require(value.substring(separator + 1).matches(GameServerSecretPattern))
            return MirkoriGameServerCredential(value)
        }
    }
}

data class PlatformAchievementFact(
    val eventId: String,
    val gameProfileId: String,
    val achievementId: String,
    val achievedAt: Instant,
)

enum class PlatformAchievementTelemetryDecision(val wireName: String) {
    ELIGIBLE("eligible"),
    BEFORE_ACTIVATION("before_activation"),
    NO_REFERRAL("no_referral"),
    ACHIEVEMENT_NOT_CONFIGURED("achievement_not_configured"),
    AWARD_LIMIT_REACHED("award_limit_reached");

    internal companion object {
        fun fromWireName(value: String): PlatformAchievementTelemetryDecision? =
            entries.firstOrNull { it.wireName == value }
    }
}

data class PlatformAchievementTelemetryResult(
    val eventId: String,
    val gameId: String,
    val decision: PlatformAchievementTelemetryDecision,
    val policyVersionId: String?,
    val candidateId: String?,
)

enum class PlatformGameplayEventType(val wireName: String) {
    START("start"),
    HEARTBEAT("heartbeat"),
    END("end"),
}

data class PlatformGameplayFact(
    val eventId: String,
    val gameProfileId: String,
    val sessionId: String,
    val sequenceNumber: Long,
    val eventType: PlatformGameplayEventType,
    val occurredAt: Instant,
)

enum class PlatformGameplayTelemetryDecision(val wireName: String) {
    ACCEPTED("accepted"),
    CONFLICTING("conflicting");

    internal companion object {
        fun fromWireName(value: String): PlatformGameplayTelemetryDecision? =
            entries.firstOrNull { it.wireName == value }
    }
}

enum class PlatformGameplaySessionStatus(val wireName: String) {
    OPEN("open"),
    ENDED("ended"),
    CONFLICTING("conflicting");

    internal companion object {
        fun fromWireName(value: String): PlatformGameplaySessionStatus? =
            entries.firstOrNull { it.wireName == value }
    }
}

data class PlatformGameplayTelemetryResult(
    val eventId: String,
    val gameId: String,
    val sessionId: String,
    val decision: PlatformGameplayTelemetryDecision,
    val sessionStatus: PlatformGameplaySessionStatus,
    val trustedDurationSeconds: Long,
)

/** Backend-only trusted telemetry client. Its credential must never be included in an Android artifact. */
class MirkoriGameServerTelemetryClient(
    val config: MirkoriGameServerTelemetryConfig,
    private val transport: PlatformTransport,
) {
    private val baseUri: URI = validateTelemetryBaseUrl(config.platformBaseUrl, config.allowCleartextLoopback)
    private val codec = GameServerTelemetryCodec()

    init {
        require(config.gameId.matches(GameIdPattern))
    }

    suspend fun submitAchievement(
        credential: MirkoriGameServerCredential,
        fact: PlatformAchievementFact,
    ): PlatformAchievementTelemetryResult {
        validateAchievement(fact)
        return codec.achievementResponse(
            post(
                path = "/api/v1/games/${config.gameId}/telemetry/achievements",
                credential = credential,
                body = codec.achievementRequest(fact),
            ),
        ).also { result ->
            require(result.eventId == fact.eventId && result.gameId == config.gameId)
            when (result.decision) {
                PlatformAchievementTelemetryDecision.ELIGIBLE -> {
                    require(result.policyVersionId.isCanonicalTelemetryUuid())
                    require(result.candidateId.isCanonicalTelemetryUuid())
                }
                PlatformAchievementTelemetryDecision.ACHIEVEMENT_NOT_CONFIGURED,
                PlatformAchievementTelemetryDecision.AWARD_LIMIT_REACHED,
                -> {
                    require(result.policyVersionId.isCanonicalTelemetryUuid())
                    require(result.candidateId == null)
                }
                PlatformAchievementTelemetryDecision.BEFORE_ACTIVATION,
                PlatformAchievementTelemetryDecision.NO_REFERRAL,
                -> require(result.policyVersionId == null && result.candidateId == null)
            }
        }
    }

    suspend fun submitGameplay(
        credential: MirkoriGameServerCredential,
        fact: PlatformGameplayFact,
    ): PlatformGameplayTelemetryResult {
        validateGameplay(fact)
        return codec.gameplayResponse(
            post(
                path = "/api/v1/games/${config.gameId}/telemetry/gameplay",
                credential = credential,
                body = codec.gameplayRequest(fact),
            ),
        ).also { result ->
            require(
                result.eventId == fact.eventId && result.gameId == config.gameId &&
                    result.sessionId == fact.sessionId && result.trustedDurationSeconds >= 0
            )
            when (result.decision) {
                PlatformGameplayTelemetryDecision.ACCEPTED -> {
                    val expectedStatus = if (fact.eventType == PlatformGameplayEventType.END) {
                        PlatformGameplaySessionStatus.ENDED
                    } else {
                        PlatformGameplaySessionStatus.OPEN
                    }
                    require(result.sessionStatus == expectedStatus)
                }
                PlatformGameplayTelemetryDecision.CONFLICTING ->
                    require(result.sessionStatus == PlatformGameplaySessionStatus.CONFLICTING)
            }
        }
    }

    private suspend fun post(
        path: String,
        credential: MirkoriGameServerCredential,
        body: String,
    ): String {
        require(path.startsWith('/') && !path.startsWith("//"))
        val response = transport.execute(
            PlatformHttpRequest(
                method = PlatformHttpMethod.POST,
                url = baseUri.toASCIIString().removeSuffix("/") + path,
                headers = linkedMapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "Authorization" to credential.authorizationHeader(),
                ),
                body = body,
            ),
        )
        if (response.status !in 200..299) {
            throw PlatformApiException(response.status, codec.errorCode(response.body))
        }
        return response.body
    }

    private fun validateAchievement(fact: PlatformAchievementFact) {
        require(fact.eventId.matches(ScopedEventIdPattern))
        require(fact.gameProfileId.isCanonicalTelemetryUuid())
        require(fact.achievementId.matches(AchievementIdPattern))
    }

    private fun validateGameplay(fact: PlatformGameplayFact) {
        require(fact.eventId.matches(ScopedEventIdPattern))
        require(fact.gameProfileId.isCanonicalTelemetryUuid())
        require(fact.sessionId.matches(ScopedEventIdPattern))
        require(fact.sequenceNumber in 0..MaximumGameplaySequence)
        require((fact.eventType == PlatformGameplayEventType.START) == (fact.sequenceNumber == 0L))
    }

    private companion object {
        val GameIdPattern = Regex("[a-z0-9][a-z0-9-]{0,63}")
        val ScopedEventIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,62}")
        val AchievementIdPattern = Regex("[a-z0-9][a-z0-9._-]{0,127}")
        const val MaximumGameplaySequence = 1_000_000_000L
    }
}

private class GameServerTelemetryCodec {
    private val json = Json

    fun achievementRequest(fact: PlatformAchievementFact): String = buildJsonObject {
        put("schemaVersion", 1)
        put("eventId", fact.eventId)
        put("gameProfileId", fact.gameProfileId)
        put("achievementId", fact.achievementId)
        put("achievedAt", fact.achievedAt.toString())
    }.toString()

    fun gameplayRequest(fact: PlatformGameplayFact): String = buildJsonObject {
        put("schemaVersion", 1)
        put("eventId", fact.eventId)
        put("gameProfileId", fact.gameProfileId)
        put("sessionId", fact.sessionId)
        put("sequenceNumber", fact.sequenceNumber)
        put("eventType", fact.eventType.wireName)
        put("occurredAt", fact.occurredAt.toString())
    }.toString()

    fun achievementResponse(body: String): PlatformAchievementTelemetryResult {
        val root = objectBody(body)
        val required = setOf("schemaVersion", "eventId", "gameId", "decision")
        val optional = setOf("policyVersionId", "candidateId")
        require(root.keys.containsAll(required) && root.keys.all { it in required || it in optional })
        require(root.long("schemaVersion") == 1L)
        return PlatformAchievementTelemetryResult(
            eventId = root.string("eventId", 63),
            gameId = root.string("gameId", 64),
            decision = PlatformAchievementTelemetryDecision.fromWireName(root.string("decision", 64))
                ?: invalidResponse(),
            policyVersionId = root.optionalString("policyVersionId", 64),
            candidateId = root.optionalString("candidateId", 64),
        )
    }

    fun gameplayResponse(body: String): PlatformGameplayTelemetryResult {
        val root = objectBody(body)
        root.requireExactFields(
            "schemaVersion",
            "eventId",
            "gameId",
            "sessionId",
            "decision",
            "sessionStatus",
            "trustedDurationSeconds",
        )
        require(root.long("schemaVersion") == 1L)
        return PlatformGameplayTelemetryResult(
            eventId = root.string("eventId", 63),
            gameId = root.string("gameId", 64),
            sessionId = root.string("sessionId", 63),
            decision = PlatformGameplayTelemetryDecision.fromWireName(root.string("decision", 32))
                ?: invalidResponse(),
            sessionStatus = PlatformGameplaySessionStatus.fromWireName(root.string("sessionStatus", 32))
                ?: invalidResponse(),
            trustedDurationSeconds = root.long("trustedDurationSeconds"),
        )
    }

    fun errorCode(body: String): String = runCatching {
        val root = objectBody(body)
        root.requireExactFields("error")
        root.string("error", 64).takeIf { it.matches(ErrorCodePattern) } ?: invalidResponse()
    }.getOrDefault("invalid_response")

    private fun objectBody(body: String): JsonObject {
        require(body.toByteArray(Charsets.UTF_8).size in 2..MaximumResponseBytes)
        return runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { invalidResponse() }
    }

    private fun JsonObject.requireExactFields(vararg names: String) {
        require(keys == names.toSet())
    }

    private fun JsonObject.string(name: String, maximum: Int): String {
        val primitive = get(name) as? JsonPrimitive ?: invalidResponse()
        require(primitive.isString)
        return primitive.content.takeIf { it.length in 1..maximum && it.none(Char::isISOControl) }
            ?: invalidResponse()
    }

    private fun JsonObject.optionalString(name: String, maximum: Int): String? {
        val element = get(name) ?: return null
        val primitive = element as? JsonPrimitive ?: invalidResponse()
        require(primitive.isString)
        return primitive.contentOrNull?.takeIf { it.length in 1..maximum && it.none(Char::isISOControl) }
            ?: invalidResponse()
    }

    private fun JsonObject.long(name: String): Long =
        get(name)?.jsonPrimitive?.longOrNull ?: invalidResponse()

    private fun invalidResponse(): Nothing = throw IllegalArgumentException("Invalid platform response")

    private companion object {
        val ErrorCodePattern = Regex("[a-z][a-z0-9_]{0,63}")
        const val MaximumResponseBytes = 64 * 1024
    }
}

private fun validateTelemetryBaseUrl(value: String, allowCleartextLoopback: Boolean): URI {
    val uri = URI(value)
    require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
    require(uri.path.isNullOrEmpty() || uri.path == "/")
    val secure = uri.scheme.equals("https", ignoreCase = true)
    val loopback = uri.scheme.equals("http", ignoreCase = true) && uri.host.lowercase() in TelemetryLoopbackHosts
    require(secure || allowCleartextLoopback && loopback)
    require(telemetryEffectivePort(uri) in 1..65535)
    return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, null, null, null)
}

private fun telemetryEffectivePort(uri: URI): Int = when {
    uri.port >= 0 -> uri.port
    uri.scheme.equals("https", ignoreCase = true) -> 443
    uri.scheme.equals("http", ignoreCase = true) -> 80
    else -> -1
}

private fun String?.isCanonicalTelemetryUuid(): Boolean =
    this != null && runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)

private val TelemetryLoopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")
private val GameServerSecretPattern = Regex("[A-Za-z0-9_-]{43,128}")
