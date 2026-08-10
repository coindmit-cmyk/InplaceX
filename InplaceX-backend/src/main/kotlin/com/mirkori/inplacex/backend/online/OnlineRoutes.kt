package com.mirkori.inplacex.backend.online

import com.mirkori.inplacex.backend.app.RuntimeDrainController
import com.mirkori.inplacex.backend.auth.AccessTokenAuthentication
import com.mirkori.inplacex.backend.auth.AuthenticatedPrincipal
import com.mirkori.inplacex.backend.auth.JwtAccessTokenVerifier
import com.mirkori.inplacex.backend.domain.duel.DuelCommandRejectedException
import com.mirkori.inplacex.backend.domain.duel.DuelCommandRejection
import com.mirkori.inplacex.backend.online.persistence.InMemoryOnlineSessionEventSequence
import com.mirkori.inplacex.backend.online.persistence.LegacyMembershipMigrationConflictException
import com.mirkori.inplacex.backend.online.persistence.OnlineSessionEvent
import com.mirkori.inplacex.backend.online.persistence.OnlineSessionEventSequence
import com.mirkori.inplacex.backend.session.codec.BoundedJsonScanner
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.request.receiveChannel
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
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
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
    eventSequences: OnlineSessionEventSequence = InMemoryOnlineSessionEventSequence(),
    clock: Clock = Clock.systemUTC(),
    nanoTime: () -> Long = System::nanoTime,
    playerProvisioner: OnlinePlayerProvisioner = NoOpOnlinePlayerProvisioner,
    abuseProtector: OnlineAbuseProtector = OnlineAbuseProtector(),
    drainController: RuntimeDrainController = RuntimeDrainController.disabled(),
) {
    val codec = OnlineJsonCodec()
    val drainState = flow {
        while (true) {
            emit(drainController.snapshot().draining)
            delay(WebSocketDrainPollIntervalMillis)
        }
    }.stateIn(
        scope = this,
        started = SharingStarted.Eagerly,
        initialValue = drainController.snapshot().draining,
    )
    install(WebSockets) {
        maxFrameSize = MaximumOnlineWebSocketFrameBytes.toLong()
    }
    routing {
        post("/api/v1/matchmaking/tickets") {
            val principal = call.authenticatedPrincipalOrRespond(
                verifier,
                playerProvisioner,
                abuseProtector,
                OnlineOperation.CreateMatchmakingTicket,
            ) ?: return@post
            val command = runCatching { codec.decodeTicket(call.receiveText()) }.getOrElse {
                call.respondOnlineError(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            if (!call.hasMatchingIdempotencyKey(command.commandId)) return@post
            val result = runOnlineCommand(call) {
                service.createTicket(
                    playerId = principal.playerId,
                    commandId = command.commandId,
                    mode = command.mode,
                    rules = command.rules,
                )
            } ?: return@post
            call.respondJson(HttpStatusCode.OK, codec.encodeTicket(result))
        }

        get("/api/v1/matchmaking/tickets/{ticketId}") {
            val principal = call.authenticatedPrincipalOrRespond(
                verifier,
                playerProvisioner,
                abuseProtector,
                OnlineOperation.ReadMatchmakingTicket,
            ) ?: return@get
            val ticketId = call.safeUuidParameter("ticketId") ?: return@get
            val result = runOnlineCommand(call) {
                service.readTicket(principal.playerId, ticketId)
            } ?: return@get
            call.respondJson(HttpStatusCode.OK, codec.encodeTicket(result))
        }

        post("/api/v1/friends/invites") {
            val principal = call.authenticatedPrincipalOrRespond(
                verifier,
                playerProvisioner,
                abuseProtector,
                OnlineOperation.CreateFriendInvite,
            ) ?: return@post
            val command = runCatching { codec.decodeFriendInvite(call.receiveText()) }.getOrElse {
                call.respondOnlineError(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            if (!call.hasMatchingIdempotencyKey(command.commandId)) return@post
            val result = runOnlineCommand(call) {
                service.createPrivateInvite(
                    playerId = principal.playerId,
                    commandId = command.commandId,
                    playStyle = command.playStyle,
                    codeLength = command.codeLength,
                    targetPlayerId = command.targetPlayerId,
                )
            } ?: return@post
            call.respondJson(HttpStatusCode.OK, codec.encodePrivateInvite(result))
        }

        get("/api/v1/friends/invites") {
            val principal = call.authenticatedPrincipalOrRespond(
                verifier,
                playerProvisioner,
                abuseProtector,
                OnlineOperation.ListFriendInvites,
            ) ?: return@get
            val result = runOnlineCommand(call) {
                service.listIncomingPrivateInvites(principal.playerId)
            } ?: return@get
            call.respondJson(HttpStatusCode.OK, codec.encodePrivateInvites(result))
        }

        get("/api/v1/friends/invites/{inviteCode}") {
            val principal = call.authenticatedPrincipalOrRespond(
                verifier,
                playerProvisioner,
                abuseProtector,
                OnlineOperation.ReadFriendInvite,
            ) ?: return@get
            val inviteCode = call.safeInviteCodeParameter() ?: return@get
            val result = runOnlineCommand(call) {
                service.readPrivateInvite(principal.playerId, inviteCode)
            } ?: return@get
            call.respondJson(HttpStatusCode.OK, codec.encodePrivateInvite(result))
        }

        post("/api/v1/friends/invites/{inviteCode}/accept") {
            val principal = call.authenticatedPrincipalOrRespond(
                verifier,
                playerProvisioner,
                abuseProtector,
                OnlineOperation.AcceptFriendInvite,
            ) ?: return@post
            val inviteCode = call.safeInviteCodeParameter() ?: return@post
            val command = runCatching { codec.decodeInviteAccept(call.receiveText()) }.getOrElse {
                call.respondOnlineError(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            if (!call.hasMatchingIdempotencyKey(command.commandId)) return@post
            val result = runOnlineCommand(call) {
                service.acceptPrivateInvite(principal.playerId, command.commandId, inviteCode)
            } ?: return@post
            call.respondJson(HttpStatusCode.OK, codec.encodePrivateInvite(result))
        }

        get("/api/v1/sessions/{sessionId}") {
            val principal = call.authenticatedPrincipalOrRespond(
                verifier,
                playerProvisioner,
                abuseProtector,
                OnlineOperation.ReadSession,
            ) ?: return@get
            val sessionId = call.safeUuidParameter("sessionId") ?: return@get
            val result = runOnlineCommand(call) {
                service.readSession(principal.playerId, sessionId)
            } ?: return@get
            call.respondJson(HttpStatusCode.OK, codec.encodeSnapshot(result))
        }

        post("/api/v1/sessions/{sessionId}/legacy-membership") {
            val principal = call.authenticatedPrincipalOrRespond(
                verifier,
                playerProvisioner,
                abuseProtector,
                OnlineOperation.MigrateLegacyMembership,
            ) ?: return@post
            val sessionId = call.safeUuidParameter("sessionId") ?: return@post
            val command = runCatching {
                codec.decodeLegacyMembershipMigration(
                    call.receiveBoundedUtf8(MaximumLegacyMigrationBodyBytes),
                )
            }.getOrElse {
                call.respondOnlineError(HttpStatusCode.BadRequest, "invalid_request")
                return@post
            }
            if (!call.hasMatchingIdempotencyKey(command.commandId)) return@post
            val receipt = runOnlineCommand(call) {
                service.migrateLegacyMembership(
                    platformPlayerId = principal.playerId,
                    sessionId = sessionId,
                    commandId = command.commandId,
                    legacyRefreshToken = command.legacyRefreshToken,
                )
            } ?: return@post
            call.respondJson(HttpStatusCode.OK, codec.encodeLegacyMembershipMigration(receipt))
        }

        post("/api/v1/sessions/{sessionId}/reconnect") {
            val principal = call.authenticatedPrincipalOrRespond(
                verifier,
                playerProvisioner,
                abuseProtector,
                OnlineOperation.ReconnectSession,
            ) ?: return@post
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
            val principal = call.authenticatedPrincipalOrRespond(
                verifier,
                playerProvisioner,
                abuseProtector,
                OnlineOperation.SubmitSecret,
            ) ?: return@post
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
            val principal = call.authenticatedPrincipalOrRespond(
                verifier,
                playerProvisioner,
                abuseProtector,
                OnlineOperation.SubmitTurn,
            ) ?: return@post
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
            val remoteIdentity = call.remoteIdentity()
            val authenticationPreCheck = abuseProtector.checkAuthenticationFailureBudget(remoteIdentity)
            if (authenticationPreCheck is OnlineAbuseDecision.Rejected) {
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "rate_limited"))
                return@webSocket
            }
            val authentication = verifier.authenticate(call.request.headers[HttpHeaders.Authorization])
            val principal = (authentication as? AccessTokenAuthentication.Accepted)?.principal
            val sessionId = call.parameters["sessionId"]?.takeIf(String::isCanonicalUuid)
            if (principal == null) {
                if (
                    abuseProtector.acquireAuthenticationAttempt(remoteIdentity) is
                    OnlineAbuseDecision.Rejected
                ) {
                    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "rate_limited"))
                    return@webSocket
                }
                val decision = abuseProtector.acquireInvalidAuthentication(remoteIdentity)
                val closeCode = if (decision is OnlineAbuseDecision.Rejected) {
                    CloseReason.Codes.TRY_AGAIN_LATER
                } else {
                    CloseReason.Codes.VIOLATED_POLICY
                }
                close(CloseReason(closeCode, "unauthorized"))
                return@webSocket
            }
            if (
                abuseProtector.acquire(principal.playerId, OnlineOperation.OpenWebSocket) is
                OnlineAbuseDecision.Rejected
            ) {
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "rate_limited"))
                return@webSocket
            }
            if (sessionId == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
                return@webSocket
            }
            val webSocketLease = abuseProtector.openWebSocket(principal.playerId)
            if (webSocketLease == null) {
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "rate_limited"))
                return@webSocket
            }
            val sessionJob = coroutineContext[Job]
            if (sessionJob == null) {
                webSocketLease.close()
                close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "server_failure"))
                return@webSocket
            }
            sessionJob.invokeOnCompletion { webSocketLease.close() }
            val initial = try {
                service.readSession(principal.playerId, sessionId)
            } catch (_: OnlineMembershipRejectedException) {
                webSocketLease.close()
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "membership_rejected"))
                return@webSocket
            } catch (_: NoSuchElementException) {
                webSocketLease.close()
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "membership_rejected"))
                return@webSocket
            } catch (_: Throwable) {
                webSocketLease.close()
                close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "server_failure"))
                return@webSocket
            }
            check(initial.sessionId == sessionId)

            val outbound = Channel<String>(capacity = MaximumPendingWebSocketFrames)
            val outboundMutex = Mutex()
            val observedEventSequence = AtomicLong(UnsubscribedEventSequence)
            val heartbeatDeadline = WebSocketHeartbeatDeadline(nanoTime, WebSocketPingTimeoutNanos)
            val writer = launch {
                for (message in outbound) send(Frame.Text(message))
            }
            val webSocketJob = coroutineContext[Job]
            val drainWatcher = launch {
                drainState.first { it }
                launch {
                    delay(WebSocketDrainForceCloseDelayMillis)
                    webSocketJob?.cancel()
                }
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "service_draining"))
            }
            fun queueObservedEvent(event: OnlineSessionEvent): Boolean {
                val response = event.sessionRevision?.let {
                    val snapshot = service.readSession(principal.playerId, sessionId)
                    check(snapshot.revision >= it) {
                        "Durable session event is ahead of the authoritative snapshot"
                    }
                    codec.encodeSnapshotFrame(
                        snapshot = snapshot,
                        eventSequence = event.eventSequence,
                        sentAt = clock.instant(),
                        requestId = null,
                    )
                }
                val accepted = response == null || outbound.trySend(response).isSuccess
                if (accepted) observedEventSequence.set(event.eventSequence)
                return accepted
            }
            fun queueEventsBefore(targetEventSequence: Long): Boolean {
                if (observedEventSequence.get() == UnsubscribedEventSequence) return true
                while (true) {
                    val events = eventSequences.readAfter(
                        sessionId,
                        observedEventSequence.get(),
                        MaximumEventPollBatch,
                    )
                    check(events.isNotEmpty()) { "Allocated WebSocket cursor is not readable" }
                    for (event in events) {
                        if (event.eventSequence == targetEventSequence) return true
                        check(event.eventSequence < targetEventSequence) {
                            "WebSocket event cursor ordering is inconsistent"
                        }
                        if (!queueObservedEvent(event)) return false
                    }
                }
            }
            val heartbeat = launch {
                while (true) {
                    delay(WebSocketHeartbeatIntervalMillis)
                    if (observedEventSequence.get() == UnsubscribedEventSequence) continue
                    if (heartbeatDeadline.hasExpired()) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "heartbeat_timeout"))
                        return@launch
                    }
                    val queued = runCatching {
                        outboundMutex.withLock {
                            val sentAt = clock.instant()
                            val eventSequence = eventSequences.next(
                                sessionId = sessionId,
                                eventType = "connection.heartbeat",
                                createdAt = sentAt,
                            )
                            val caughtUp = queueEventsBefore(eventSequence)
                            val queued = caughtUp && outbound.trySend(
                                codec.encodeHeartbeatFrame(
                                    sessionId,
                                    eventSequence,
                                    sentAt,
                                    requestId = null,
                                ),
                            ).isSuccess
                            if (queued) observedEventSequence.set(eventSequence)
                            queued
                        }
                    }.getOrElse {
                        close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "server_failure"))
                        return@launch
                    }
                    if (!queued) {
                        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "slow_consumer"))
                        return@launch
                    }
                }
            }
            val sessionChanges = launch {
                while (true) {
                    delay(WebSocketEventPollIntervalMillis)
                    val queued = runCatching {
                        outboundMutex.withLock {
                            val after = observedEventSequence.get()
                            if (after == UnsubscribedEventSequence) return@withLock true
                            eventSequences.readAfter(sessionId, after, MaximumEventPollBatch)
                                .all(::queueObservedEvent)
                        }
                    }.getOrElse {
                        close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "server_failure"))
                        return@launch
                    }
                    if (!queued) {
                        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "slow_consumer"))
                        return@launch
                    }
                }
            }
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unsupported_frame"))
                        return@webSocket
                    }
                    val source = frame.readText()
                    if (source.toByteArray(StandardCharsets.UTF_8).size > MaximumOnlineWebSocketFrameBytes) {
                        close(CloseReason(CloseReason.Codes.TOO_BIG, "frame_too_large"))
                        return@webSocket
                    }
                    val command = runCatching { codec.decodeWebSocketControl(source) }.getOrElse {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid_frame"))
                        return@webSocket
                    }
                    if (
                        abuseProtector.acquire(principal.playerId, OnlineOperation.WebSocketControl) is
                        OnlineAbuseDecision.Rejected
                    ) {
                        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "rate_limited"))
                        return@webSocket
                    }
                    if (command.sessionId != sessionId) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "session_mismatch"))
                        return@webSocket
                    }
                    if (command.type == "session.ping") heartbeatDeadline.recordPing()
                    val queued = runCatching {
                        outboundMutex.withLock {
                            val sentAt = clock.instant()
                            val response = when (command.type) {
                                "session.subscribe", "session.resync" -> {
                                    val replayable = command.lastSeenEventSequence?.let {
                                        eventSequences.isReplayable(sessionId, it)
                                    } ?: true
                                    val eventType = if (replayable) {
                                        "session.snapshot"
                                    } else {
                                        "session.replayGap"
                                    }
                                    val eventSequence = eventSequences.next(sessionId, eventType, sentAt)
                                    val snapshot = service.readSession(principal.playerId, sessionId)
                                    val response = if (replayable) {
                                        codec.encodeSnapshotFrame(
                                            snapshot,
                                            eventSequence,
                                            sentAt,
                                            command.requestId,
                                        )
                                    } else {
                                        codec.encodeReplayGapFrame(
                                            snapshot,
                                            requireNotNull(command.lastSeenEventSequence),
                                            eventSequence,
                                            sentAt,
                                            command.requestId,
                                        )
                                    }
                                    observedEventSequence.set(eventSequence)
                                    response
                                }
                                "session.ping" -> {
                                    val eventSequence = eventSequences.next(
                                        sessionId,
                                        "connection.heartbeat",
                                        sentAt,
                                    )
                                    if (!queueEventsBefore(eventSequence)) throw SlowWebSocketConsumerException()
                                    val response = codec.encodeHeartbeatFrame(
                                        sessionId,
                                        eventSequence,
                                        sentAt,
                                        command.requestId,
                                    )
                                    observedEventSequence.set(eventSequence)
                                    response
                                }
                                else -> error("unsupported control command")
                            }
                            outbound.trySend(response).isSuccess
                        }
                    }.getOrElse { failure ->
                        if (failure is SlowWebSocketConsumerException) return@getOrElse false
                        close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "server_failure"))
                        return@webSocket
                    }
                    if (!queued) {
                        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "slow_consumer"))
                        return@webSocket
                    }
                }
            } finally {
                drainWatcher.cancel()
                heartbeat.cancel()
                sessionChanges.cancel()
                outbound.close()
                writer.cancel()
                webSocketLease.close()
            }
        }
    }
}

