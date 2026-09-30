package com.mirkori.inplacex.platform.online

import com.mirkori.inplacex.logging.InplaceXLogger

data class OnlineTransportPolicy(
    val maxAttempts: Int = 3,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive." }
    }
}

fun interface OnlineConnectivity {
    fun isOnline(): Boolean
}

interface OnlineTokenStore {
    fun accessTokenOrNull(): String?

    fun refreshAccessTokenOrNull(): String?
}

data class OnlineRestResponse(
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

enum class OnlineFailureCode {
    OFFLINE,
    AUTHENTICATION_UNAVAILABLE,
    UNAUTHORIZED,
    TIMEOUT,
    NETWORK,
    RATE_LIMITED,
    OVERLOADED,
    PROTOCOL,
}

sealed interface OnlineRestAttempt {
    data class Succeeded(val response: OnlineRestResponse) : OnlineRestAttempt

    data class Failed(val code: OnlineFailureCode) : OnlineRestAttempt
}

fun interface OnlineRestExecutor {
    fun execute(request: RemoteRequestSpec): OnlineRestAttempt
}

data class OnlineWebSocketRequest(
    val sessionId: String,
    val path: String,
    val subprotocol: String,
    val headers: Map<String, String>,
)

interface OnlineWebSocketConnection {
    fun close()
}

sealed interface OnlineWebSocketAttempt {
    data class Connected(val connection: OnlineWebSocketConnection) : OnlineWebSocketAttempt

    data class Failed(val code: OnlineFailureCode) : OnlineWebSocketAttempt
}

fun interface OnlineWebSocketConnector {
    fun connect(request: OnlineWebSocketRequest): OnlineWebSocketAttempt
}

sealed interface OnlineTransportResult<out T> {
    data class Success<T>(val value: T, val attempts: Int) : OnlineTransportResult<T>

    data class Failure(val code: OnlineFailureCode, val attempts: Int) : OnlineTransportResult<Nothing>
}

interface OnlineTransportClient {
    fun execute(request: RemoteRequestSpec): OnlineTransportResult<OnlineRestResponse>

    fun connect(sessionId: String): OnlineTransportResult<OnlineWebSocketConnection>
}

class DeterministicOnlineTransportClient(
    private val connectivity: OnlineConnectivity,
    private val tokenStore: OnlineTokenStore,
    private val restExecutor: OnlineRestExecutor,
    private val webSocketConnector: OnlineWebSocketConnector,
    private val policy: OnlineTransportPolicy = OnlineTransportPolicy(),
    private val logger: InplaceXLogger = InplaceXLogger(),
) : OnlineTransportClient {
    override fun execute(request: RemoteRequestSpec): OnlineTransportResult<OnlineRestResponse> {
        var attempts = 0
        var refreshedToken = false

        while (attempts < policy.maxAttempts) {
            if (!connectivity.isOnline()) return offline(attempts)

            val authenticatedRequest = authenticatedRequestOrNull(request) ?: return authenticationUnavailable(attempts)
            attempts += 1
            when (val attempt = restExecutor.execute(authenticatedRequest)) {
                is OnlineRestAttempt.Succeeded -> return OnlineTransportResult.Success(attempt.response, attempts)
                is OnlineRestAttempt.Failed -> {
                    if (attempt.code == OnlineFailureCode.UNAUTHORIZED && !refreshedToken) {
                        refreshedToken = tokenStore.refreshAccessTokenOrNull() != null
                        if (refreshedToken) continue
                    }
                    if (!attempt.code.isRetryable() || attempts == policy.maxAttempts) {
                        return failure(attempt.code, attempts)
                    }
                    logRetry("rest", attempt.code, attempts)
                }
            }
        }

        return failure(OnlineFailureCode.NETWORK, attempts)
    }

    override fun connect(sessionId: String): OnlineTransportResult<OnlineWebSocketConnection> {
        var attempts = 0
        var refreshedToken = false

        while (attempts < policy.maxAttempts) {
            if (!connectivity.isOnline()) return offline(attempts)

            val accessToken = tokenStore.accessTokenOrNull() ?: return authenticationUnavailable(attempts)
            val request = OnlineWebSocketRequest(
                sessionId = sessionId,
                path = "/api/v1/ws/sessions/$sessionId",
                subprotocol = WebSocketSubprotocol,
                headers = mapOf("Authorization" to "Bearer $accessToken"),
            )
            attempts += 1
            when (val attempt = webSocketConnector.connect(request)) {
                is OnlineWebSocketAttempt.Connected -> return OnlineTransportResult.Success(attempt.connection, attempts)
                is OnlineWebSocketAttempt.Failed -> {
                    if (attempt.code == OnlineFailureCode.UNAUTHORIZED && !refreshedToken) {
                        refreshedToken = tokenStore.refreshAccessTokenOrNull() != null
                        if (refreshedToken) continue
                    }
                    if (!attempt.code.isRetryable() || attempts == policy.maxAttempts) {
                        return failure(attempt.code, attempts)
                    }
                    logRetry("websocket", attempt.code, attempts)
                }
            }
        }

        return failure(OnlineFailureCode.NETWORK, attempts)
    }

    private fun authenticatedRequestOrNull(request: RemoteRequestSpec): RemoteRequestSpec? {
        if (!request.requiresAuthentication) return request
        val accessToken = tokenStore.accessTokenOrNull() ?: return null
        return request.copy(headers = request.headers + ("Authorization" to "Bearer $accessToken"))
    }

    private fun offline(attempts: Int): OnlineTransportResult.Failure =
        failure(OnlineFailureCode.OFFLINE, attempts)

    private fun authenticationUnavailable(attempts: Int): OnlineTransportResult.Failure =
        failure(OnlineFailureCode.AUTHENTICATION_UNAVAILABLE, attempts)

    private fun failure(code: OnlineFailureCode, attempts: Int): OnlineTransportResult.Failure {
        logger.warn(
            tag = LogTag,
            message = "online transport request failed",
            attributes = mapOf("failureCode" to code.name, "attempts" to attempts.toString()),
        )
        return OnlineTransportResult.Failure(code, attempts)
    }

    private fun logRetry(operation: String, code: OnlineFailureCode, attempts: Int) {
        logger.warn(
            tag = LogTag,
            message = "online transport retry scheduled",
            attributes = mapOf(
                "operation" to operation,
                "failureCode" to code.name,
                "attempt" to attempts.toString(),
            ),
        )
    }

    private fun OnlineFailureCode.isRetryable(): Boolean = when (this) {
        OnlineFailureCode.TIMEOUT,
        OnlineFailureCode.NETWORK,
        OnlineFailureCode.RATE_LIMITED,
        OnlineFailureCode.OVERLOADED,
        -> true

        OnlineFailureCode.OFFLINE,
        OnlineFailureCode.AUTHENTICATION_UNAVAILABLE,
        OnlineFailureCode.UNAUTHORIZED,
        OnlineFailureCode.PROTOCOL,
        -> false
    }

    private companion object {
        const val LogTag = "OnlineTransport"
        const val WebSocketSubprotocol = "inplacex.online.v1"
    }
}
