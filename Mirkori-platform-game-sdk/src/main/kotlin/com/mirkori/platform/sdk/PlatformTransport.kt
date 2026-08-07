package com.mirkori.platform.sdk

enum class PlatformHttpMethod {
    POST,
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
) {
    init {
        require(status in 100..599)
    }

    override fun toString(): String = "PlatformHttpResponse(status=$status, [redacted])"
}

fun interface PlatformTransport {
    suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse
}
