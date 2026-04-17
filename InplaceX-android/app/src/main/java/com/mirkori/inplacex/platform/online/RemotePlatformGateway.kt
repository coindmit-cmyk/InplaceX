package com.mirkori.inplacex.platform.online

import java.util.UUID

enum class RemoteHttpMethod {
    GET,
    POST,
    PUT,
    DELETE,
}

enum class RemoteIdentityProvider {
    GUEST,
    GOOGLE_PLAY,
    FACEBOOK,
    APPLE,
    EMAIL,
}

enum class RemoteRoomVisibility {
    PRIVATE,
    FRIENDS_ONLY,
    PUBLIC,
}

enum class RemoteRoomLifecycle {
    WAITING,
    READY,
    IN_PROGRESS,
    FINISHED,
    CANCELLED,
}

enum class RemoteMatchLifecycle {
    CREATED,
    RUNNING,
    FINISHED,
    CANCELLED,
}

data class RemoteRequestSpec(
    val method: RemoteHttpMethod,
    val path: String,
    val queryParameters: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: Map<String, Any?> = emptyMap(),
    val idempotencyKey: String? = null,
)

data class RemoteProgressPayload(
    val playerId: String,
    val gameSlug: String,
    val baseRevision: Long,
    val stats: Map<String, Int>,
    val balances: Map<String, Int>,
    val stateJson: String,
)

data class RemoteRoomQuery(
    val gameSlug: String,
    val visibility: RemoteRoomVisibility? = null,
    val lifecycle: RemoteRoomLifecycle? = null,
    val limit: Int = 20,
)

data class RemoteCreateRoomPayload(
    val playerId: String,
    val gameSlug: String,
    val roomName: String,
    val visibility: RemoteRoomVisibility,
    val maxMembers: Int,
    val inviteCode: String? = null,
    val configJson: String = "{}",
)

data class RemoteJoinRoomPayload(
    val roomId: String,
    val playerId: String,
    val displayName: String,
    val inviteCode: String? = null,
)

data class RemoteSubmitTurnPayload(
    val roomId: String,
    val matchId: String,
    val playerId: String,
    val clientTurnId: String = UUID.randomUUID().toString(),
    val turnIndex: Int,
    val guess: String,
    val score: Int,
)

data class RemoteTransportSession(
    val session: OnlineSession,
    val connectRequest: RemoteRequestSpec,
    val transport: TransportBoundary,
)

interface RemotePlatformGateway : MatchmakingStub {
    fun prepareGuestAuth(
        installationId: String,
        displayName: String,
        locale: String,
        regionCode: String,
    ): RemoteRequestSpec

    fun prepareLinkIdentity(
        playerId: String,
        provider: RemoteIdentityProvider,
        idToken: String,
    ): RemoteRequestSpec

    fun prepareFetchProfile(playerId: String): RemoteRequestSpec

    fun preparePullProgress(
        playerId: String,
        gameSlug: String,
    ): RemoteRequestSpec

    fun preparePushProgress(
        payload: RemoteProgressPayload,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): RemoteRequestSpec

    fun prepareListRooms(query: RemoteRoomQuery): RemoteRequestSpec

    fun prepareCreateRoom(
        payload: RemoteCreateRoomPayload,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): RemoteRequestSpec

    fun prepareJoinRoom(
        payload: RemoteJoinRoomPayload,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): RemoteRequestSpec

    fun prepareLeaveRoom(
        roomId: String,
        playerId: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): RemoteRequestSpec

    fun prepareFetchRoom(roomId: String): RemoteRequestSpec

    fun prepareSubmitTurn(
        payload: RemoteSubmitTurnPayload,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): RemoteRequestSpec

    fun prepareRoomTransport(
        roomId: String,
        playerId: String,
    ): RemoteTransportSession
}

data class StubOnlineSession(
    override val sessionId: String,
    override val isConnected: Boolean,
) : OnlineSession

class BufferedTransportBoundary : TransportBoundary {
    private val events = mutableListOf<String>()

    override fun send(event: String) {
        events += event
    }

    fun snapshot(): List<String> = events.toList()
}

