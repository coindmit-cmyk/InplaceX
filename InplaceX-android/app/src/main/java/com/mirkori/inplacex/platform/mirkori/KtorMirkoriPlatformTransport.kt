package com.mirkori.inplacex.platform.mirkori

import com.mirkori.inplacex.platform.online.ConnectivityGate
import com.mirkori.platform.sdk.PlatformHttpMethod
import com.mirkori.platform.sdk.PlatformHttpRequest
import com.mirkori.platform.sdk.PlatformHttpResponse
import com.mirkori.platform.sdk.PlatformTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

data class MirkoriTransportPolicy(
    val connectTimeoutMillis: Long = 10_000L,
    val requestTimeoutMillis: Long = 15_000L,
    val socketTimeoutMillis: Long = 20_000L,
    val maxAttempts: Int = 2,
    val retryDelayMillis: Long = 250L,
) {
    init {
        require(connectTimeoutMillis > 0)
        require(requestTimeoutMillis > 0)
        require(socketTimeoutMillis > 0)
        require(maxAttempts in 1..3)
        require(retryDelayMillis >= 0)
    }
}

enum class MirkoriTransportFailure {
    OFFLINE,
    NETWORK,
    TIMEOUT,
    RESPONSE_TOO_LARGE,
    RETRY_EXHAUSTED,
}

class MirkoriTransportException(
    val failure: MirkoriTransportFailure,
) : IOException("Mirkori platform transport failed: ${failure.name.lowercase()}")

fun createMirkoriHttpClient(policy: MirkoriTransportPolicy = MirkoriTransportPolicy()): HttpClient =
    HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = policy.connectTimeoutMillis
            requestTimeoutMillis = policy.requestTimeoutMillis
            socketTimeoutMillis = policy.socketTimeoutMillis
        }
        engine {
            config { followRedirects(false) }
        }
    }

class KtorMirkoriPlatformTransport(
    private val client: HttpClient,
    private val connectivity: ConnectivityGate,
    private val policy: MirkoriTransportPolicy = MirkoriTransportPolicy(),
) : PlatformTransport {
    override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse {
        if (!connectivity.isOnline()) throw MirkoriTransportException(MirkoriTransportFailure.OFFLINE)
        var attempt = 1
        while (attempt <= policy.maxAttempts) {
            try {
                val response = client.request {
                    method = when (request.method) {
                        PlatformHttpMethod.GET -> HttpMethod.Get
                        PlatformHttpMethod.POST -> HttpMethod.Post
                        PlatformHttpMethod.PUT -> HttpMethod.Put
                    }
                    url(request.url)
                    request.headers.forEach { (name, value) -> header(name, value) }
                    if (request.method != PlatformHttpMethod.GET) {
                        setBody(request.body)
                    }
                }
                val body = response.readBoundedBody()
                return PlatformHttpResponse(
                    status = response.status.value,
                    body = body,
                    serverTime = response.headers[HttpHeaders.Date]
                        ?.takeIf { URI(request.url).scheme.equals("https", ignoreCase = true) }
                        ?.toServerInstantOrNull(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (timeout: HttpRequestTimeoutException) {
                if (attempt >= policy.maxAttempts) {
                    throw MirkoriTransportException(MirkoriTransportFailure.TIMEOUT)
                }
            } catch (network: IOException) {
                if (network is MirkoriTransportException || attempt >= policy.maxAttempts) {
                    throw if (network is MirkoriTransportException) {
                        network
                    } else {
                        MirkoriTransportException(MirkoriTransportFailure.NETWORK)
                    }
                }
            }
            delay(policy.retryDelayMillis * attempt)
            attempt += 1
        }
        throw MirkoriTransportException(MirkoriTransportFailure.RETRY_EXHAUSTED)
    }
}

private suspend fun io.ktor.client.statement.HttpResponse.readBoundedBody(): String {
    val channel = bodyAsChannel()
    val buffer = ByteArray(8 * 1024)
    val output = ByteArrayOutputStream()
    var total = 0
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        if (read == 0) continue
        if (read > MaximumMirkoriResponseBytes - total) {
            val error = MirkoriTransportException(MirkoriTransportFailure.RESPONSE_TOO_LARGE)
            channel.cancel(error)
            throw error
        }
        output.write(buffer, 0, read)
        total += read
    }
    return output.toString(Charsets.UTF_8.name())
}

private fun String.toServerInstantOrNull(): Instant? = runCatching {
    ZonedDateTime.parse(this, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
}.getOrNull()

private const val MaximumMirkoriResponseBytes = 64 * 1024
