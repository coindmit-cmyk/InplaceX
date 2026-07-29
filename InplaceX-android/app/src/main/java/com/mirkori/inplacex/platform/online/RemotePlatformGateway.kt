package com.mirkori.inplacex.platform.online

import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class RemoteHttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
}

enum class RemotePlatform {
    ANDROID,
}

enum class RemoteMatchmakingMode {
    CLASSIC,
    PRO,
    PRO_PLUS,
}

enum class RemoteFriendPlayStyle {
    RACE,
    TURN_BASED,
}

data class RemoteRequestSpec(
    val operation: String = DefaultOperation,
    val method: RemoteHttpMethod,
    val path: String,
    val queryParameters: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val bodyJson: String? = null,
    val idempotencyKey: String? = null,
    val requiresAuthentication: Boolean = true,
) {
    init {
        require(operation.matches(SafeOperation)) { "operation must be a stable safe identifier" }
        require(path.matches(SafeApiPath) && path.hasNoTraversalSegments()) {
            "path must use safe segments under the versioned API prefix"
        }
        require(queryParameters.keys.none(String::isSensitiveTransportKey)) {
            "credentials are forbidden in query parameters"
        }
        require(queryParameters.all { (name, value) ->
            name.matches(SafeQueryName) &&
                value.length <= MaxQueryValueLength &&
                value.none(Char::isISOControl)
        }) {
            "query parameters contain an invalid name or value"
        }
        require(operation == DefaultOperation || headers.keys.none(String::isManagedTransportHeader)) {
            "credential and idempotency headers are owned by TransportBoundary"
        }
        require(headers.all { (name, value) ->
            name.matches(SafeHeaderName) &&
                value.length <= MaxHeaderValueLength &&
                value.none { it == '\r' || it == '\n' }
        }) {
            "headers contain an invalid name or value"
        }
        require(bodyJson == null || bodyJson.toByteArray(Charsets.UTF_8).size <= MaxRequestBodyBytes) {
            "request body exceeds the configured transport limit"
        }
        idempotencyKey?.let(::requireValidIdempotencyKey)
        if (operation != DefaultOperation && method != RemoteHttpMethod.GET) {
            requireNotNull(idempotencyKey) { "state-changing requests require an idempotency key" }
        }
    }

    val isRetryable: Boolean
        get() = method == RemoteHttpMethod.GET || idempotencyKey != null

    override fun toString(): String =
        "RemoteRequestSpec(" +
            "operation=$operation, " +
            "method=$method, " +
            "path=$path, " +
            "queryParameters=${queryParameters.keys}, " +
            "headers=${headers.keys}, " +
            "bodyJson=${if (bodyJson == null) "null" else "[redacted]"}, " +
            "idempotencyKey=${if (idempotencyKey == null) "null" else "[redacted]"}, " +
            "requiresAuthentication=$requiresAuthentication)"

    companion object {
        const val DefaultOperation = "legacy.operation"
        const val ApiPrefix = "/api/v1/"
        private val SafeOperation = Regex("[a-z][a-z0-9]*(?:\\.[a-z0-9]+)*")
        private val SafeApiPath = Regex("^/api/v1(?:/[A-Za-z0-9._~-]+)+$")
        private val SafeQueryName = Regex("[A-Za-z][A-Za-z0-9._~-]{0,63}")
        private val SafeHeaderName = Regex("[A-Za-z][A-Za-z0-9-]{0,63}")
        private const val MaxQueryValueLength = 512
        private const val MaxHeaderValueLength = 1_024
        private const val MaxRequestBodyBytes = 1024 * 1024
    }
}

data class RemoteWebSocketSpec(
    val operation: String,
    val sessionId: String,
    val path: String,
    val queryParameters: Map<String, String> = emptyMap(),
    val subprotocol: String = OnlineV1Subprotocol,
) {
    init {
        require(operation.matches(Regex("[a-z][a-z0-9]*(?:\\.[a-z0-9]+)*"))) {
            "operation must be a stable safe identifier"
        }
        require(
            path.matches(Regex("^/api/v1(?:/[A-Za-z0-9._~-]+)+$")) &&
                path.hasNoTraversalSegments()
        ) {
            "WebSocket path must use safe segments under the versioned API prefix"
        }
        require(queryParameters.isEmpty()) { "WebSocket query parameters are forbidden in v1" }
        require(subprotocol == OnlineV1Subprotocol) { "unsupported online subprotocol" }
        requireSafePathSegment(sessionId, "sessionId")
    }

    companion object {
        const val OnlineV1Subprotocol = "inplacex.online.v1"
    }
}

