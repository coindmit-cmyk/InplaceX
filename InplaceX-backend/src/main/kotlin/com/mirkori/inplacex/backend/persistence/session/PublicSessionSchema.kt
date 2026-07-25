package com.mirkori.inplacex.backend.persistence.session

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

const val MAX_PUBLIC_SESSION_FRAME_BYTES: Int = 64 * 1024

private val OPAQUE_ID = Regex("[A-Za-z0-9._~-]{1,64}")

data class PublicGameConfig(
    val codeLength: Int,
    val attemptLimit: Int,
) {
    init {
        require(codeLength in 4..20) { "Public game code length must be in 4..20" }
        require(attemptLimit in 1..1_000) { "Public game attempt limit must be in 1..1000" }
    }
}

enum class PublicDuelPhase {
    SETUP_WAITING_FOR_PLAYERS,
    SETUP_SECRET_A,
    SETUP_SECRET_B,
    ACTIVE_TURN_A,
    ACTIVE_TURN_B,
    FINISHED,
    ABANDONED,
}

enum class PublicParticipantSlot {
    A,
    B,
}

enum class PublicParticipantType {
    HUMAN,
    BOT,
}

data class PublicDuelParticipant(
    val participantId: String,
    val slot: PublicParticipantSlot,
    val participantType: PublicParticipantType,
    val secretSubmitted: Boolean,
    val connected: Boolean,
) {
    init {
        requireOpaqueId(participantId, "participantId")
    }
}

data class PublicDuelTurn(
    val turnNumber: Int,
    val actorParticipantId: String,
    val exactMatches: Int,
    val solved: Boolean,
) {
    init {
        require(turnNumber > 0) { "Public turn number must be positive" }
        requireOpaqueId(actorParticipantId, "actorParticipantId")
        require(exactMatches >= 0) { "Public exact match count must not be negative" }
    }
}

data class PublicDuelSessionSnapshot(
    val sessionId: String,
    val revision: Long,
    val eventSequence: Long,
    val phase: PublicDuelPhase,
    val config: PublicGameConfig,
    val participants: List<PublicDuelParticipant>,
    val turns: List<PublicDuelTurn> = emptyList(),
    val currentActorParticipantId: String? = null,
    val winnerParticipantId: String? = null,
) {
    init {
        requireOpaqueId(sessionId, "sessionId")
        require(revision >= 0) { "Public session revision must not be negative" }
        require(eventSequence >= 0) { "Public event sequence must not be negative" }
        require(participants.size <= 2) { "A duel snapshot cannot contain more than two participants" }
        require(participants.map { it.participantId }.distinct().size == participants.size) {
            "Public participant ids must be unique"
        }
        require(participants.map { it.slot }.distinct().size == participants.size) {
            "Public participant slots must be unique"
        }
        require(turns.size <= 2_048) { "Public turn history exceeds the supported bound" }
        require(turns.zipWithNext().all { (left, right) -> left.turnNumber < right.turnNumber }) {
            "Public turns must be strictly ordered"
        }
        require(turns.all { it.exactMatches <= config.codeLength }) {
            "Public exact match count exceeds code length"
        }
        val participantIds = participants.mapTo(mutableSetOf()) { it.participantId }
        require(participantIds.isEmpty() || turns.all { it.actorParticipantId in participantIds }) {
            "Public turn actor must belong to the session"
        }
        require(currentActorParticipantId == null || currentActorParticipantId in participantIds) {
            "Current actor must belong to the session"
        }
        require(winnerParticipantId == null || winnerParticipantId in participantIds) {
            "Winner must belong to the session"
        }
    }
}

sealed interface PublicDuelSessionEvent {
    val type: String

    data class ParticipantPresenceChanged(
        val participantId: String,
        val connected: Boolean,
    ) : PublicDuelSessionEvent {
        override val type: String = "session.participantPresenceChanged"

        init {
            requireOpaqueId(participantId, "participantId")
        }
    }

    data class SecretStatusChanged(
        val participantId: String,
        val secretSubmitted: Boolean,
    ) : PublicDuelSessionEvent {
        override val type: String = "duel.secretStatusChanged"

        init {
            requireOpaqueId(participantId, "participantId")
        }
    }

