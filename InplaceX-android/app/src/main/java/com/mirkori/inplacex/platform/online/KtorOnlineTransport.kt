package com.mirkori.inplacex.platform.online

import com.mirkori.inplacex.logging.InplaceXLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.io.IOException
import java.net.URI
import java.util.concurrent.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AccessToken private constructor(
    private val value: String,
) {
    internal fun authorizationHeader(): String = "Bearer $value"

    internal fun sameValueAs(other: AccessToken): Boolean = value == other.value

    override fun toString(): String = "[redacted]"

    companion object {
        fun from(value: String): AccessToken {
            require(value.isNotBlank() && value.none(Char::isWhitespace)) {
                "access token has an invalid format"
            }
            return AccessToken(value)
        }
    }
}

interface AccessTokenProvider {
    suspend fun currentAccessToken(): AccessToken?

    suspend fun refreshAccessToken(rejectedToken: AccessToken): AccessToken?
}

class AccessTokenTemporarilyUnavailableException(cause: Throwable? = null) :
    IllegalStateException("access token authority is temporarily unavailable", cause)

class SingleFlightAccessTokenProvider(
    private val delegate: AccessTokenProvider,
) : AccessTokenProvider {
    private val refreshMutex = Mutex()

    override suspend fun currentAccessToken(): AccessToken? = delegate.currentAccessToken()

    override suspend fun refreshAccessToken(rejectedToken: AccessToken): AccessToken? =
        refreshMutex.withLock {
            val latest = delegate.currentAccessToken()
            if (latest != null && !latest.sameValueAs(rejectedToken)) {
                latest
            } else {
                delegate.refreshAccessToken(rejectedToken)
            }
        }
}

fun interface ConnectivityGate {
    fun isOnline(): Boolean
}

object AlwaysOnlineConnectivity : ConnectivityGate {
    override fun isOnline(): Boolean = true
}

data class OnlineEndpoint(
    val baseUrl: String,
    val allowCleartextLoopback: Boolean = false,
) {
    init {
        val uri = runCatching { URI(baseUrl) }.getOrElse {
            throw IllegalArgumentException("online endpoint is not a valid URI")
        }
        val isHttps = uri.scheme.equals("https", ignoreCase = true)
        val isLoopback = uri.scheme.equals("http", ignoreCase = true) &&
            (uri.host == "127.0.0.1" || uri.host == "localhost" || uri.host == "::1")
        require(uri.host != null && (isHttps || (allowCleartextLoopback && isLoopback))) {
            "online endpoint must use HTTPS"
        }
        require(uri.userInfo == null) { "endpoint user-info is forbidden" }
        require(uri.query == null && uri.fragment == null) { "endpoint query and fragment are forbidden" }
        require(uri.path.isNullOrEmpty() || uri.path == "/") {
            "endpoint must not contain a path"
        }
    }

    internal val normalizedBaseUrl: String = baseUrl.trimEnd('/')
}

data class KtorOnlineTransportPolicy(
    val connectTimeoutMillis: Long = 10_000L,
    val requestTimeoutMillis: Long = 15_000L,
    val socketTimeoutMillis: Long = 20_000L,
    val maxAttempts: Int = 3,
    val retryDelayMillis: Long = 250L,
    val maxFrameBytes: Int = 64 * 1024,
) {
    init {
        require(connectTimeoutMillis > 0L)
        require(requestTimeoutMillis > 0L)
        require(socketTimeoutMillis > 0L)
        require(maxAttempts in 1..5)
        require(retryDelayMillis >= 0L)
        require(maxFrameBytes in 1..64 * 1024)
    }
}

fun interface RetryDelay {
    suspend fun await(delayMillis: Long)
}

object CoroutineRetryDelay : RetryDelay {
    override suspend fun await(delayMillis: Long) {
        delay(delayMillis)
    }
}

class RemoteResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    override fun toString(): String =
        "RemoteResponse(statusCode=$statusCode, headers=${headers.keys}, body=[redacted])"
}

sealed interface RemoteCallResult {
    data class Success(val response: RemoteResponse) : RemoteCallResult

    data class HttpFailure(val response: RemoteResponse) : RemoteCallResult

    data object Offline : RemoteCallResult

    data object MissingAccessToken : RemoteCallResult

    data object AccessTokenTemporarilyUnavailable : RemoteCallResult

    data object TimedOut : RemoteCallResult

    data class NetworkFailure(val errorClass: String) : RemoteCallResult
}

sealed interface OnlineSessionOpenResult {
    data class Opened(val session: OnlineSession) : OnlineSessionOpenResult

    data object Offline : OnlineSessionOpenResult

    data object MissingAccessToken : OnlineSessionOpenResult

    data object AccessTokenTemporarilyUnavailable : OnlineSessionOpenResult

