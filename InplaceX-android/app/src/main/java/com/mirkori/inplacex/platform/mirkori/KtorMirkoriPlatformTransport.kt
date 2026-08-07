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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import java.io.IOException
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

class MirkoriTransportException(errorClass: String) : IOException("Mirkori platform transport failed: $errorClass")

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
        if (!connectivity.isOnline()) throw MirkoriTransportException("offline")
        var attempt = 1
        while (attempt <= policy.maxAttempts) {
            try {
                val response = client.request {
                    method = when (request.method) {
                        PlatformHttpMethod.POST -> HttpMethod.Post
                    }
                    url(request.url)
                    request.headers.forEach { (name, value) -> header(name, value) }
                    setBody(request.body)
                }
                val body = response.bodyAsText()
                if (body.toByteArray(Charsets.UTF_8).size > MaximumResponseBytes) {
                    throw MirkoriTransportException("response_too_large")
                }
                return PlatformHttpResponse(response.status.value, body)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (timeout: HttpRequestTimeoutException) {
                if (attempt >= policy.maxAttempts) throw MirkoriTransportException(timeout.javaClass.name)
            } catch (network: IOException) {
                if (network is MirkoriTransportException || attempt >= policy.maxAttempts) {
                    throw MirkoriTransportException(network.javaClass.name)
                }
            }
            delay(policy.retryDelayMillis * attempt)
            attempt += 1
        }
        throw MirkoriTransportException("retry_exhausted")
    }

    private companion object {
        const val MaximumResponseBytes = 64 * 1024
    }
}