    data class TurnResult(
        val turnNumber: Int,
        val actorParticipantId: String,
        val exactMatches: Int,
        val solved: Boolean,
    ) : PublicDuelSessionEvent {
        override val type: String = "duel.turnResult"

        init {
            require(turnNumber > 0) { "Turn result number must be positive" }
            requireOpaqueId(actorParticipantId, "actorParticipantId")
            require(exactMatches >= 0) { "Turn result exact matches must not be negative" }
        }
    }

    data class PhaseChanged(
        val phase: PublicDuelPhase,
        val currentActorParticipantId: String? = null,
    ) : PublicDuelSessionEvent {
        override val type: String = "duel.phaseChanged"

        init {
            currentActorParticipantId?.let { requireOpaqueId(it, "currentActorParticipantId") }
        }
    }

    data class Finished(
        val winnerParticipantId: String? = null,
    ) : PublicDuelSessionEvent {
        override val type: String = "duel.finished"

        init {
            winnerParticipantId?.let { requireOpaqueId(it, "winnerParticipantId") }
        }
    }
}

sealed interface PublicDuelCommandResult {
    val type: String

    data class SecretAccepted(
        val participantId: String,
        val secretSubmitted: Boolean = true,
    ) : PublicDuelCommandResult {
        override val type: String = "duel.secretAccepted"

        init {
            requireOpaqueId(participantId, "participantId")
        }
    }

    data class TurnAccepted(
        val turnNumber: Int,
        val exactMatches: Int,
        val solved: Boolean,
    ) : PublicDuelCommandResult {
        override val type: String = "duel.turnAccepted"

        init {
            require(turnNumber > 0) { "Accepted turn number must be positive" }
            require(exactMatches >= 0) { "Accepted exact matches must not be negative" }
        }
    }

    data class PresenceAccepted(
        val participantId: String,
        val connected: Boolean,
    ) : PublicDuelCommandResult {
        override val type: String = "session.presenceAccepted"

        init {
            requireOpaqueId(participantId, "participantId")
        }
    }

    data class PhaseAccepted(
        val phase: PublicDuelPhase,
    ) : PublicDuelCommandResult {
        override val type: String = "duel.phaseAccepted"
    }
}

sealed class DurableDuelCommandContent private constructor() {
    internal abstract fun fingerprintPayload(): JsonObject

    object RecordSecretStatus : DurableDuelCommandContent() {
        override fun fingerprintPayload(): JsonObject = jsonObject(
            "type" to JsonPrimitive("duel.recordSecretStatus"),
        )

        override fun toString(): String = "RecordSecretStatus"
    }

    class SubmitSecret private constructor(private val valueDigest: String) : DurableDuelCommandContent() {
        override fun fingerprintPayload(): JsonObject = jsonObject(
            "type" to JsonPrimitive("duel.submitSecret"),
            "valueDigest" to JsonPrimitive(valueDigest),
        )

        override fun toString(): String = "SubmitSecret([redacted])"

        companion object {
            fun validated(secret: String): SubmitSecret {
                requireDigits(secret, "secret")
                return SubmitSecret(sha256Hex(secret))
            }
        }
    }

    class SubmitGuess private constructor(private val valueDigest: String) : DurableDuelCommandContent() {
        override fun fingerprintPayload(): JsonObject = jsonObject(
            "type" to JsonPrimitive("duel.submitGuess"),
            "valueDigest" to JsonPrimitive(valueDigest),
        )

        override fun toString(): String = "SubmitGuess([redacted])"

        companion object {
            fun validated(guess: String): SubmitGuess {
                requireDigits(guess, "guess")
                return SubmitGuess(sha256Hex(guess))
            }
        }
    }

    data class SetPresence(val connected: Boolean) : DurableDuelCommandContent() {
        override fun fingerprintPayload(): JsonObject = jsonObject(
            "type" to JsonPrimitive("session.setPresence"),
            "connected" to JsonPrimitive(connected),
        )
    }

    data class AdvancePhase(val phase: PublicDuelPhase) : DurableDuelCommandContent() {
        override fun fingerprintPayload(): JsonObject = jsonObject(
            "type" to JsonPrimitive("duel.advancePhase"),
            "phase" to JsonPrimitive(phase.name),
        )
    }
}