    data object Unauthorized : OnlineSessionOpenResult

    data object TimedOut : OnlineSessionOpenResult

    data class NetworkFailure(val errorClass: String) : OnlineSessionOpenResult
}

fun createOnlineHttpClient(
    policy: KtorOnlineTransportPolicy = KtorOnlineTransportPolicy(),
): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = policy.connectTimeoutMillis
        requestTimeoutMillis = policy.requestTimeoutMillis
        socketTimeoutMillis = policy.socketTimeoutMillis
    }
    install(WebSockets)
    engine {
        config {
            followRedirects(false)
        }
    }
}

class KtorOnlineTransport(
    private val client: HttpClient,
    private val endpoint: OnlineEndpoint,
    private val tokenProvider: AccessTokenProvider,
    private val connectivity: ConnectivityGate = AlwaysOnlineConnectivity,
    private val policy: KtorOnlineTransportPolicy = KtorOnlineTransportPolicy(),
    private val retryDelay: RetryDelay = CoroutineRetryDelay,
    private val logger: InplaceXLogger = InplaceXLogger(),
) : TransportBoundary {
    private val tokens = SingleFlightAccessTokenProvider(tokenProvider)

    override suspend fun execute(request: RemoteRequestSpec): RemoteCallResult {
        if (!connectivity.isOnline()) return RemoteCallResult.Offline

        var token = if (request.requiresAuthentication) {
            try {
                tokens.currentAccessToken() ?: return RemoteCallResult.MissingAccessToken
            } catch (_: AccessTokenTemporarilyUnavailableException) {
                return RemoteCallResult.AccessTokenTemporarilyUnavailable
            }
        } else {
            null
        }
        var refreshAttempted = false
        var attempt = 1

        while (attempt <= policy.maxAttempts) {
            try {
                val response = client.request {
                    method = request.method.toKtorMethod()
                    url(endpoint.normalizedBaseUrl + request.path)
                    request.queryParameters.forEach { (name, value) -> parameter(name, value) }
                    request.headers.forEach { (name, value) -> header(name, value) }
                    request.idempotencyKey?.let { header("Idempotency-Key", it) }
                    token?.let { header(HttpHeaders.Authorization, it.authorizationHeader()) }
                    request.bodyJson?.let {
                        contentType(ContentType.Application.Json)
                        setBody(it)
                    }
                }.toRemoteResponse()

                if (
                    response.statusCode == HttpStatusCode.Unauthorized.value &&
                    request.requiresAuthentication &&
                    !refreshAttempted
                ) {
                    refreshAttempted = true
                    token = try {
                        tokens.refreshAccessToken(requireNotNull(token))
                            ?: return RemoteCallResult.MissingAccessToken
                    } catch (_: AccessTokenTemporarilyUnavailableException) {
                        return RemoteCallResult.AccessTokenTemporarilyUnavailable
                    }
                    continue
                }

                if (response.statusCode in 200..299) {
                    logOutcome(request.operation, "success", attempt)
                    return RemoteCallResult.Success(response)
                }

                if (request.isRetryable && response.statusCode.isRetryableStatus() && attempt < policy.maxAttempts) {
                    logOutcome(request.operation, "retry_http", attempt)
                    retryDelay.await(policy.retryDelayMillis * attempt)
                    attempt += 1
                    continue
                }

                logOutcome(request.operation, "http_failure", attempt)
                return RemoteCallResult.HttpFailure(response)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (timeout: HttpRequestTimeoutException) {
                if (request.isRetryable && attempt < policy.maxAttempts) {
                    logOutcome(request.operation, "retry_timeout", attempt)
                    retryDelay.await(policy.retryDelayMillis * attempt)
                    attempt += 1
                    continue
                }
                logOutcome(request.operation, "timeout", attempt)
                return RemoteCallResult.TimedOut
            } catch (network: IOException) {
                if (request.isRetryable && attempt < policy.maxAttempts) {
                    logOutcome(request.operation, "retry_network", attempt)
                    retryDelay.await(policy.retryDelayMillis * attempt)
                    attempt += 1
                    continue
                }
                logOutcome(request.operation, "network_failure", attempt)
                return RemoteCallResult.NetworkFailure(network.javaClass.name)
            }
        }

        return RemoteCallResult.NetworkFailure("retry_exhausted")
    }

    override suspend fun openSession(request: RemoteWebSocketSpec): OnlineSessionOpenResult {
        if (!connectivity.isOnline()) return OnlineSessionOpenResult.Offline

        var token = try {
            tokens.currentAccessToken()
                ?: return OnlineSessionOpenResult.MissingAccessToken
        } catch (_: AccessTokenTemporarilyUnavailableException) {
            return OnlineSessionOpenResult.AccessTokenTemporarilyUnavailable
        }
        var refreshAttempted = false
        var attempt = 1

        while (attempt <= policy.maxAttempts) {
            try {
                val session = client.webSocketSession {
                    url(endpoint.normalizedBaseUrl + request.path)
                    request.queryParameters.forEach { (name, value) -> parameter(name, value) }
                    header(HttpHeaders.Authorization, token.authorizationHeader())
                    header(HttpHeaders.SecWebSocketProtocol, request.subprotocol)
                }
                logOutcome(request.operation, "connected", attempt)
                return OnlineSessionOpenResult.Opened(
                    KtorOnlineSession(
                        sessionId = request.sessionId,
                        session = session,
                        maxFrameBytes = policy.maxFrameBytes,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (response: ResponseException) {
                if (response.response.status == HttpStatusCode.Unauthorized && !refreshAttempted) {
                    refreshAttempted = true
                    token = try {
                        tokens.refreshAccessToken(token)
                            ?: return OnlineSessionOpenResult.MissingAccessToken
                    } catch (_: AccessTokenTemporarilyUnavailableException) {
                        return OnlineSessionOpenResult.AccessTokenTemporarilyUnavailable
                    }
                    continue
                }
                if (response.response.status == HttpStatusCode.Unauthorized) {
                    return OnlineSessionOpenResult.Unauthorized
                }
                if (response.response.status.value.isRetryableStatus() && attempt < policy.maxAttempts) {
                    retryDelay.await(policy.retryDelayMillis * attempt)
                    attempt += 1
                    continue
                }
                return OnlineSessionOpenResult.NetworkFailure(response.javaClass.name)
            } catch (timeout: HttpRequestTimeoutException) {
                if (attempt < policy.maxAttempts) {
                    retryDelay.await(policy.retryDelayMillis * attempt)
                    attempt += 1
                    continue
                }
                return OnlineSessionOpenResult.TimedOut
            } catch (network: IOException) {
                if (attempt < policy.maxAttempts) {
                    retryDelay.await(policy.retryDelayMillis * attempt)
                    attempt += 1
                    continue
                }
                return OnlineSessionOpenResult.NetworkFailure(network.javaClass.name)
            }
        }

        return OnlineSessionOpenResult.NetworkFailure("retry_exhausted")
    }

    private fun logOutcome(
        operation: String,
        outcome: String,
        attempt: Int,
    ) {
        logger.info(
            tag = "OnlineTransport",
            message = "online transport operation completed",
            attributes = mapOf(
                "operation" to operation,
                "outcome" to outcome,
                "attempt" to attempt.toString(),
            ),
        )
    }
}

private class KtorOnlineSession(
    override val sessionId: String,
    private val session: DefaultClientWebSocketSession,
    private val maxFrameBytes: Int,
) : OnlineSession {
    override val isConnected: Boolean
        get() = session.isActive

    override suspend fun send(frame: String) {
        require(frame.toByteArray(Charsets.UTF_8).size <= maxFrameBytes) {
            "WebSocket frame exceeds the configured limit"
        }
        session.send(Frame.Text(frame))
    }

    override suspend fun receive(): String? {
        val frame = session.incoming.receiveCatching().getOrNull() ?: return null
        return when (frame) {
            is Frame.Text -> {
                if (frame.data.size > maxFrameBytes) {
                    session.close(CloseReason(CloseReason.Codes.TOO_BIG, "frame_too_large"))
                    null
                } else {
                    frame.readText()
                }
            }
            is Frame.Close -> null
            else -> null
        }
    }

    override suspend fun close() {
        session.close(CloseReason(CloseReason.Codes.NORMAL, "client_close"))
    }
}

private fun RemoteHttpMethod.toKtorMethod(): HttpMethod = when (this) {
    RemoteHttpMethod.GET -> HttpMethod.Get
    RemoteHttpMethod.POST -> HttpMethod.Post
    RemoteHttpMethod.PUT -> HttpMethod.Put
    RemoteHttpMethod.DELETE -> HttpMethod.Delete
}

private suspend fun io.ktor.client.statement.HttpResponse.toRemoteResponse(): RemoteResponse =
    RemoteResponse(
        statusCode = status.value,
        headers = headers.entries()
            .filterNot { (name, _) ->
                name.equals(HttpHeaders.SetCookie, ignoreCase = true) ||
                    name.equals(HttpHeaders.Authorization, ignoreCase = true)
            }
            .associate { (name, values) -> name to values.toList() },
        body = bodyAsText(),
    )

private fun Int.isRetryableStatus(): Boolean =
    this == HttpStatusCode.RequestTimeout.value ||
        this == HttpStatusCode.TooManyRequests.value ||
        this in 500..599
