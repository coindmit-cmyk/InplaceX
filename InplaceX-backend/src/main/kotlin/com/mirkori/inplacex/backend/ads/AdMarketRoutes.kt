package com.mirkori.inplacex.backend.ads

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

enum class PublicAdMarket {
    RUSSIA,
    GLOBAL,
    UNKNOWN,
}

data class AdMarketRuntimeConfig(
    val trustedCountryHeader: String,
    val trustedProxyHosts: Set<String>,
) {
    init {
        require(trustedCountryHeader.matches(SafeHeaderName)) {
            "Ad market country header has an invalid format"
        }
        require(trustedProxyHosts.isNotEmpty()) {
            "At least one trusted ad market proxy host is required"
        }
        require(trustedProxyHosts.all { it.length in 1..MaximumProxyHostLength && it.none(Char::isISOControl) }) {
            "Ad market trusted proxy host has an invalid format"
        }
    }

    companion object {
        fun fromEnvironmentOrNull(
            environment: Map<String, String>,
        ): AdMarketRuntimeConfig? {
            val header = environment[CountryHeaderEnvironmentKey]?.trim().orEmpty()
            val proxyHosts = environment[TrustedProxyHostsEnvironmentKey]
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.toSet()
                .orEmpty()

            if (header.isEmpty() && proxyHosts.isEmpty()) return null
            require(header.isNotEmpty() && proxyHosts.isNotEmpty()) {
                "Ad market country header and trusted proxy hosts must be configured together"
            }
            return AdMarketRuntimeConfig(
                trustedCountryHeader = header,
                trustedProxyHosts = proxyHosts,
            )
        }

        const val CountryHeaderEnvironmentKey = "INPLACEX_AD_MARKET_COUNTRY_HEADER"
        const val TrustedProxyHostsEnvironmentKey = "INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS"
        private val SafeHeaderName = Regex("[A-Za-z0-9-]{1,64}")
        private const val MaximumProxyHostLength = 255
    }
}

fun interface AdMarketResolver {
    fun resolve(
        remoteHost: String,
        countryCode: String?,
    ): PublicAdMarket
}

object UnknownAdMarketResolver : AdMarketResolver {
    override fun resolve(
        remoteHost: String,
        countryCode: String?,
    ): PublicAdMarket = PublicAdMarket.UNKNOWN
}

class TrustedProxyAdMarketResolver(
    private val config: AdMarketRuntimeConfig,
) : AdMarketResolver {
    override fun resolve(
        remoteHost: String,
        countryCode: String?,
    ): PublicAdMarket {
        if (remoteHost !in config.trustedProxyHosts) return PublicAdMarket.UNKNOWN
        val normalizedCountry = countryCode
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.matches(IsoCountryCode) }
            ?: return PublicAdMarket.UNKNOWN
        return if (normalizedCountry == RussiaCountryCode) {
            PublicAdMarket.RUSSIA
        } else {
            PublicAdMarket.GLOBAL
        }
    }

    private companion object {
        val IsoCountryCode = Regex("[A-Z]{2}")
        const val RussiaCountryCode = "RU"
    }
}

fun Application.configureAdMarketRoutes(
    config: AdMarketRuntimeConfig?,
    resolver: AdMarketResolver = config
        ?.let(::TrustedProxyAdMarketResolver)
        ?: UnknownAdMarketResolver,
) {
    routing {
        get("/api/v1/runtime/ad-market") {
            val market = resolver.resolve(
                remoteHost = call.request.local.remoteHost,
                countryCode = config?.let { call.request.header(it.trustedCountryHeader) },
            )
            call.response.cacheControl(CacheControl.NoStore(null))
            call.respondText(
                text = """{"market":"${market.name}"}""",
                contentType = ContentType.Application.Json,
            )
        }
    }
}