object PublicSessionJson {
    private val parser = Json {
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    fun encodeConfig(config: PublicGameConfig): String = encodeBoundary(configElement(config))

    fun decodeConfig(json: String): PublicGameConfig {
        val objectValue = parseBoundary(json).jsonObject
        requireFields(objectValue, required = emptySet(), allowed = setOf("codeLength", "length", "attemptLimit"))
        require(!("codeLength" in objectValue && "length" in objectValue)) {
            "Config must not contain both codeLength and legacy length"
        }
        return PublicGameConfig(
            codeLength = objectValue.optionalInt("codeLength")
                ?: objectValue.optionalInt("length")
                ?: 4,
            attemptLimit = objectValue.optionalInt("attemptLimit") ?: 10,
        )
    }

    fun decodeLegacySecretStatus(
        json: String,
        defaultParticipantId: String,
    ): PublicDuelSessionEvent.SecretStatusChanged {
        val objectValue = parseBoundary(json).jsonObject
        requireFields(
            objectValue,
            required = emptySet(),
            allowed = setOf("participantId", "secretSubmitted"),
        )
        val participantId = objectValue.optionalString("participantId") ?: defaultParticipantId
        val submitted = objectValue["secretSubmitted"]?.let { value ->
            value.jsonPrimitive.takeUnless { it.isString }?.booleanOrNull
                ?: throw IllegalArgumentException("Legacy secretSubmitted must be a boolean")
        } ?: true
        require(submitted) { "Legacy secret-status command may only record submitted=true" }
        return PublicDuelSessionEvent.SecretStatusChanged(participantId, submitted)
    }

    fun encodeSnapshot(snapshot: PublicDuelSessionSnapshot): String = encodeBoundary(snapshotElement(snapshot))

    fun decodeSnapshot(json: String): PublicDuelSessionSnapshot {
        val value = parseBoundary(json).jsonObject
        requireFields(
            value,
            required = setOf(
                "schemaVersion",
                "sessionId",
                "revision",
                "eventSeq",
                "phase",
                "config",
                "participants",
                "turns",
            ),
            allowed = setOf(
                "schemaVersion",
                "sessionId",
                "revision",
                "eventSeq",
                "phase",
                "config",
                "participants",
                "turns",
                "currentActorParticipantId",
                "winnerParticipantId",
            ),
        )
        require(value.requiredString("schemaVersion") == "1.0") { "Unsupported public snapshot schema version" }
        return PublicDuelSessionSnapshot(
            sessionId = value.requiredString("sessionId"),
            revision = value.requiredLong("revision"),
            eventSequence = value.requiredLong("eventSeq"),
            phase = enumValue<PublicDuelPhase>(value.requiredString("phase"), "phase"),
            config = decodeConfigElement(value.getValue("config")),
            participants = value.getValue("participants").jsonArray.map(::decodeParticipant),
            turns = value.getValue("turns").jsonArray.map(::decodeTurn),
            currentActorParticipantId = value.optionalString("currentActorParticipantId"),
            winnerParticipantId = value.optionalString("winnerParticipantId"),
        )
    }

    fun encodeEventPayload(event: PublicDuelSessionEvent): String = encodeBoundary(eventPayload(event))

    fun decodeEvent(type: String, payloadJson: String): PublicDuelSessionEvent {
        val payload = parseBoundary(payloadJson).jsonObject
        return when (type) {
            "session.participantPresenceChanged" -> {
                requireFields(payload, setOf("participantId", "connected"))
                PublicDuelSessionEvent.ParticipantPresenceChanged(
                    participantId = payload.requiredString("participantId"),
                    connected = payload.requiredBoolean("connected"),
                )
            }

            "duel.secretStatusChanged" -> {
                requireFields(payload, setOf("participantId", "secretSubmitted"))
                PublicDuelSessionEvent.SecretStatusChanged(
                    participantId = payload.requiredString("participantId"),
                    secretSubmitted = payload.requiredBoolean("secretSubmitted"),
                )
            }

            "duel.turnResult" -> {
                requireFields(payload, setOf("turnNumber", "actorParticipantId", "exactMatches", "solved"))
                PublicDuelSessionEvent.TurnResult(
                    turnNumber = payload.requiredInt("turnNumber"),
                    actorParticipantId = payload.requiredString("actorParticipantId"),
                    exactMatches = payload.requiredInt("exactMatches"),
                    solved = payload.requiredBoolean("solved"),
                )
            }

            "duel.phaseChanged" -> {
                requireFields(
                    payload,
                    required = setOf("phase"),
                    allowed = setOf("phase", "currentActorParticipantId"),
                )
                PublicDuelSessionEvent.PhaseChanged(
                    phase = enumValue(payload.requiredString("phase"), "phase"),
                    currentActorParticipantId = payload.optionalString("currentActorParticipantId"),
                )
            }

            "duel.finished" -> {
                requireFields(payload, required = emptySet(), allowed = setOf("winnerParticipantId"))
                PublicDuelSessionEvent.Finished(
                    winnerParticipantId = payload.optionalString("winnerParticipantId"),
                )
            }

            else -> throw IllegalArgumentException("Unknown public duel event type")
        }
    }

    fun encodeEventFrame(event: PublicDuelSessionEvent): String = encodeBoundary(
        jsonObject(
            "type" to JsonPrimitive(event.type),
            "payload" to eventPayload(event),
        ),
    )

    fun decodeEventFrame(json: String): PublicDuelSessionEvent {
        val frame = parseBoundary(json).jsonObject
        requireFields(frame, setOf("type", "payload"))
        val payload = encodeBoundary(frame.getValue("payload"))
        return decodeEvent(frame.requiredString("type"), payload)
    }

    fun encodeResultPayload(result: PublicDuelCommandResult): String = encodeBoundary(resultPayload(result))

    fun decodeResult(type: String, payloadJson: String): PublicDuelCommandResult {
        val payload = parseBoundary(payloadJson).jsonObject
        return when (type) {
            "duel.secretAccepted" -> {
                requireFields(payload, setOf("participantId", "secretSubmitted"))
                PublicDuelCommandResult.SecretAccepted(
                    participantId = payload.requiredString("participantId"),
                    secretSubmitted = payload.requiredBoolean("secretSubmitted"),
                )
            }

            "duel.turnAccepted" -> {
                requireFields(payload, setOf("turnNumber", "exactMatches", "solved"))
                PublicDuelCommandResult.TurnAccepted(
                    turnNumber = payload.requiredInt("turnNumber"),
                    exactMatches = payload.requiredInt("exactMatches"),
                    solved = payload.requiredBoolean("solved"),
                )
            }

            "session.presenceAccepted" -> {
                requireFields(payload, setOf("participantId", "connected"))
                PublicDuelCommandResult.PresenceAccepted(
                    participantId = payload.requiredString("participantId"),
                    connected = payload.requiredBoolean("connected"),
                )
            }

            "duel.phaseAccepted" -> {
                requireFields(payload, setOf("phase"))
                PublicDuelCommandResult.PhaseAccepted(
                    phase = enumValue(payload.requiredString("phase"), "phase"),
                )
            }

            else -> throw IllegalArgumentException("Unknown public duel command result type")
        }
    }

    internal fun requestFingerprint(expectedRevision: Long, content: DurableDuelCommandContent): String {
        require(expectedRevision >= 0) { "Expected revision must not be negative" }
        val canonicalRequest = encodeCanonical(
            jsonObject(
                "content" to content.fingerprintPayload(),
                "expectedRevision" to JsonPrimitive(expectedRevision),
            ),
        )
        return sha256Hex(canonicalRequest)
    }

    private fun snapshotElement(snapshot: PublicDuelSessionSnapshot): JsonObject = jsonObject(
        "schemaVersion" to JsonPrimitive("1.0"),
        "sessionId" to JsonPrimitive(snapshot.sessionId),
        "revision" to JsonPrimitive(snapshot.revision),
        "eventSeq" to JsonPrimitive(snapshot.eventSequence),
        "phase" to JsonPrimitive(snapshot.phase.name),
        "config" to configElement(snapshot.config),
        "participants" to JsonArray(snapshot.participants.map(::participantElement)),
        "turns" to JsonArray(snapshot.turns.map(::turnElement)),
        "currentActorParticipantId" to snapshot.currentActorParticipantId?.let(::JsonPrimitive),
        "winnerParticipantId" to snapshot.winnerParticipantId?.let(::JsonPrimitive),
    )

    private fun configElement(config: PublicGameConfig): JsonObject = jsonObject(
        "attemptLimit" to JsonPrimitive(config.attemptLimit),
        "codeLength" to JsonPrimitive(config.codeLength),
    )

    private fun participantElement(participant: PublicDuelParticipant): JsonObject = jsonObject(
        "connected" to JsonPrimitive(participant.connected),
        "participantId" to JsonPrimitive(participant.participantId),
        "participantType" to JsonPrimitive(participant.participantType.name),
        "secretSubmitted" to JsonPrimitive(participant.secretSubmitted),
        "slot" to JsonPrimitive(participant.slot.name),
    )

    private fun turnElement(turn: PublicDuelTurn): JsonObject = jsonObject(
        "actorParticipantId" to JsonPrimitive(turn.actorParticipantId),
        "exactMatches" to JsonPrimitive(turn.exactMatches),
        "solved" to JsonPrimitive(turn.solved),
        "turnNumber" to JsonPrimitive(turn.turnNumber),
    )

    private fun eventPayload(event: PublicDuelSessionEvent): JsonObject = when (event) {
        is PublicDuelSessionEvent.ParticipantPresenceChanged -> jsonObject(
            "connected" to JsonPrimitive(event.connected),
            "participantId" to JsonPrimitive(event.participantId),
        )

        is PublicDuelSessionEvent.SecretStatusChanged -> jsonObject(
            "participantId" to JsonPrimitive(event.participantId),
            "secretSubmitted" to JsonPrimitive(event.secretSubmitted),
        )

        is PublicDuelSessionEvent.TurnResult -> jsonObject(
            "actorParticipantId" to JsonPrimitive(event.actorParticipantId),
            "exactMatches" to JsonPrimitive(event.exactMatches),
            "solved" to JsonPrimitive(event.solved),
            "turnNumber" to JsonPrimitive(event.turnNumber),
        )

        is PublicDuelSessionEvent.PhaseChanged -> jsonObject(
            "currentActorParticipantId" to event.currentActorParticipantId?.let(::JsonPrimitive),
            "phase" to JsonPrimitive(event.phase.name),
        )

        is PublicDuelSessionEvent.Finished -> jsonObject(
            "winnerParticipantId" to event.winnerParticipantId?.let(::JsonPrimitive),
        )
    }

    private fun resultPayload(result: PublicDuelCommandResult): JsonObject = when (result) {
        is PublicDuelCommandResult.SecretAccepted -> jsonObject(
            "participantId" to JsonPrimitive(result.participantId),
            "secretSubmitted" to JsonPrimitive(result.secretSubmitted),
        )

        is PublicDuelCommandResult.TurnAccepted -> jsonObject(
            "exactMatches" to JsonPrimitive(result.exactMatches),
            "solved" to JsonPrimitive(result.solved),
            "turnNumber" to JsonPrimitive(result.turnNumber),
        )

        is PublicDuelCommandResult.PresenceAccepted -> jsonObject(
            "connected" to JsonPrimitive(result.connected),
            "participantId" to JsonPrimitive(result.participantId),
        )

        is PublicDuelCommandResult.PhaseAccepted -> jsonObject(
            "phase" to JsonPrimitive(result.phase.name),
        )
    }

    private fun decodeConfigElement(element: JsonElement): PublicGameConfig {
        val value = element.jsonObject
        requireFields(value, setOf("attemptLimit", "codeLength"))
        return PublicGameConfig(
            codeLength = value.requiredInt("codeLength"),
            attemptLimit = value.requiredInt("attemptLimit"),
        )
    }

    private fun decodeParticipant(element: JsonElement): PublicDuelParticipant {
        val value = element.jsonObject
        requireFields(
            value,
            setOf("connected", "participantId", "participantType", "secretSubmitted", "slot"),
        )
        return PublicDuelParticipant(
            participantId = value.requiredString("participantId"),
            slot = enumValue(value.requiredString("slot"), "slot"),
            participantType = enumValue(value.requiredString("participantType"), "participantType"),
            secretSubmitted = value.requiredBoolean("secretSubmitted"),
            connected = value.requiredBoolean("connected"),
        )
    }

    private fun decodeTurn(element: JsonElement): PublicDuelTurn {
        val value = element.jsonObject
        requireFields(value, setOf("actorParticipantId", "exactMatches", "solved", "turnNumber"))
        return PublicDuelTurn(
            turnNumber = value.requiredInt("turnNumber"),
            actorParticipantId = value.requiredString("actorParticipantId"),
            exactMatches = value.requiredInt("exactMatches"),
            solved = value.requiredBoolean("solved"),
        )
    }

    private fun parseBoundary(json: String): JsonElement {
        requireFrameSize(json)
        val parsed = try {
            parser.parseToJsonElement(json)
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid public session JSON frame", error)
        }
        requireNoForbiddenFields(parsed)
        return parsed
    }

    private fun encodeBoundary(element: JsonElement): String {
        requireNoForbiddenFields(element)
        return encodeCanonical(element).also(::requireFrameSize)
    }

    private fun encodeCanonical(element: JsonElement): String = canonicalize(element).toString()

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonicalize(it.value) })
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }

    private fun requireFrameSize(json: String) {
        require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_PUBLIC_SESSION_FRAME_BYTES) {
            "Public session JSON frame exceeds 64 KiB"
        }
    }

    private fun requireNoForbiddenFields(element: JsonElement) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                require(!key.isForbiddenPublicField()) { "Public session JSON contains a forbidden field" }
                requireNoForbiddenFields(value)
            }

            is JsonArray -> element.forEach(::requireNoForbiddenFields)
            else -> Unit
        }
    }
}