private data class TicketCommand(
    val commandId: String,
    val mode: OnlineMatchMode,
    val rules: OnlineMatchRules,
)

private data class SessionCommand(
    val commandId: String,
    val expectedRevision: Long,
    val digits: String,
)

private data class FriendInviteCommand(
    val commandId: String,
    val playStyle: OnlineFriendPlayStyle,
    val codeLength: Int,
    val targetPlayerId: String?,
)

private data class ReconnectCommand(
    val commandId: String,
)

private data class InviteAcceptCommand(
    val commandId: String,
)

private data class LegacyMembershipMigrationCommand(
    val commandId: String,
    val legacyRefreshToken: String,
) {
    override fun toString(): String =
        "LegacyMembershipMigrationCommand(commandId=$commandId, legacyRefreshToken=[redacted])"
}

private data class WebSocketControlCommand(
    val requestId: String,
    val sessionId: String,
    val type: String,
    val lastSeenEventSequence: Long?,
)

private data class WebSocketParticipant(
    val actor: String,
    val participantId: String,
    val slot: String,
    val secretSubmitted: Boolean,
)

private class OnlineJsonCodec {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    fun decodeTicket(source: String): TicketCommand {
        val value = decodeObject(
            source,
            requiredFields = setOf("commandId", "mode"),
            allowedFields = setOf("commandId", "mode", "playStyle", "codeLength"),
        )
        val hasPlayStyle = "playStyle" in value
        val hasCodeLength = "codeLength" in value
        require(hasPlayStyle == hasCodeLength) {
            "playStyle and codeLength must be provided together"
        }
        return TicketCommand(
            commandId = value.uuid("commandId"),
            mode = when (value.string("mode", 16)) {
                "classic" -> OnlineMatchMode.CLASSIC
                "pro" -> OnlineMatchMode.PRO
                "pro_plus" -> OnlineMatchMode.PRO_PLUS
                else -> throw IllegalArgumentException("unsupported mode")
            },
            rules = if (hasPlayStyle) {
                OnlineMatchRules(
                    playStyle = when (value.string("playStyle", 16)) {
                        "race" -> OnlineFriendPlayStyle.RACE
                        "turn_based" -> OnlineFriendPlayStyle.TURN_BASED
                        else -> throw IllegalArgumentException("unsupported play style")
                    },
                    codeLength = value.intInRange("codeLength", 4..10),
                )
            } else {
                OnlineMatchRules(
                    playStyle = OnlineFriendPlayStyle.TURN_BASED,
                    codeLength = 4,
                )
            },
        )
    }

