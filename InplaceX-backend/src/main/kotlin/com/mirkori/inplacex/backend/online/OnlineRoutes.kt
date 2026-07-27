package com.mirkori.inplacex.backend.online

import com.mirkori.inplacex.backend.auth.AccessTokenAuthentication
import com.mirkori.inplacex.backend.auth.AuthenticatedPrincipal
import com.mirkori.inplacex.backend.auth.JwtAccessTokenVerifier
import com.mirkori.inplacex.backend.session.codec.BoundedJsonScanner
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

fun Application.configureOnlineRoutes(
    verifier: JwtAccessTokenVerifier,
    service: AuthoritativeOnlineDuelService,
) {
    val codec = OnlineJsonCodec()
    install(WebSockets)
    routing {
        post("/api/v1/matchmaking/tickets") {
            val principal = call.authenticatedPrincipalOrRespond(verifier) ?: return@post
            val command = runCatching { codec.decodeTicket(call.receiveText()) }.getOrElse {
                call.respondOnlineError(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            if (!call.hasMatchingIdempotencyKey(command.commandId)) return@post
            val result = runOnlineCommand(call) {
                service.createTicket(principal.playerId, command.commandId, command.mode)
            } ?: return@post
            call.respondJson(HttpStatusCode.OK, codec.encodeTicket(result))
        }

        get("/api/v1/matchmaking/tickets/{ticketId}") {
            val principal = call.authenticatedPrincipalOrRespond(verifier) ?: return@get
            val ticketId = call.safeUuidParameter("ticketId") ?: return@get
            val result = runOnlineCommand(call) {
                service.readTicket(principal.playerId, ticketId)
            } ?: return@get
            call.respondJson(HttpStatusCode.OK, codec.encodeTicket(result))
        }

        get("/api/v1/sessions/{sessionId}") {
            val principal = call.authenticatedPrincipalOrRespond(verifier) ?: return@get
            val sessionId = call.safeUuidParameter("sessionId") ?: return@get
            val result = runOnlineCommand(call) {
                service.readSession(principal.playerId, sessionId)
            } ?: return@get
            call.respondJson(HttpStatusCode.OK, codec.encodeSnapshot(result))
        }

        post("/api/v1/sessions/{sessionId}/reconnect") {
            val principal = call.authenticatedPrincipalOrRespond(verifier) ?: return@post
            val sessionId = call.safeUuidParameter("sessionId") ?: return@post
            val command = runCatching { codec.decodeReconnect(call.receiveText()) }.getOrElse {
                call.respondOnlineError(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            if (!call.hasMatchingIdempotencyKey(command.commandId)) return@post
            val result = runOnlineCommand(call) {
                service.readSession(principal.playerId, sessionId)
            } ?: return@post
            call.respondJson(HttpStatusCode.OK, codec.encodeSnapshot(result))
        }

        post("/api/v1/sessions/{sessionId}/setup/secret") {
            val principal = call.authenticatedPrincipalOrRespond(verifier) ?: return@post
            val sessionId = call.safeUuidParameter("sessionId") ?: return@post
            val command = runCatching { codec.decodeSecret(call.receiveText()) }.getOrElse {
                call.respondOnlineError(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            if (!call.hasMatchingIdempotencyKey(command.commandId)) return@post
            val result = runOnlineCommand(call) {
                service.submitSecret(
                    playerId = principal.playerId,
                    sessionId = sessionId,
                    commandId = command.commandId,
                    expectedRevision = command.expectedRevision,
                    secret = command.digits,
                )
            } ?: return@post
            call.respondJson(HttpStatusCode.OK, codec.encodeSnapshot(result))
        }

        post("/api/v1/sessions/{sessionId}/turns") {
            val principal = call.authenticatedPrincipalOrRespond(verifier) ?: return@post
            val sessionId = call.safeUuidParameter("sessionId") ?: return@post
            val command = runCatching { codec.decodeGuess(call.receiveText()) }.getOrElse {
                call.respondOnlineError(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            if (!call.hasMatchingIdempotencyKey(command.commandId)) return@post
            val result = runOnlineCommand(call) {
                service.submitGuess(
                    playerId = principal.playerId,
                    sessionId = sessionId,
                    commandId = command.commandId,
                    expectedRevision = command.expectedRevision,
                    guess = command.digits,
                )
            } ?: return@post
            call.respondJson(HttpStatusCode.OK, codec.encodeSnapshot(result))
        }

        webSocket(
            path = "/api/v1/ws/sessions/{sessionId}",
            protocol = "inplacex.online.v1",
        ) {
            val authentication = verifier.authenticate(call.request.headers[HttpHeaders.Authorization])
            val principal = (authentication as? AccessTokenAuthentication.Accepted)?.principal
            val sessionId = call.parameters["sessionId"]?.takeIf(String::isCanonicalUuid)
            if (principal == null || sessionId == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                return@webSocket
            }
            val initial = runCatching { service.readSession(principal.playerId, sessionId) }.getOrElse {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "membership_rejected"))
                return@webSocket
            }
            send(Frame.Text(codec.encodeSnapshotFrame(initial)))
            for (frame in incoming) {
                if (frame !is Frame.Text || frame.readText() != """{"type":"snapshot.request"}""") {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "unsupported_frame"))
                    return@webSocket
                }
                val snapshot = runCatching { service.readSession(principal.playerId, sessionId) }.getOrElse {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "membership_rejected"))
                    return@webSocket
                }
                send(Frame.Text(codec.encodeSnapshotFrame(snapshot)))
            }
        }
    }
}

private data class TicketCommand(
    val commandId: String,
    val mode: OnlineMatchMode,
)

private data class SessionCommand(
    val commandId: String,
    val expectedRevision: Long,
    val digits: String,
)

private data class ReconnectCommand(
    val commandId: String,
)

private class OnlineJsonCodec {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    fun decodeTicket(source: String): TicketCommand {
        val value = decodeObject(source, setOf("commandId", "mode"))
        return TicketCommand(
            commandId = value.uuid("commandId"),
            mode = when (value.string("mode", 16)) {
                "classic" -> OnlineMatchMode.CLASSIC
                "pro" -> OnlineMatchMode.PRO
                "pro_plus" -> OnlineMatchMode.PRO_PLUS
                else -> throw IllegalArgumentException("unsupported mode")
            },
        )
    }

    fun decodeSecret(source: String): SessionCommand =
        decodeSessionCommand(source, "secret")

    fun decodeGuess(source: String): SessionCommand =
        decodeSessionCommand(source, "guess")

    fun decodeReconnect(source: String): ReconnectCommand {
        val value = decodeObject(
            source,
            requiredFields = setOf("commandId"),
            allowedFields = setOf("commandId", "lastSeenEventSeq"),
        )
        value["lastSeenEventSeq"]?.let { eventSeq ->
            if (eventSeq !is JsonNull) {
                require((eventSeq as? JsonPrimitive)?.longOrNull?.let { it >= 0 } == true)
            }
        }
        return ReconnectCommand(value.uuid("commandId"))
    }

    fun encodeTicket(ticket: MatchmakingTicket): String = buildJsonObject {
        put("ticketId", ticket.ticketId)
        put("status", ticket.status.name.lowercase())
        ticket.sessionId?.let { put("sessionId", it) }
        put("matchedWithBot", ticket.matchedWithBot)
        put("createdAtEpochMs", ticket.createdAt.toEpochMilli())
    }.toString()

    fun encodeSnapshot(snapshot: OnlineDuelSnapshot): String = snapshotJson(snapshot).toString()

    fun encodeSnapshotFrame(snapshot: OnlineDuelSnapshot): String = buildJsonObject {
        put("type", "session.snapshot")
        put("payload", snapshotJson(snapshot))
    }.toString()

    private fun decodeSessionCommand(source: String, digitField: String): SessionCommand {
        val value = decodeObject(
            source,
            setOf("commandId", "expectedRevision", digitField),
        )
        val digits = value.string(digitField, 20)
        require(digits.matches(Regex("\\d{4,20}")))
        return SessionCommand(
            commandId = value.uuid("commandId"),
            expectedRevision = value.nonNegativeLong("expectedRevision"),
            digits = digits,
        )
    }

    private fun snapshotJson(snapshot: OnlineDuelSnapshot): JsonObject = buildJsonObject {
        put("sessionId", snapshot.sessionId)
        put("revision", snapshot.revision)
        put("phase", snapshot.phase)
        snapshot.currentTurn?.let { put("currentTurn", it) } ?: put("currentTurn", JsonNull)
        snapshot.winner?.let { put("winner", it) } ?: put("winner", JsonNull)
        put("codeLength", snapshot.codeLength)
        put("attemptLimit", snapshot.attemptLimit)
        put("allowDuplicates", snapshot.allowDuplicates)
        put("attempts", buildJsonArray {
            snapshot.attempts.forEach { attempt ->
                add(buildJsonObject {
                    put("actor", attempt.actor)
                    put("exactMatches", attempt.exactMatches)
                    put("number", attempt.number)
                })
            }
        })
        put("participants", buildJsonArray {
            snapshot.participants.forEach { participant ->
                add(buildJsonObject {
                    put("actor", participant.actor)
                    put("secretConfigured", participant.secretConfigured)
                    put("attemptsUsed", participant.attemptsUsed)
                    put("attemptsLeft", participant.attemptsLeft)
                })
            }
        })
    }

    private fun decodeObject(
        source: String,
        requiredFields: Set<String>,
        allowedFields: Set<String> = requiredFields,
    ): JsonObject {
        require(source.toByteArray(StandardCharsets.UTF_8).size <= MaximumOnlineBodyBytes)
        BoundedJsonScanner(json).requireSafeStructure(source)
        val value = json.parseToJsonElement(source) as? JsonObject
            ?: throw IllegalArgumentException("request must be an object")
        require(value.keys.containsAll(requiredFields) && value.keys.all(allowedFields::contains))
        return value
    }

    private fun JsonObject.string(name: String, maximum: Int): String {
        val primitive = this[name] as? JsonPrimitive ?: throw IllegalArgumentException("$name must be a string")
        require(primitive.isString)
        return primitive.content.takeIf { it.length in 1..maximum && it.none(Char::isISOControl) }
            ?: throw IllegalArgumentException("$name has invalid length")
    }

    private fun JsonObject.uuid(name: String): String =
        string(name, 36).takeIf(String::isCanonicalUuid)
            ?: throw IllegalArgumentException("$name must be a canonical UUID")

    private fun JsonObject.nonNegativeLong(name: String): Long =
        (this[name] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.longOrNull
            ?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("$name must be non-negative")

    private companion object {
        const val MaximumOnlineBodyBytes = 16 * 1024
    }
}

private suspend fun ApplicationCall.authenticatedPrincipalOrRespond(
    verifier: JwtAccessTokenVerifier,
): AuthenticatedPrincipal? {
    return when (val result = verifier.authenticate(request.headers[HttpHeaders.Authorization])) {
        is AccessTokenAuthentication.Accepted -> result.principal
        is AccessTokenAuthentication.Rejected -> {
            respondOnlineError(HttpStatusCode.Unauthorized, "unauthorized")
            null
        }
    }
}

private suspend fun ApplicationCall.safeUuidParameter(name: String): String? {
    val value = parameters[name]
    if (value == null || !value.isCanonicalUuid()) {
        respondOnlineError(HttpStatusCode.BadRequest, "invalid_path")
        return null
    }
    return value
}

private suspend fun ApplicationCall.hasMatchingIdempotencyKey(commandId: String): Boolean {
    if (request.headers["Idempotency-Key"] != commandId) {
        respondOnlineError(HttpStatusCode.BadRequest, "invalid_idempotency_key")
        return false
    }
    return true
}

private suspend fun <T> runOnlineCommand(
    call: ApplicationCall,
    operation: () -> T,
): T? {
    return try {
        operation()
    } catch (_: OnlineMembershipRejectedException) {
        call.respondOnlineError(HttpStatusCode.Forbidden, "membership_rejected")
        null
    } catch (_: NoSuchElementException) {
        call.respondOnlineError(HttpStatusCode.NotFound, "not_found")
        null
    } catch (conflict: OnlineRevisionConflictException) {
        call.respondJson(
            HttpStatusCode.Conflict,
            buildJsonObject {
                put("error", "revision_conflict")
                put("currentRevision", conflict.current.revision)
            }.toString(),
        )
        null
    } catch (_: OnlineCommandIdReusedException) {
        call.respondOnlineError(HttpStatusCode.Conflict, "command_id_reused")
        null
    } catch (_: IllegalArgumentException) {
        call.respondOnlineError(HttpStatusCode.BadRequest, "invalid_request")
        null
    }
}

private suspend fun ApplicationCall.respondOnlineError(status: HttpStatusCode, code: String) {
    respondJson(status, buildJsonObject { put("error", code) }.toString())
}

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: String) {
    respondText(body, ContentType.Application.Json, status)
}

private fun String.isCanonicalUuid(): Boolean =
    runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)