private fun jsonObject(vararg entries: Pair<String, JsonElement?>): JsonObject = JsonObject(
    entries.mapNotNull { (key, value) -> value?.let { key to it } }.toMap(),
)

private fun requireFields(
    value: JsonObject,
    required: Set<String>,
    allowed: Set<String> = required,
) {
    require(required.all(value::containsKey)) { "Public session JSON is missing a required field" }
    require(value.keys.all(allowed::contains)) { "Public session JSON contains an unknown field" }
}

private fun JsonObject.requiredString(name: String): String =
    getValue(name).jsonPrimitive.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("Public session field $name must be a string")

private fun JsonObject.optionalString(name: String): String? = when (val value = get(name)) {
    null, JsonNull -> null
    else -> value.jsonPrimitive.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("Public session field $name must be a string")
}

private fun JsonObject.requiredLong(name: String): Long =
    getValue(name).jsonPrimitive.takeUnless { it.isString }?.longOrNull
        ?: throw IllegalArgumentException("Public session field $name must be an integer")

private fun JsonObject.requiredInt(name: String): Int =
    getValue(name).jsonPrimitive.takeUnless { it.isString }?.intOrNull
        ?: throw IllegalArgumentException("Public session field $name must be an integer")

private fun JsonObject.optionalInt(name: String): Int? = get(name)?.let { value ->
    value.jsonPrimitive.takeUnless { it.isString }?.intOrNull
        ?: throw IllegalArgumentException("Public session field $name must be an integer")
}