data class RemoteAuthBootstrapPayload(
    val installationId: String,
    val platform: RemotePlatform = RemotePlatform.ANDROID,
    val appVersion: String? = null,
    val locale: String? = null,
) {
    override fun toString(): String =
        "RemoteAuthBootstrapPayload(installationId=[redacted], platform=$platform)"
}

data class RemoteGoogleAuthenticationPayload(
    val idToken: String,
    val nonce: String,
) {
    override fun toString(): String =
        "RemoteGoogleAuthenticationPayload(idToken=[redacted], nonce=[redacted])"
}

data class RemoteCloudSavePayload(
    val commandId: String,
    val expectedRevision: Long,
    val schemaVersion: Int,
    val stateJson: String,
) {
    override fun toString(): String =
        "RemoteCloudSavePayload(commandId=$commandId, expectedRevision=$expectedRevision, " +
            "schemaVersion=$schemaVersion, stateJson=[redacted])"
}

data class RemoteMatchmakingPayload(
    val commandId: String,
    val mode: RemoteMatchmakingMode,
)

data class RemoteSubmitSecretPayload(
    val sessionId: String,
    val commandId: String,
    val expectedRevision: Long,
    val secret: String,
) {
    override fun toString(): String =
        "RemoteSubmitSecretPayload(sessionId=$sessionId, commandId=$commandId, " +
            "expectedRevision=$expectedRevision, secret=[redacted])"
}

data class RemoteFriendInvitePayload(
    val commandId: String,
    val playStyle: RemoteFriendPlayStyle,
    val codeLength: Int,
)

data class RemoteSubmitGuessPayload(
    val sessionId: String,
    val commandId: String,
    val expectedRevision: Long,
    val guess: String,
) {
    override fun toString(): String =
        "RemoteSubmitGuessPayload(sessionId=$sessionId, commandId=$commandId, " +
            "expectedRevision=$expectedRevision, guess=[redacted])"
}

interface RemotePlatformGateway : MatchmakingStub {
    fun prepareGuestBootstrap(
        payload: RemoteAuthBootstrapPayload,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): RemoteRequestSpec

    fun prepareRefresh(
        refreshToken: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): RemoteRequestSpec

    fun prepareGoogleChallenge(
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): RemoteRequestSpec

    fun prepareGoogleAuthentication(
        payload: RemoteGoogleAuthenticationPayload,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): RemoteRequestSpec

    fun prepareReadCloudSave(): RemoteRequestSpec

    fun prepareWriteCloudSave(
        payload: RemoteCloudSavePayload,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): RemoteRequestSpec

    fun prepareCreateMatchmakingTicket(
        payload: RemoteMatchmakingPayload,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): RemoteRequestSpec

    fun prepareReadMatchmakingTicket(ticketId: String): RemoteRequestSpec

    fun prepareCreateFriendInvite(
        payload: RemoteFriendInvitePayload,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): RemoteRequestSpec

    fun prepareReadFriendInvite(inviteCode: String): RemoteRequestSpec

    fun prepareAcceptFriendInvite(
        inviteCode: String,
        commandId: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): RemoteRequestSpec

    fun prepareCancelMatchmakingTicket(
        ticketId: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): RemoteRequestSpec

    fun prepareReadSession(sessionId: String): RemoteRequestSpec

    fun prepareReconnect(
        sessionId: String,
        commandId: String,
        lastSeenEventSeq: Long?,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): RemoteRequestSpec

    fun prepareSubmitSecret(
        payload: RemoteSubmitSecretPayload,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): RemoteRequestSpec

    fun prepareSubmitGuess(
        payload: RemoteSubmitGuessPayload,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): RemoteRequestSpec

    fun prepareSessionTransport(sessionId: String): RemoteWebSocketSpec
}

