package com.mirkori.inplacex.platform.ads

import com.mirkori.inplacex.ads.AdMarket
import com.mirkori.inplacex.platform.logging.AppLog
import com.mirkori.inplacex.platform.online.AccessToken
import com.mirkori.inplacex.platform.online.AccessTokenProvider
import com.mirkori.inplacex.platform.online.KtorOnlineTransport
import com.mirkori.inplacex.platform.online.OnlineEndpoint
import com.mirkori.inplacex.platform.online.RemoteCallResult
import com.mirkori.inplacex.platform.online.RemoteHttpMethod
import com.mirkori.inplacex.platform.online.RemoteRequestSpec
import com.mirkori.inplacex.platform.online.TransportBoundary
import com.mirkori.inplacex.platform.online.createOnlineHttpClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class BackendAdMarketResolver(
    private val transport: TransportBoundary,
    private val nowMillis: () -> Long,
    private val cacheDurationMillis: Long = DefaultCacheDurationMillis,
    private val closeAction: () -> Unit = {},
) : AdMarketResolver, AutoCloseable {
    private val mutex = Mutex()
    private var cachedMarket: CachedMarket? = null

    init {
        require(cacheDurationMillis >= 0)
    }

    override suspend fun resolveMarket(): AdMarket = mutex.withLock {
        val now = nowMillis()
        cachedMarket
            ?.takeIf { now - it.resolvedAtMillis in 0 until cacheDurationMillis }
            ?.let { return@withLock it.market }

        val resolved = when (
            val result = transport.execute(
                RemoteRequestSpec(
                    operation = "ads.market.resolve",
                    method = RemoteHttpMethod.GET,
                    path = "/api/v1/runtime/ad-market",
                    requiresAuthentication = false,
                ),
            )
        ) {
            is RemoteCallResult.Success -> runCatching {
                AdMarketResponseCodec.decode(result.response.body)
            }.getOrElse {
                AppLog.warn(
                    tag = "AdMarket",
                    message = "Ad market response rejected",
                    attributes = mapOf("failure" to "protocol"),
                )
                AdMarket.UNKNOWN
            }
            is RemoteCallResult.HttpFailure,
            RemoteCallResult.Offline,
            RemoteCallResult.MissingAccessToken,
            RemoteCallResult.TimedOut,
            is RemoteCallResult.NetworkFailure,
            -> AdMarket.UNKNOWN
        }
        cachedMarket = resolved
            .takeIf { it != AdMarket.UNKNOWN }
            ?.let { CachedMarket(it, now) }
        resolved
    }

    override fun close() {
        closeAction()
    }

    private data class CachedMarket(
        val market: AdMarket,
        val resolvedAtMillis: Long,
    )

    companion object {
        const val DefaultCacheDurationMillis = 5 * 60 * 1_000L
    }
}

fun createBackendAdMarketResolverOrUnknown(
    baseUrl: String,
    allowCleartextLoopback: Boolean,
    nowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
): AdMarketResolver {
    if (baseUrl.isBlank()) return UnknownAdMarketResolver
    val endpoint = runCatching {
        OnlineEndpoint(baseUrl, allowCleartextLoopback)
    }.getOrElse {
        AppLog.warn(
            tag = "AdMarket",
            message = "Ad market endpoint configuration rejected",
            attributes = mapOf("failure" to "invalid_endpoint"),
        )
        return UnknownAdMarketResolver
    }
    val client = createOnlineHttpClient()
    return BackendAdMarketResolver(
        transport = KtorOnlineTransport(
            client = client,
            endpoint = endpoint,
            tokenProvider = NoAdMarketAccessToken,
        ),
        nowMillis = nowMillis,
        closeAction = client::close,
    )
}

internal object AdMarketResponseCodec {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    fun decode(source: String): AdMarket {
        require(source.toByteArray(Charsets.UTF_8).size <= MaximumResponseBytes)
        val response = json.parseToJsonElement(source) as? JsonObject
            ?: throw IllegalArgumentException("ad market response must be an object")
        require(response.keys == setOf(MarketField))
        val value = response[MarketField] as? JsonPrimitive
            ?: throw IllegalArgumentException("ad market is required")
        require(value.isString)
        return AdMarket.valueOf(value.content)
    }

    private const val MarketField = "market"
    private const val MaximumResponseBytes = 128
}

private object NoAdMarketAccessToken : AccessTokenProvider {
    override suspend fun currentAccessToken(): AccessToken? = null

    override suspend fun refreshAccessToken(rejectedToken: AccessToken): AccessToken? = null
}