class StubRemotePlatformGateway(
    private val apiPrefix: String = "/v1",
) : RemotePlatformGateway {
    override fun createLocalPlaceholderSession(): OnlineSession {
        return StubOnlineSession(
            sessionId = "local-${UUID.randomUUID()}",
            isConnected = false,
        )
    }

    override fun prepareGuestAuth(
        installationId: String,
        displayName: String,
        locale: String,
        regionCode: String,
    ): RemoteRequestSpec {
        return RemoteRequestSpec(
            method = RemoteHttpMethod.POST,
            path = "$apiPrefix/auth/guest",
            body = linkedMapOf(
                "installationId" to installationId,
                "displayName" to displayName,
                "locale" to locale,
                "regionCode" to regionCode,
            ),
        )
    }

    override fun prepareLinkIdentity(
        playerId: String,
        provider: RemoteIdentityProvider,
        idToken: String,
    ): RemoteRequestSpec {
        return RemoteRequestSpec(
            method = RemoteHttpMethod.POST,
            path = "$apiPrefix/auth/link/${provider.name.lowercase()}",
            body = linkedMapOf(
                "playerId" to playerId,
                "idToken" to idToken,
            ),
        )
    }

    override fun prepareFetchProfile(playerId: String): RemoteRequestSpec {
        return RemoteRequestSpec(
            method = RemoteHttpMethod.GET,
            path = "$apiPrefix/players/$playerId",
        )
    }

    override fun preparePullProgress(
        playerId: String,
        gameSlug: String,
    ): RemoteRequestSpec {
        return RemoteRequestSpec(
            method = RemoteHttpMethod.GET,
            path = "$apiPrefix/players/$playerId/progress/$gameSlug",
        )
    }

    override fun preparePushProgress(
        payload: RemoteProgressPayload,
        idempotencyKey: String,
    ): RemoteRequestSpec {
        return RemoteRequestSpec(
            method = RemoteHttpMethod.PUT,
            path = "$apiPrefix/players/${payload.playerId}/progress/${payload.gameSlug}",
            headers = mapOf("Idempotency-Key" to idempotencyKey),
            idempotencyKey = idempotencyKey,
            body = linkedMapOf(
                "playerId" to payload.playerId,
                "gameSlug" to payload.gameSlug,
                "baseRevision" to payload.baseRevision,
                "stats" to payload.stats,
                "balances" to payload.balances,
                "stateJson" to payload.stateJson,
            ),
        )
    }

    override fun prepareListRooms(query: RemoteRoomQuery): RemoteRequestSpec {
        val params = linkedMapOf(
            "gameSlug" to query.gameSlug,
            "limit" to query.limit.toString(),
        )
        query.visibility?.let { params["visibility"] = it.name }
        query.lifecycle?.let { params["status"] = it.name }
        return RemoteRequestSpec(
            method = RemoteHttpMethod.GET,
            path = "$apiPrefix/rooms",
            queryParameters = params,
        )
    }

    override fun prepareCreateRoom(
        payload: RemoteCreateRoomPayload,
        idempotencyKey: String,
    ): RemoteRequestSpec {
        return RemoteRequestSpec(
            method = RemoteHttpMethod.POST,
            path = "$apiPrefix/rooms",
            headers = mapOf("Idempotency-Key" to idempotencyKey),
            idempotencyKey = idempotencyKey,
            body = linkedMapOf(
                "playerId" to payload.playerId,
                "gameSlug" to payload.gameSlug,
                "roomName" to payload.roomName,
                "visibility" to payload.visibility.name,
                "maxMembers" to payload.maxMembers,
                "inviteCode" to payload.inviteCode,
                "configJson" to payload.configJson,
            ),
        )
    }

    override fun prepareJoinRoom(
        payload: RemoteJoinRoomPayload,
        idempotencyKey: String,
    ): RemoteRequestSpec {
        return RemoteRequestSpec(
            method = RemoteHttpMethod.POST,
            path = "$apiPrefix/rooms/${payload.roomId}/members",
            headers = mapOf("Idempotency-Key" to idempotencyKey),
            idempotencyKey = idempotencyKey,
            body = linkedMapOf(
                "playerId" to payload.playerId,
                "displayName" to payload.displayName,
                "inviteCode" to payload.inviteCode,
            ),
        )
    }

    override fun prepareLeaveRoom(
        roomId: String,
        playerId: String,
        idempotencyKey: String,
    ): RemoteRequestSpec {
        return RemoteRequestSpec(
            method = RemoteHttpMethod.DELETE,
            path = "$apiPrefix/rooms/$roomId/members/$playerId",
            headers = mapOf("Idempotency-Key" to idempotencyKey),
            idempotencyKey = idempotencyKey,
        )
    }

    override fun prepareFetchRoom(roomId: String): RemoteRequestSpec {
        return RemoteRequestSpec(
            method = RemoteHttpMethod.GET,
            path = "$apiPrefix/rooms/$roomId",
        )
    }

    override fun prepareSubmitTurn(
        payload: RemoteSubmitTurnPayload,
        idempotencyKey: String,
    ): RemoteRequestSpec {
        return RemoteRequestSpec(
            method = RemoteHttpMethod.POST,
            path = "$apiPrefix/rooms/${payload.roomId}/matches/${payload.matchId}/turns",
            headers = mapOf("Idempotency-Key" to idempotencyKey),
            idempotencyKey = idempotencyKey,
            body = linkedMapOf(
                "playerId" to payload.playerId,
                "clientTurnId" to payload.clientTurnId,
                "turnIndex" to payload.turnIndex,
                "guess" to payload.guess,
                "score" to payload.score,
            ),
        )
    }

    override fun prepareRoomTransport(
        roomId: String,
        playerId: String,
    ): RemoteTransportSession {
        val session = StubOnlineSession(
            sessionId = "ws-${UUID.randomUUID()}",
            isConnected = false,
        )
        return RemoteTransportSession(
            session = session,
            connectRequest = RemoteRequestSpec(
                method = RemoteHttpMethod.GET,
                path = "$apiPrefix/rooms/$roomId/ws",
                queryParameters = mapOf(
                    "playerId" to playerId,
                    "sessionId" to session.sessionId,
                ),
            ),
            transport = BufferedTransportBoundary(),
        )
    }
}
