package com.mirkori.inplacex.platform.online

interface OnlineSession {
    val sessionId: String
    val isConnected: Boolean

    suspend fun send(frame: String)

    suspend fun receive(): String?

    suspend fun close()
}

interface MatchmakingStub {
    fun createLocalPlaceholderSession(): OnlineSession
}

interface TransportBoundary {
    suspend fun execute(request: RemoteRequestSpec): RemoteCallResult

    suspend fun openSession(request: RemoteWebSocketSpec): OnlineSessionOpenResult
}
