package com.mirkori.platform.sdk

import java.time.Instant

enum class PlatformHttpMethod {
    GET,
    POST,
    PUT,
}

class PlatformHttpRequest(
    val method: PlatformHttpMethod,
    val url: String,
    val headers: Map<String, String>,
    val body: String,
) {
    override fun toString(): String = "PlatformHttpRequest(method=$method, url=$url, [redacted])"
}

class PlatformHttpResponse(
    val status: Int,
    val body: String,
    val serverTime: Instant? = null,
) {
    init {
        require(status in 100..599)
    }

    override fun toString(): String = "PlatformHttpResponse(status=$status, [redacted])"
}

enum class PlatformTransportFailure(val retryable: Boolean) {
    NETWORK_UNAVAILABLE(true),
    TIMEOUT(true),
    TLS_REJECTED(false),
    CANCELLED(false),
    INVALID_RESPONSE(false),
}

class PlatformTransportException(
    val failure: PlatformTransportFailure,
) : IllegalStateException("Platform transport failed: ${failure.name.lowercase()}")

fun interface PlatformTransport {
    suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse
}