class OfflineOnlineSession(
    override val sessionId: String,
) : OnlineSession {
    override val isConnected: Boolean = false

    override suspend fun send(frame: String) {
        throw IllegalStateException("offline session is not connected")
    }

    override suspend fun receive(): String? = null

    override suspend fun close() = Unit
}

class ContractRemotePlatformGateway : RemotePlatformGateway {
    override fun createLocalPlaceholderSession(): OnlineSession =
        OfflineOnlineSession(sessionId = "offline-${UUID.randomUUID()}")

    override fun prepareGuestBootstrap(
        payload: RemoteAuthBootstrapPayload,
        idempotencyKey: String,
    ): RemoteRequestSpec {
        require(payload.installationId.isNotBlank()) { "installationId is required" }
        return RemoteRequestSpec(
            operation = "auth.bootstrap",
            method = RemoteHttpMethod.POST,
            path = "/api/v1/auth/bootstrap",
            bodyJson = jsonObject(
                "installationId" to JsonPrimitive(payload.installationId),
                "platform" to JsonPrimitive(payload.platform.name.lowercase()),
                "appVersion" to payload.appVersion?.let(::JsonPrimitive),
                "locale" to payload.locale?.let(::JsonPrimitive),
            ),
            idempotencyKey = idempotencyKey,
            requiresAuthentication = false,
        )
    }

    override fun prepareRefresh(
        refreshToken: String,
        idempotencyKey: String,
    ): RemoteRequestSpec {
        require(refreshToken.isNotBlank()) { "refresh token is required" }
        return RemoteRequestSpec(
            operation = "auth.refresh",
            method = RemoteHttpMethod.POST,
            path = "/api/v1/auth/refresh",
            bodyJson = jsonObject("refreshToken" to JsonPrimitive(refreshToken)),
            idempotencyKey = idempotencyKey,
            requiresAuthentication = false,
        )
    }

    override fun prepareGoogleChallenge(
        idempotencyKey: String,
    ): RemoteRequestSpec =
        RemoteRequestSpec(
            operation = "auth.google.challenge",
            method = RemoteHttpMethod.POST,
            path = "/api/v1/auth/google/challenge",
            idempotencyKey = idempotencyKey,
        )

    override fun prepareGoogleAuthentication(
        payload: RemoteGoogleAuthenticationPayload,
        idempotencyKey: String,
    ): RemoteRequestSpec {
        require(payload.idToken.length in 1..8_192 && payload.idToken.none(Char::isWhitespace)) {
            "Google ID token has an invalid format"
        }
        require(payload.nonce.matches(Regex("[A-Za-z0-9_-]{32,128}"))) {
            "Google nonce has an invalid format"
        }
        return RemoteRequestSpec(
            operation = "auth.google.exchange",
            method = RemoteHttpMethod.POST,
            path = "/api/v1/auth/google",
            bodyJson = jsonObject(
                "idToken" to JsonPrimitive(payload.idToken),
                "nonce" to JsonPrimitive(payload.nonce),
            ),
            idempotencyKey = idempotencyKey,
        )
    }

    override fun prepareReadCloudSave(): RemoteRequestSpec =
        RemoteRequestSpec(
            operation = "cloudsave.read",
            method = RemoteHttpMethod.GET,
            path = "/api/v1/me/save",
        )

    override fun prepareWriteCloudSave(
        payload: RemoteCloudSavePayload,
        idempotencyKey: String,
    ): RemoteRequestSpec {
        require(payload.expectedRevision >= 0L) { "expectedRevision must be non-negative" }
        require(payload.schemaVersion > 0) { "schemaVersion must be positive" }
        requireSafeUuid(payload.commandId, "commandId")
        return RemoteRequestSpec(
            operation = "cloudsave.write",
            method = RemoteHttpMethod.PUT,
            path = "/api/v1/me/save",
            headers = mapOf("If-Match" to payload.expectedRevision.toString()),
            bodyJson = jsonObject(
                "commandId" to JsonPrimitive(payload.commandId),
                "expectedRevision" to JsonPrimitive(payload.expectedRevision),
                "schemaVersion" to JsonPrimitive(payload.schemaVersion),
                "stateJson" to JsonPrimitive(payload.stateJson),
            ),
            idempotencyKey = idempotencyKey,
        )
    }