private fun JsonObject.requiredBoolean(name: String): Boolean =
    getValue(name).jsonPrimitive.takeUnless { it.isString }?.booleanOrNull
        ?: throw IllegalArgumentException("Public session field $name must be a boolean")

private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
    enumValues<T>().firstOrNull { it.name == value }
        ?: throw IllegalArgumentException("Public session field $field has an unknown enum value")

private fun String.isForbiddenPublicField(): Boolean {
    val normalized = lowercase().filter(Char::isLetterOrDigit)
    if (normalized == "secretsubmitted") return false
    return listOf(
        "secret",
        "hash",
        "cipher",
        "seed",
        "keymaterial",
        "privatekey",
        "token",
        "jwt",
        "purchase",
        "integrity",
        "providerpayload",
        "guess",
        "rawrequest",
        "requestbody",
        "rawpayload",
        "authorization",
        "cookie",
        "credential",
        "password",
    ).any(normalized::contains)
}

private fun requireOpaqueId(value: String, field: String) {
    require(OPAQUE_ID.matches(value)) { "$field must be an opaque id" }
    val normalized = value.lowercase().filter(Char::isLetterOrDigit)
    require(value.any(Char::isLetter)) { "$field must not contain a raw digit sequence" }
    require(
        listOf("secret", "token", "guess", "password", "credential", "privatekey", "providerpayload")
            .none(normalized::contains),
    ) { "$field must not contain sensitive value material" }
    require(!(value.startsWith("eyJ") && value.count { it == '.' } >= 2)) {
        "$field must not contain a token-shaped value"
    }
}

private fun requireDigits(value: String, field: String) {
    require(value.length in 4..20 && value.all(Char::isDigit)) {
        "$field must contain 4..20 digits"
    }
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
