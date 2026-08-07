package com.mirkori.inplacex.backend.online

import java.time.Clock
import kotlin.math.max

/**
 * Process-local protection for the single-instance online runtime.
 *
 * Nginx remains the coarse network perimeter. This limiter deliberately keys authenticated
 * requests by platform principal and operation, so changing an IP address cannot bypass the
 * application-level command budget. The bounded key registry fails closed when it is full.
 */
class OnlineAbuseProtector(
    private val clock: Clock = Clock.systemUTC(),
    private val windowMillis: Long = DefaultWindowMillis,
    private val authenticationAttemptLimit: Int = DefaultAuthenticationAttemptLimit,
    private val invalidAuthenticationLimit: Int = DefaultInvalidAuthenticationLimit,
    private val operationLimits: Map<OnlineOperation, Int> = DefaultOperationLimits,
    private val maximumConcurrentWebSocketsPerPrincipal: Int = DefaultWebSocketsPerPrincipal,
    private val maximumConcurrentWebSockets: Int = DefaultGlobalWebSockets,
    private val maximumTrackedKeys: Int = DefaultMaximumTrackedKeys,
) {
    private val lock = Any()
    private val windows = mutableMapOf<RateLimitKey, RateWindow>()
    private val webSocketsByPrincipal = mutableMapOf<String, Int>()
    private var openWebSockets = 0

    init {
        require(windowMillis > 0) { "Rate limit window must be positive" }
        require(authenticationAttemptLimit > 0) { "Authentication attempt limit must be positive" }
        require(invalidAuthenticationLimit > 0) { "Invalid authentication limit must be positive" }
        require(operationLimits.keys.containsAll(OnlineOperation.entries)) {
            "Every online operation requires an explicit rate limit"
        }
        require(operationLimits.values.all { it > 0 }) { "Online operation limits must be positive" }
        require(maximumConcurrentWebSocketsPerPrincipal > 0) {
            "Per-principal WebSocket limit must be positive"
        }
        require(maximumConcurrentWebSockets > 0) { "Global WebSocket limit must be positive" }
        require(maximumTrackedKeys > 0) { "Tracked rate limit key bound must be positive" }
    }

    fun acquire(principalId: String, operation: OnlineOperation): OnlineAbuseDecision =
        acquireWindow(
            key = RateLimitKey("principal", principalId, operation.wireName),
            limit = requireNotNull(operationLimits[operation]),
        )

    fun acquireAuthenticationAttempt(remoteIdentity: String): OnlineAbuseDecision =
        acquireWindow(
            key = RateLimitKey("auth-attempt", remoteIdentity.take(MaximumRemoteIdentityCharacters), "verify"),
            limit = authenticationAttemptLimit,
        )

    fun acquireInvalidAuthentication(remoteIdentity: String): OnlineAbuseDecision =
        acquireWindow(
            key = RateLimitKey("invalid-auth", remoteIdentity.take(MaximumRemoteIdentityCharacters), "verify"),
            limit = invalidAuthenticationLimit,
        )

    fun openWebSocket(principalId: String): WebSocketLease? = synchronized(lock) {
        val principalConnections = webSocketsByPrincipal[principalId] ?: 0
        if (
            openWebSockets >= maximumConcurrentWebSockets ||
            principalConnections >= maximumConcurrentWebSocketsPerPrincipal
        ) {
            return@synchronized null
        }
        openWebSockets += 1
        webSocketsByPrincipal[principalId] = principalConnections + 1
        WebSocketLease { closeWebSocket(principalId) }
    }

    private fun acquireWindow(key: RateLimitKey, limit: Int): OnlineAbuseDecision = synchronized(lock) {
        val now = clock.millis()
        val current = windows[key]
        if (current == null) {
            evictExpiredWindows(now)
            if (windows.size >= maximumTrackedKeys) {
                return@synchronized OnlineAbuseDecision.Rejected(DefaultRetryAfterSeconds)
            }
            windows[key] = RateWindow(startedAtMillis = now, requests = 1)
            return@synchronized OnlineAbuseDecision.Allowed
        }
        if (now < current.startedAtMillis || now - current.startedAtMillis >= windowMillis) {
            windows[key] = RateWindow(startedAtMillis = now, requests = 1)
            return@synchronized OnlineAbuseDecision.Allowed
        }
        if (current.requests >= limit) {
            val remainingMillis = windowMillis - (now - current.startedAtMillis)
            val retryAfterSeconds = max(1L, (remainingMillis + 999L) / 1_000L)
            return@synchronized OnlineAbuseDecision.Rejected(retryAfterSeconds)
        }
        current.requests += 1
        OnlineAbuseDecision.Allowed
    }

    private fun evictExpiredWindows(now: Long) {
        windows.entries.removeIf { (_, window) ->
            now < window.startedAtMillis || now - window.startedAtMillis >= windowMillis
        }
    }

    private fun closeWebSocket(principalId: String) = synchronized(lock) {
        val principalConnections = checkNotNull(webSocketsByPrincipal[principalId])
        if (principalConnections == 1) {
            webSocketsByPrincipal.remove(principalId)
        } else {
            webSocketsByPrincipal[principalId] = principalConnections - 1
        }
        check(openWebSockets > 0)
        openWebSockets -= 1
    }

    class WebSocketLease internal constructor(
        private val release: () -> Unit,
    ) : AutoCloseable {
        private var closed = false

        override fun close() = synchronized(this) {
            if (!closed) {
                closed = true
                release()
            }
        }
    }

    private data class RateLimitKey(
        val scope: String,
        val principal: String,
        val operation: String,
    )

    private data class RateWindow(
        val startedAtMillis: Long,
        var requests: Int,
    )

    companion object {
        private const val DefaultWindowMillis = 60_000L
        private const val DefaultAuthenticationAttemptLimit = 120
        private const val DefaultInvalidAuthenticationLimit = 30
        private const val DefaultWebSocketsPerPrincipal = 3
        private const val DefaultGlobalWebSockets = 1_000
        private const val DefaultMaximumTrackedKeys = 50_000
        private const val DefaultRetryAfterSeconds = 60L
        private const val MaximumRemoteIdentityCharacters = 128

        private val DefaultOperationLimits = mapOf(
            OnlineOperation.CreateMatchmakingTicket to 20,
            OnlineOperation.ReadMatchmakingTicket to 120,
            OnlineOperation.CreateFriendInvite to 20,
            OnlineOperation.ReadFriendInvite to 120,
            OnlineOperation.AcceptFriendInvite to 30,
            OnlineOperation.ReadSession to 180,
            OnlineOperation.MigrateLegacyMembership to 10,
            OnlineOperation.ReconnectSession to 30,
            OnlineOperation.SubmitSecret to 30,
            OnlineOperation.SubmitTurn to 120,
            OnlineOperation.OpenWebSocket to 30,
        )
    }
}

enum class OnlineOperation(val wireName: String) {
    CreateMatchmakingTicket("matchmaking.create"),
    ReadMatchmakingTicket("matchmaking.read"),
    CreateFriendInvite("friends.invite.create"),
    ReadFriendInvite("friends.invite.read"),
    AcceptFriendInvite("friends.invite.accept"),
    ReadSession("sessions.read"),
    MigrateLegacyMembership("sessions.legacy-membership.migrate"),
    ReconnectSession("sessions.reconnect"),
    SubmitSecret("sessions.secret.submit"),
    SubmitTurn("sessions.turn.submit"),
    OpenWebSocket("websocket.open"),
}

sealed interface OnlineAbuseDecision {
    data object Allowed : OnlineAbuseDecision

    data class Rejected(val retryAfterSeconds: Long) : OnlineAbuseDecision
}