    override fun prepareCreateMatchmakingTicket(
        payload: RemoteMatchmakingPayload,
        idempotencyKey: String,
    ): RemoteRequestSpec {
        requireSafeUuid(payload.commandId, "commandId")
        return RemoteRequestSpec(
            operation = "matchmaking.create",
            method = RemoteHttpMethod.POST,
            path = "/api/v1/matchmaking/tickets",
            bodyJson = jsonObject(
                "commandId" to JsonPrimitive(payload.commandId),
                "mode" to JsonPrimitive(payload.mode.name.lowercase()),
            ),
            idempotencyKey = idempotencyKey,
        )
    }

    override fun prepareReadMatchmakingTicket(ticketId: String): RemoteRequestSpec {
        requireSafePathSegment(ticketId, "ticketId")
        return RemoteRequestSpec(
            operation = "matchmaking.read",
            method = RemoteHttpMethod.GET,
            path = "/api/v1/matchmaking/tickets/$ticketId",
        )
    }

    override fun prepareCreateFriendInvite(
        payload: RemoteFriendInvitePayload,
        idempotencyKey: String,
    ): RemoteRequestSpec {
        requireSafeUuid(payload.commandId, "commandId")
        require(payload.codeLength in 4..10) { "friend code length must be in 4..10" }
        return RemoteRequestSpec(
            operation = "friends.invite.create",
            method = RemoteHttpMethod.POST,
            path = "/api/v1/friends/invites",
            bodyJson = jsonObject(
                "commandId" to JsonPrimitive(payload.commandId),
                "playStyle" to JsonPrimitive(payload.playStyle.name.lowercase()),
                "codeLength" to JsonPrimitive(payload.codeLength),
            ),
            idempotencyKey = idempotencyKey,
        )
    }

    override fun prepareReadFriendInvite(inviteCode: String): RemoteRequestSpec {
        requireFriendInviteCode(inviteCode)
        return RemoteRequestSpec(
            operation = "friends.invite.read",
            method = RemoteHttpMethod.GET,
            path = "/api/v1/friends/invites/${inviteCode.uppercase()}",
        )
    }

    override fun prepareAcceptFriendInvite(
        inviteCode: String,
        commandId: String,
        idempotencyKey: String,
    ): RemoteRequestSpec {
        requireFriendInviteCode(inviteCode)
        requireSafeUuid(commandId, "commandId")
        return RemoteRequestSpec(
            operation = "friends.invite.accept",
            method = RemoteHttpMethod.POST,
            path = "/api/v1/friends/invites/${inviteCode.uppercase()}/accept",
            bodyJson = jsonObject("commandId" to JsonPrimitive(commandId)),
            idempotencyKey = idempotencyKey,
        )
    }

    override fun prepareCancelMatchmakingTicket(
        ticketId: String,
        idempotencyKey: String,
    ): RemoteRequestSpec {
        requireSafePathSegment(ticketId, "ticketId")
        return RemoteRequestSpec(
            operation = "matchmaking.cancel",
            method = RemoteHttpMethod.DELETE,
            path = "/api/v1/matchmaking/tickets/$ticketId",
            idempotencyKey = idempotencyKey,
        )
    }

    override fun prepareReadSession(sessionId: String): RemoteRequestSpec {
        requireSafePathSegment(sessionId, "sessionId")
        return RemoteRequestSpec(
            operation = "session.read",
            method = RemoteHttpMethod.GET,
            path = "/api/v1/sessions/$sessionId",
        )
    }

    override fun prepareReconnect(
        sessionId: String,
        commandId: String,
        lastSeenEventSeq: Long?,
        idempotencyKey: String,
    ): RemoteRequestSpec {
        requireSafePathSegment(sessionId, "sessionId")
        requireSafeUuid(commandId, "commandId")
        require(lastSeenEventSeq == null || lastSeenEventSeq >= 0L) {
            "lastSeenEventSeq must be non-negative"
        }
        return RemoteRequestSpec(
            operation = "session.reconnect",
            method = RemoteHttpMethod.POST,
            path = "/api/v1/sessions/$sessionId/reconnect",
            bodyJson = jsonObject(
                "commandId" to JsonPrimitive(commandId),
                "lastSeenEventSeq" to lastSeenEventSeq?.let(::JsonPrimitive),
            ),
            idempotencyKey = idempotencyKey,
        )
    }

