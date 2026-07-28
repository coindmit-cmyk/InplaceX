package com.mirkori.inplacex.platform.online

import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

enum class OnlineMatchStatus {
    SEARCHING,
    MATCHED,
}

data class OnlineMatchTicket(
    val ticketId: String,
    val status: OnlineMatchStatus,
    val sessionId: String?,
    val matchedWithBot: Boolean,
)

data class OnlineDuelAttemptState(
    val actor: String,
    val exactMatches: Int,
    val number: Int,
)

data class OnlineDuelSnapshotState(
    val sessionId: String,
    val revision: Long,
    val phase: String,
    val currentTurn: String?,
    val winner: String?,
    val codeLength: Int,
    val attemptLimit: Int,
    val allowDuplicates: Boolean,
    val attempts: List<OnlineDuelAttemptState>,
)

sealed interface OnlineClientResult<out T> {
    data class Success<T>(val value: T) : OnlineClientResult<T>
    data object AuthenticationRequired : OnlineClientResult<Nothing>
    data object MembershipRejected : OnlineClientResult<Nothing>
    data object RevisionConflict : OnlineClientResult<Nothing>
    data object Offline : OnlineClientResult<Nothing>
    data object TemporarilyUnavailable : OnlineClientResult<Nothing>
    data object InvalidResponse : OnlineClientResult<Nothing>
}

class OnlineDuelClient(
    private val transport: TransportBoundary,
    private val gateway: RemotePlatformGateway = ContractRemotePlatformGateway(),
) {
    private val codec = OnlineDuelResponseCodec()

    suspend fun createMatch(mode: RemoteMatchmakingMode): OnlineClientResult<OnlineMatchTicket> {
        val commandId = UUID.randomUUID().toString()
        val request = gateway.prepareCreateMatchmakingTicket(
            payload = RemoteMatchmakingPayload(commandId, mode),
            idempotencyKey = commandId,
        )
        return transport.execute(request).decode(codec::ticket)
    }

    suspend fun readTicket(ticketId: String): OnlineClientResult<OnlineMatchTicket> =
        transport.execute(gateway.prepareReadMatchmakingTicket(ticketId)).decode(codec::ticket)

    suspend fun readSession(sessionId: String): OnlineClientResult<OnlineDuelSnapshotState> =
        transport.execute(gateway.prepareReadSession(sessionId)).decode(codec::snapshot)

    suspend fun submitSecret(
        sessionId: String,
        revision: Long,
        secret: String,
    ): OnlineClientResult<OnlineDuelSnapshotState> {
        val commandId = UUID.randomUUID().toString()
        return transport.execute(
            gateway.prepareSubmitSecret(
                payload = RemoteSubmitSecretPayload(sessionId, commandId, revision, secret),
                idempotencyKey = commandId,
            ),
        ).decode(codec::snapshot)
    }

    suspend fun submitGuess(
        sessionId: String,
        revision: Long,
        guess: String,
    ): OnlineClientResult<OnlineDuelSnapshotState> {
        val commandId = UUID.randomUUID().toString()
        return transport.execute(
            gateway.prepareSubmitGuess(
                payload = RemoteSubmitGuessPayload(sessionId, commandId, revision, guess),
                idempotencyKey = commandId,
            ),
        ).decode(codec::snapshot)
    }

    suspend fun openEvents(sessionId: String): OnlineSessionOpenResult =
        transport.openSession(gateway.prepareSessionTransport(sessionId))

    private fun <T> RemoteCallResult.decode(
        parser: (String) -> T,
    ): OnlineClientResult<T> = when (this) {
        is RemoteCallResult.Success -> runCatching { OnlineClientResult.Success(parser(response.body)) }
            .getOrDefault(OnlineClientResult.InvalidResponse)
        is RemoteCallResult.HttpFailure -> when (response.statusCode) {
            401 -> OnlineClientResult.AuthenticationRequired
            403 -> OnlineClientResult.MembershipRejected
            409 -> OnlineClientResult.RevisionConflict
            in 500..599 -> OnlineClientResult.TemporarilyUnavailable
            else -> OnlineClientResult.InvalidResponse
        }
        RemoteCallResult.Offline -> OnlineClientResult.Offline
        RemoteCallResult.MissingAccessToken -> OnlineClientResult.AuthenticationRequired
        RemoteCallResult.TimedOut,
        is RemoteCallResult.NetworkFailure,
        -> OnlineClientResult.TemporarilyUnavailable
    }
}