    fun decodeFriendInvite(source: String): FriendInviteCommand {
        val value = decodeObject(
            source,
            requiredFields = setOf("commandId", "playStyle", "codeLength"),
            allowedFields = setOf("commandId", "playStyle", "codeLength", "targetPlayerId"),
        )
        return FriendInviteCommand(
            commandId = value.uuid("commandId"),
            playStyle = when (value.string("playStyle", 16)) {
                "race" -> OnlineFriendPlayStyle.RACE
                "turn_based" -> OnlineFriendPlayStyle.TURN_BASED
                else -> throw IllegalArgumentException("unsupported play style")
            },
            codeLength = value.intInRange("codeLength", 4..10),
            targetPlayerId = value["targetPlayerId"]?.let { value.uuid("targetPlayerId") },
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

    fun decodeInviteAccept(source: String): InviteAcceptCommand {
        val value = decodeObject(source, setOf("commandId"))
        return InviteAcceptCommand(value.uuid("commandId"))
    }

    fun decodeLegacyMembershipMigration(source: String): LegacyMembershipMigrationCommand {
        val value = decodeObject(
            source = source,
            requiredFields = setOf("commandId", "legacyRefreshToken"),
            maximumBytes = MaximumLegacyMigrationBodyBytes,
        )
        return LegacyMembershipMigrationCommand(
            commandId = value.uuid("commandId"),
            legacyRefreshToken = value.string("legacyRefreshToken", 512).also { token ->
                require(token.none(Char::isWhitespace))
            },
        )
    }

    fun encodeLegacyMembershipMigration(receipt: LegacyOnlineMembershipMigrationReceipt): String =
        buildJsonObject {
            put("sessionId", receipt.sessionId)
            put("status", "migrated")
        }.toString()

    fun encodeTicket(ticket: MatchmakingTicket): String = buildJsonObject {
        put("ticketId", ticket.ticketId)
        put("status", ticket.status.name.lowercase())
        if (ticket.sessionId == null) {
            put("sessionId", JsonNull)
        } else {
            put("sessionId", ticket.sessionId)
        }
        put("matchedWithBot", ticket.matchedWithBot)
        put("createdAtEpochMs", ticket.createdAt.toEpochMilli())
    }.toString()

    fun encodeSnapshot(snapshot: OnlineDuelSnapshot): String = snapshotJson(snapshot).toString()

    fun encodePrivateInvite(invite: PrivateDuelInvite): String = buildJsonObject {
        put("inviteCode", invite.inviteCode)
        put("status", invite.status.name.lowercase())
        if (invite.sessionId == null) {
            put("sessionId", JsonNull)
        } else {
            put("sessionId", invite.sessionId)
        }
        put("createdAtEpochMs", invite.createdAt.toEpochMilli())
        put("expiresAtEpochMs", invite.expiresAt.toEpochMilli())
        put("playStyle", invite.playStyle.name.lowercase())
        put("codeLength", invite.codeLength)
        put("allowDuplicates", invite.allowDuplicates)
        put("maxConsecutiveDuplicateDigits", invite.maxConsecutiveDuplicateDigits)
        put("matchDurationSeconds", invite.matchDurationSeconds)
    }.toString()

    fun encodePrivateInvites(invites: List<PrivateDuelInvite>): String = buildJsonArray {
        invites.forEach { invite -> add(json.parseToJsonElement(encodePrivateInvite(invite))) }
    }.toString()

    fun decodeWebSocketControl(source: String): WebSocketControlCommand {
        val value = decodeObject(
            source,
            setOf("schemaVersion", "messageId", "requestId", "sessionId", "type", "payload"),
            maximumBytes = MaximumOnlineWebSocketFrameBytes,
        )
        require(value.string("schemaVersion", 8) == "1.0")
        value.uuid("messageId")
        val requestId = value.uuid("requestId")
        val sessionId = value.uuid("sessionId")
        val type = value.string("type", 32)
        require(type in WebSocketControlTypes)
        val payload = value["payload"] as? JsonObject
            ?: throw IllegalArgumentException("payload must be an object")
        val lastSeenEventSequence = when (type) {
            "session.ping" -> {
                require(payload.isEmpty())
                null
            }
            else -> {
                require(payload.keys.all { it == "lastSeenEventSeq" })
                payload["lastSeenEventSeq"]?.let { element ->
                    require(element !is JsonNull)
                    (element as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
                        ?: throw IllegalArgumentException("lastSeenEventSeq must not be negative")
                }
            }
        }
        return WebSocketControlCommand(requestId, sessionId, type, lastSeenEventSequence)
    }

    fun encodeSnapshotFrame(
        snapshot: OnlineDuelSnapshot,
        eventSequence: Long,
        sentAt: Instant,
        requestId: String?,
    ): String = serverEnvelope(
        sessionId = snapshot.sessionId,
        eventSequence = eventSequence,
        sentAt = sentAt,
        requestId = requestId,
        type = "session.snapshot",
        payload = buildJsonObject {
            put("snapshot", webSocketSnapshotJson(snapshot, eventSequence))
        },
    )

    fun encodeReplayGapFrame(
        snapshot: OnlineDuelSnapshot,
        requestedAfterEventSequence: Long,
        eventSequence: Long,
        sentAt: Instant,
        requestId: String,
    ): String = serverEnvelope(
        sessionId = snapshot.sessionId,
        eventSequence = eventSequence,
        sentAt = sentAt,
        requestId = requestId,
        type = "session.replayGap",
        payload = buildJsonObject {
            put("snapshot", webSocketSnapshotJson(snapshot, eventSequence))
            put("requestedAfterEventSeq", requestedAfterEventSequence)
        },
    )

    fun encodeHeartbeatFrame(
        sessionId: String,
        eventSequence: Long,
        sentAt: Instant,
        requestId: String?,
    ): String = serverEnvelope(
        sessionId = sessionId,
        eventSequence = eventSequence,
        sentAt = sentAt,
        requestId = requestId,
        type = "connection.heartbeat",
        payload = buildJsonObject { put("serverTime", sentAt.toString()) },
    )

    private fun serverEnvelope(
        sessionId: String,
        eventSequence: Long,
        sentAt: Instant,
        requestId: String?,
        type: String,
        payload: JsonObject,
    ): String = buildJsonObject {
        put("schemaVersion", "1.0")
        put("messageId", UUID.randomUUID().toString())
        if (requestId == null) put("requestId", JsonNull) else put("requestId", requestId)
        put("sessionId", sessionId)
        put("eventSeq", eventSequence)
        put("sentAt", sentAt.toString())
        put("type", type)
        put("payload", payload)
    }.toString()

    private fun webSocketSnapshotJson(
        snapshot: OnlineDuelSnapshot,
        eventSequence: Long,
    ): JsonObject {
        val participants = snapshot.participants.mapIndexed { index, participant ->
            WebSocketParticipant(
                actor = participant.actor,
                participantId = UUID.nameUUIDFromBytes(
                    "${snapshot.sessionId}:${if (index == 0) "A" else "B"}"
                        .toByteArray(StandardCharsets.UTF_8),
                ).toString(),
                slot = if (index == 0) "A" else "B",
                secretSubmitted = participant.secretConfigured,
            )
        }
        fun participantId(actor: String?): String? =
            actor?.let { expected -> participants.firstOrNull { it.actor == expected }?.participantId }

        val phase = when (snapshot.phase) {
            "setup" -> when (participants.indexOfFirst { !it.secretSubmitted }) {
                0 -> "SETUP_SECRET_A"
                1 -> "SETUP_SECRET_B"
                else -> "SETUP_WAITING_FOR_PLAYERS"
            }
            "active" -> if (snapshot.playStyle == "race") {
                "ACTIVE_RACE"
            } else if (participants.indexOfFirst { it.actor == snapshot.currentTurn } == 0) {
                "ACTIVE_TURN_A"
            } else {
                "ACTIVE_TURN_B"
            }
            "finished" -> "FINISHED"
            else -> "ABANDONED"
        }
        return buildJsonObject {
            put("sessionId", snapshot.sessionId)
            put("revision", snapshot.revision)
            put("eventSeq", eventSequence)
            put("phase", phase)
            put("config", buildJsonObject {
                put("mode", "online_duel")
                put("playStyle", snapshot.playStyle)
                put("codeLength", snapshot.codeLength)
                if (snapshot.attemptLimit == null) put("attemptLimit", JsonNull)
                else put("attemptLimit", snapshot.attemptLimit)
                put("allowDuplicates", snapshot.allowDuplicates)
                if (snapshot.maxConsecutiveDuplicateDigits == null) {
                    put("maxConsecutiveDuplicateDigits", JsonNull)
                } else {
                    put("maxConsecutiveDuplicateDigits", snapshot.maxConsecutiveDuplicateDigits)
                }
            })
            put("selfParticipantId", requireNotNull(participantId("player")))
            participantId(snapshot.currentTurn)?.let { put("currentTurnParticipantId", it) }
                ?: put("currentTurnParticipantId", JsonNull)
            put("participants", buildJsonArray {
                participants.forEach { participant ->
                    add(buildJsonObject {
                        put("participantId", participant.participantId)
                        put("slot", participant.slot)
                        put("secretSubmitted", participant.secretSubmitted)
                        put("connected", participant.actor == "player")
                    })
                }
            })
            put("turns", buildJsonArray {
                snapshot.attempts.forEach { attempt ->
                    add(buildJsonObject {
                        put("turnNumber", attempt.number)
                        put("actorParticipantId", requireNotNull(participantId(attempt.actor)))
                        if (attempt.ownGuess == null) put("ownGuess", JsonNull)
                        else put("ownGuess", attempt.ownGuess)
                        put("exactMatches", attempt.exactMatches)
                        put("solved", attempt.exactMatches == snapshot.codeLength)
                    })
                }
            })
            participantId(snapshot.winner)?.let { put("winnerParticipantId", it) }
                ?: put("winnerParticipantId", JsonNull)
            put("serverTime", Instant.ofEpochMilli(snapshot.serverTimeEpochMs).toString())
        }
    }

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
        if (snapshot.currentTurn == null) {
            put("currentTurn", JsonNull)
        } else {
            put("currentTurn", snapshot.currentTurn)
        }
        if (snapshot.winner == null) {
            put("winner", JsonNull)
        } else {
            put("winner", snapshot.winner)
        }
        if (snapshot.finishReason == null) {
            put("finishReason", JsonNull)
        } else {
            put("finishReason", snapshot.finishReason)
        }
        put("playStyle", snapshot.playStyle)
        put("codeLength", snapshot.codeLength)
        if (snapshot.attemptLimit == null) {
            put("attemptLimit", JsonNull)
        } else {
            put("attemptLimit", snapshot.attemptLimit)
        }
        put("allowDuplicates", snapshot.allowDuplicates)
        if (snapshot.maxConsecutiveDuplicateDigits == null) {
            put("maxConsecutiveDuplicateDigits", JsonNull)
        } else {
            put("maxConsecutiveDuplicateDigits", snapshot.maxConsecutiveDuplicateDigits)
        }
        if (snapshot.startedAtEpochMs == null) {
            put("startedAtEpochMs", JsonNull)
        } else {
            put("startedAtEpochMs", snapshot.startedAtEpochMs)
        }
        if (snapshot.deadlineAtEpochMs == null) {
            put("deadlineAtEpochMs", JsonNull)
        } else {
            put("deadlineAtEpochMs", snapshot.deadlineAtEpochMs)
        }
        put("serverTimeEpochMs", snapshot.serverTimeEpochMs)
        put("attempts", buildJsonArray {
            snapshot.attempts.forEach { attempt ->
                add(buildJsonObject {
                    put("actor", attempt.actor)
                    put("exactMatches", attempt.exactMatches)
                    put("number", attempt.number)
                    if (attempt.ownGuess == null) {
                        put("ownGuess", JsonNull)
                    } else {
                        put("ownGuess", attempt.ownGuess)
                    }
                })
            }
        })
        put("participants", buildJsonArray {
            snapshot.participants.forEach { participant ->
                add(buildJsonObject {
                    put("actor", participant.actor)
                    put("secretConfigured", participant.secretConfigured)
                    put("attemptsUsed", participant.attemptsUsed)
                    if (participant.attemptsLeft == null) {
                        put("attemptsLeft", JsonNull)
                    } else {
                        put("attemptsLeft", participant.attemptsLeft)
                    }
                })
            }
        })
    }

    private fun decodeObject(
        source: String,
        requiredFields: Set<String>,
        allowedFields: Set<String> = requiredFields,
        maximumBytes: Int = MaximumOnlineBodyBytes,
    ): JsonObject {
        require(source.toByteArray(StandardCharsets.UTF_8).size <= maximumBytes)
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

    private fun JsonObject.intInRange(name: String, range: IntRange): Int =
        (this[name] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.content
            ?.toIntOrNull()
            ?.takeIf(range::contains)
            ?: throw IllegalArgumentException("$name is outside the allowed range")

    private companion object {
        const val MaximumOnlineBodyBytes = 16 * 1024
    }
}

private const val MaximumOnlineWebSocketFrameBytes = 64 * 1024
private const val MaximumLegacyMigrationBodyBytes = 2 * 1024
private const val MaximumRemoteIdentityCharacters = 128
private const val MaximumPendingWebSocketFrames = 16
private const val WebSocketHeartbeatIntervalMillis = 20_000L
private const val WebSocketEventPollIntervalMillis = 250L
private const val WebSocketDrainPollIntervalMillis = 100L
private const val WebSocketDrainForceCloseDelayMillis = 1_000L
private const val WebSocketPingTimeoutNanos = 45_000_000_000L
private const val MaximumEventPollBatch = 32
private const val UnsubscribedEventSequence = -1L
private val WebSocketControlTypes = setOf("session.subscribe", "session.resync", "session.ping")
private class SlowWebSocketConsumerException : IllegalStateException()

internal class WebSocketHeartbeatDeadline(
    private val nanoTime: () -> Long,
    private val timeoutNanos: Long,
) {
    private val lastPingAt = AtomicLong(nanoTime())

    init {
        require(timeoutNanos > 0) { "WebSocket ping timeout must be positive" }
    }

    fun recordPing() {
        lastPingAt.set(nanoTime())
    }

    fun hasExpired(): Boolean = nanoTime() - lastPingAt.get() > timeoutNanos
}

private suspend fun ApplicationCall.authenticatedPrincipalOrRespond(
    verifier: JwtAccessTokenVerifier,
    playerProvisioner: OnlinePlayerProvisioner,
    abuseProtector: OnlineAbuseProtector,
    operation: OnlineOperation,
): AuthenticatedPrincipal? {
    val remoteIdentity = remoteIdentity()
    when (val decision = abuseProtector.checkAuthenticationFailureBudget(remoteIdentity)) {
        OnlineAbuseDecision.Allowed -> Unit
        is OnlineAbuseDecision.Rejected -> {
            respondRateLimited(decision.retryAfterSeconds)
            return null
        }
    }
    return when (val result = verifier.authenticate(request.headers[HttpHeaders.Authorization])) {
        is AccessTokenAuthentication.Accepted -> {
            when (val decision = abuseProtector.acquire(result.principal.playerId, operation)) {
                OnlineAbuseDecision.Allowed -> result.principal.also {
                    playerProvisioner.ensurePlayer(it.playerId)
                }
                is OnlineAbuseDecision.Rejected -> {
                    respondRateLimited(decision.retryAfterSeconds)
                    null
                }
            }
        }
        is AccessTokenAuthentication.Rejected -> {
            when (val authenticationDecision = abuseProtector.acquireAuthenticationAttempt(remoteIdentity)) {
                OnlineAbuseDecision.Allowed -> {
                    when (val invalidDecision = abuseProtector.acquireInvalidAuthentication(remoteIdentity)) {
                        OnlineAbuseDecision.Allowed -> {
                            respondOnlineError(HttpStatusCode.Unauthorized, "unauthorized")
                        }
                        is OnlineAbuseDecision.Rejected -> respondRateLimited(invalidDecision.retryAfterSeconds)
                    }
                }
                is OnlineAbuseDecision.Rejected -> respondRateLimited(authenticationDecision.retryAfterSeconds)
            }
            null
        }
    }
}

private fun ApplicationCall.remoteIdentity(): String =
    request.headers["X-Real-IP"]
        ?.takeIf { it.isNotBlank() && it.length <= MaximumRemoteIdentityCharacters && it.none(Char::isISOControl) }
        ?: request.local.remoteHost.take(MaximumRemoteIdentityCharacters)

private suspend fun ApplicationCall.respondRateLimited(retryAfterSeconds: Long) {
    response.headers.append(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
    respondOnlineError(HttpStatusCode.TooManyRequests, "rate_limited")
}

fun interface OnlinePlayerProvisioner {
    fun ensurePlayer(playerId: String)
}

private object NoOpOnlinePlayerProvisioner : OnlinePlayerProvisioner {
    override fun ensurePlayer(playerId: String) = Unit
}

private suspend fun ApplicationCall.safeUuidParameter(name: String): String? {
    val value = parameters[name]
    if (value == null || !value.isCanonicalUuid()) {
        respondOnlineError(HttpStatusCode.BadRequest, "invalid_path")
        return null
    }
    return value
}

private suspend fun ApplicationCall.safeInviteCodeParameter(): String? {
    val value = parameters["inviteCode"]?.uppercase()
    if (value == null || !value.matches(Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8}"))) {
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
    } catch (_: OnlineInviteUnavailableException) {
        call.respondOnlineError(HttpStatusCode.Conflict, "invite_unavailable")
        null
    } catch (_: LegacyMembershipMigrationConflictException) {
        call.respondOnlineError(HttpStatusCode.Conflict, "idempotency_key_reused")
        null
    } catch (_: LegacyOnlineCredentialRejectedException) {
        call.respondOnlineError(HttpStatusCode.Forbidden, "legacy_credential_rejected")
        null
    } catch (_: LegacyOnlineMigrationUnavailableException) {
        call.respondOnlineError(HttpStatusCode.ServiceUnavailable, "legacy_migration_unavailable")
        null
    } catch (rejected: DuelCommandRejectedException) {
        val (status, code) = when (rejected.rejection) {
            DuelCommandRejection.INVALID_SECRET,
            DuelCommandRejection.INVALID_GUESS,
            -> HttpStatusCode.BadRequest to "invalid_guess"
            DuelCommandRejection.NOT_CURRENT_TURN ->
                HttpStatusCode.Conflict to "not_your_turn"
            DuelCommandRejection.MATCH_FINISHED ->
                HttpStatusCode.Conflict to "session_finished"
            DuelCommandRejection.SECRET_NOT_EXPECTED,
            DuelCommandRejection.MATCH_NOT_ACTIVE,
            -> HttpStatusCode.Conflict to "invalid_state"
        }
        call.respondOnlineError(status, code)
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

private suspend fun ApplicationCall.receiveBoundedUtf8(maximumBytes: Int): String {
    require(maximumBytes > 0)
    request.headers[HttpHeaders.ContentLength]?.let { declared ->
        val parsed = declared.toLongOrNull()
        require(parsed != null && parsed in 0..maximumBytes.toLong())
    }
    val channel = receiveChannel()
    val bytes = ByteArray(maximumBytes + 1)
    var offset = 0
    while (offset < bytes.size) {
        val read = channel.readAvailable(bytes, offset, bytes.size - offset)
        when {
            read < 0 -> break
            read == 0 -> yield()
            else -> offset += read
        }
    }
    require(offset <= maximumBytes)
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, 0, offset))
        .toString()
}

private fun String.isCanonicalUuid(): Boolean =
    runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)