    override fun prepareSubmitSecret(
        payload: RemoteSubmitSecretPayload,
        idempotencyKey: String,
    ): RemoteRequestSpec {
        requireSafePathSegment(payload.sessionId, "sessionId")
        requireSafeUuid(payload.commandId, "commandId")
        require(payload.expectedRevision >= 0L) { "expectedRevision must be non-negative" }
        require(payload.secret.matches(Regex("\\d{4,20}"))) { "secret must contain 4 to 20 digits" }
        return RemoteRequestSpec(
            operation = "duel.submitsecret",
            method = RemoteHttpMethod.POST,
            path = "/api/v1/sessions/${payload.sessionId}/setup/secret",
            headers = mapOf("If-Match" to payload.expectedRevision.toString()),
            bodyJson = jsonObject(
                "commandId" to JsonPrimitive(payload.commandId),
                "expectedRevision" to JsonPrimitive(payload.expectedRevision),
                "secret" to JsonPrimitive(payload.secret),
            ),
            idempotencyKey = idempotencyKey,
        )
    }

    override fun prepareSubmitGuess(
        payload: RemoteSubmitGuessPayload,
        idempotencyKey: String,
    ): RemoteRequestSpec {
        requireSafePathSegment(payload.sessionId, "sessionId")
        requireSafeUuid(payload.commandId, "commandId")
        require(payload.expectedRevision >= 0L) { "expectedRevision must be non-negative" }
        require(payload.guess.matches(Regex("\\d{4,20}"))) { "guess must contain 4 to 20 digits" }
        return RemoteRequestSpec(
            operation = "duel.submitguess",
            method = RemoteHttpMethod.POST,
            path = "/api/v1/sessions/${payload.sessionId}/turns",
            headers = mapOf("If-Match" to payload.expectedRevision.toString()),
            bodyJson = jsonObject(
                "commandId" to JsonPrimitive(payload.commandId),
                "expectedRevision" to JsonPrimitive(payload.expectedRevision),
                "guess" to JsonPrimitive(payload.guess),
            ),
            idempotencyKey = idempotencyKey,
        )
    }

    override fun prepareSessionTransport(sessionId: String): RemoteWebSocketSpec {
        requireSafePathSegment(sessionId, "sessionId")
        return RemoteWebSocketSpec(
            operation = "session.websocket",
            sessionId = sessionId,
            path = "/api/v1/ws/sessions/$sessionId",
        )
    }
}

private fun jsonObject(vararg fields: Pair<String, JsonPrimitive?>): String =
    JsonObject(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()).toString()

private fun requireValidIdempotencyKey(value: String) {
    require(value.matches(Regex("[A-Za-z0-9._~-]{1,128}"))) {
        "idempotency key has an invalid format"
    }
}

private fun requireSafeUuid(value: String, field: String) {
    require(runCatching { UUID.fromString(value) }.isSuccess) { "$field must be a UUID" }
}

private fun requireSafePathSegment(value: String, field: String) {
    require(value.matches(Regex("[A-Za-z0-9._~-]{1,128}"))) {
        "$field is not a safe path segment"
    }
    require(value != "." && value != "..") {
        "$field must not be a traversal segment"
    }
}

private fun requireFriendInviteCode(value: String) {
    require(value.uppercase().matches(Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8}"))) {
        "inviteCode has an invalid format"
    }
}

private fun String.hasNoTraversalSegments(): Boolean =
    removePrefix("/").split('/').none { segment ->
        segment == "." || segment == ".."
    }

private fun String.isSensitiveTransportKey(): Boolean {
    val normalized = lowercase()
    return listOf("authorization", "cookie", "credential", "secret", "token").any(normalized::contains)
}

private fun String.isManagedTransportHeader(): Boolean {
    val normalized = lowercase()
    return normalized == "authorization" ||
        normalized == "proxy-authorization" ||
        normalized == "cookie" ||
        normalized == "set-cookie" ||
        normalized == "idempotency-key" ||
        normalized == "x-api-key"
}