private class OnlineDuelResponseCodec {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    fun ticket(source: String): OnlineMatchTicket {
        val value = objectValue(
            source,
            setOf("ticketId", "status", "sessionId", "matchedWithBot", "createdAtEpochMs"),
        )
        val status = when (value.string("status", 16)) {
            "searching" -> OnlineMatchStatus.SEARCHING
            "matched" -> OnlineMatchStatus.MATCHED
            else -> throw IllegalArgumentException("unsupported ticket status")
        }
        val sessionId = value.nullableUuid("sessionId")
        require((status == OnlineMatchStatus.MATCHED) == (sessionId != null))
        return OnlineMatchTicket(
            ticketId = value.uuid("ticketId"),
            status = status,
            sessionId = sessionId,
            matchedWithBot = value.boolean("matchedWithBot"),
        ).also {
            require(status == OnlineMatchStatus.MATCHED || !it.matchedWithBot)
        }
    }

    fun snapshot(source: String): OnlineDuelSnapshotState {
        val value = objectValue(source, SnapshotFields)
        val codeLength = value.positiveInt("codeLength")
        return OnlineDuelSnapshotState(
            sessionId = value.uuid("sessionId"),
            revision = value.nonNegativeLong("revision"),
            phase = value.string("phase", 16).also { require(it in AllowedPhases) },
            currentTurn = value.nullableActor("currentTurn"),
            winner = value.nullableActor("winner"),
            codeLength = codeLength,
            attemptLimit = value.positiveInt("attemptLimit"),
            allowDuplicates = value.boolean("allowDuplicates"),
            attempts = value.array("attempts").map { element ->
                val attempt = element as? JsonObject ?: throw IllegalArgumentException("attempt must be an object")
                require(attempt.keys == AttemptFields)
                OnlineDuelAttemptState(
                    actor = attempt.string("actor", 16).also { require(it in AllowedActors) },
                    exactMatches = attempt.nonNegativeInt("exactMatches").also { require(it <= codeLength) },
                    number = attempt.positiveInt("number"),
                )
            },
        )
    }

    private fun objectValue(source: String, expectedFields: Set<String>): JsonObject {
        require(source.toByteArray(StandardCharsets.UTF_8).size <= MaximumOnlineResponseBytes)
        val value = json.parseToJsonElement(source) as? JsonObject
            ?: throw IllegalArgumentException("response must be an object")
        require(value.keys == expectedFields)
        return value
    }

    private fun JsonObject.string(name: String, maximum: Int): String {
        val value = this[name] as? JsonPrimitive ?: throw IllegalArgumentException("$name is required")
        require(value.isString)
        return value.content.takeIf { it.length in 1..maximum && it.none(Char::isISOControl) }
            ?: throw IllegalArgumentException("$name has an invalid format")
    }

    private fun JsonObject.uuid(name: String): String =
        string(name, 36).takeIf(String::isCanonicalUuid)
            ?: throw IllegalArgumentException("$name is invalid")

    private fun JsonObject.nullableUuid(name: String): String? {
        val value = this[name] ?: throw IllegalArgumentException("$name is required")
        if (value is JsonNull) return null
        return uuid(name)
    }

    private fun JsonObject.boolean(name: String): Boolean =
        (this[name] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.booleanOrNull
            ?: throw IllegalArgumentException("$name is invalid")

    private fun JsonObject.nonNegativeLong(name: String): Long =
        (this[name] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.longOrNull
            ?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("$name is invalid")

    private fun JsonObject.positiveInt(name: String): Int =
        (this[name] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.intOrNull
            ?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("$name is invalid")

    private fun JsonObject.nonNegativeInt(name: String): Int =
        (this[name] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.intOrNull
            ?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("$name is invalid")

    private fun JsonObject.nullableActor(name: String): String? {
        val value = this[name] ?: throw IllegalArgumentException("$name is required")
        if (value is JsonNull) return null
        return string(name, 16).also { require(it in AllowedActors) }
    }

    private fun JsonObject.array(name: String): JsonArray =
        this[name] as? JsonArray ?: throw IllegalArgumentException("$name is invalid")

    private companion object {
        val SnapshotFields = setOf(
            "sessionId",
            "revision",
            "phase",
            "currentTurn",
            "winner",
            "codeLength",
            "attemptLimit",
            "allowDuplicates",
            "attempts",
            "participants",
        )
        val AttemptFields = setOf("actor", "exactMatches", "number")
        val AllowedActors = setOf("player", "opponent")
        val AllowedPhases = setOf("setup", "active", "finished")
        const val MaximumOnlineResponseBytes = 64 * 1024
    }
}

private fun String.isCanonicalUuid(): Boolean =
    runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)
