package com.mirkori.inplacex.platform.online

interface OnlineSession {
    val sessionId: String
    val isConnected: Boolean
}

interface MatchmakingStub {
    fun createLocalPlaceholderSession(): OnlineSession
}

interface TransportBoundary {
    fun send(event: String)
}
