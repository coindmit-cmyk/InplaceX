package com.mirkori.inplacex.backend.ads

import com.maxmind.db.MaxMindDbConstructor
import com.maxmind.db.MaxMindDbParameter
import com.maxmind.db.Reader
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.file.Path
import kotlin.io.path.isRegularFile

enum class PublicAdMarket {
    RUSSIA,
    GLOBAL,
    UNKNOWN,
}

enum class AdMarketSource {
    TRUSTED_COUNTRY_HEADER,
    LOCAL_IP_DATABASE,
}

data class AdMarketRuntimeConfig(
    val source: AdMarketSource,
    val trustedProxyHosts: Set<String>,
    val trustedHeader: String,
    val databasePath: Path? = null,
) {
    init {
        require(trustedHeader.matches(SafeHeaderName)) {
            "Ad market trusted header has an invalid format"
        }
        require(trustedProxyHosts.all { it.length in 1..MaximumProxyHostLength && it.none(Char::isISOControl) }) {
            "Ad market trusted proxy host has an invalid format"
        }
        when (source) {
            AdMarketSource.TRUSTED_COUNTRY_HEADER -> {
                require(trustedProxyHosts.isNotEmpty()) {
                    "At least one trusted ad market proxy host is required for a country header"
                }
                require(databasePath == null) {
                    "Country-header ad market source cannot use a local IP database"
                }
            }
            AdMarketSource.LOCAL_IP_DATABASE -> {
                require(databasePath != null) {
                    "Local IP database path is required for database ad market source"
                }
            }
        }
    }

    val trustedCountryHeader: String?
        get() = trustedHeader.takeIf { source == AdMarketSource.TRUSTED_COUNTRY_HEADER }

    val trustedClientIpHeader: String?
        get() = trustedHeader.takeIf { source == AdMarketSource.LOCAL_IP_DATABASE }

    companion object {
        fun fromEnvironmentOrNull(
            environment: Map<String, String>,
        ): AdMarketRuntimeConfig? {
            val required = environment[RequiredEnvironmentKey]
                ?.trim()
                ?.lowercase()
                ?.let { raw ->
                    require(raw == "true" || raw == "false") {
                        "$RequiredEnvironmentKey must be true or false"
                    }
                    raw.toBoolean()
                }
                ?: false
            val countryHeader = environment[CountryHeaderEnvironmentKey]?.trim().orEmpty()
            val databasePath = environment[DatabasePathEnvironmentKey]?.trim().orEmpty()
            val clientIpHeader = environment[ClientIpHeaderEnvironmentKey]
                ?.trim()
                ?.ifEmpty { null }
                ?: DefaultClientIpHeader
            val proxyHosts = environment[TrustedProxyHostsEnvironmentKey]
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.toSet()
                .orEmpty()

            require(countryHeader.isEmpty() || databasePath.isEmpty()) {
                "Configure either a trusted country header or a local IP database, not both"
            }
            if (countryHeader.isNotEmpty()) {
                return AdMarketRuntimeConfig(
                    source = AdMarketSource.TRUSTED_COUNTRY_HEADER,
                    trustedProxyHosts = proxyHosts,
                    trustedHeader = countryHeader,
                )
            }
            if (databasePath.isNotEmpty()) {
                return AdMarketRuntimeConfig(
                    source = AdMarketSource.LOCAL_IP_DATABASE,
                    trustedProxyHosts = proxyHosts,
                    trustedHeader = clientIpHeader,
                    databasePath = Path.of(databasePath),
                )
            }
            require(proxyHosts.isEmpty() && environment[ClientIpHeaderEnvironmentKey].isNullOrBlank()) {
                "Ad market proxy settings require a country header or local IP database"
            }
            require(!required) {
                "Ad market configuration is required but no source is configured"
            }
            return null
        }

        const val RequiredEnvironmentKey = "INPLACEX_AD_MARKET_REQUIRED"
        const val CountryHeaderEnvironmentKey = "INPLACEX_AD_MARKET_COUNTRY_HEADER"
        const val DatabasePathEnvironmentKey = "INPLACEX_AD_MARKET_DB_PATH"
        const val ClientIpHeaderEnvironmentKey = "INPLACEX_AD_MARKET_CLIENT_IP_HEADER"
        const val TrustedProxyHostsEnvironmentKey = "INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS"
        const val DefaultClientIpHeader = "X-InplaceX-Client-IP"
        private val SafeHeaderName = Regex("[A-Za-z0-9-]{1,64}")
        private const val MaximumProxyHostLength = 255
    }
}

fun interface AdMarketResolver {
    fun resolve(
        remoteAddress: String,
        trustedHeaderValue: String?,
    ): PublicAdMarket
}

object UnknownAdMarketResolver : AdMarketResolver {
    override fun resolve(
        remoteAddress: String,
        trustedHeaderValue: String?,
    ): PublicAdMarket = PublicAdMarket.UNKNOWN
}

class TrustedProxyAdMarketResolver(
    private val config: AdMarketRuntimeConfig,
) : AdMarketResolver {
    init {
        require(config.source == AdMarketSource.TRUSTED_COUNTRY_HEADER)
    }

    override fun resolve(
        remoteAddress: String,
        trustedHeaderValue: String?,
    ): PublicAdMarket {
        if (remoteAddress !in config.trustedProxyHosts) return PublicAdMarket.UNKNOWN
        return marketForCountryCode(trustedHeaderValue)
    }
}

fun interface CountryByIpLookup {
    fun countryCode(address: InetAddress): String?
}

class MmdbCountryByIpLookup(
    databasePath: Path,
) : CountryByIpLookup, AutoCloseable {
    private val reader: Reader

    init {
        require(databasePath.isRegularFile()) {
            "Ad market IP database must be an existing regular file"
        }
        reader = Reader(databasePath.toFile())
    }

    override fun countryCode(address: InetAddress): String? =
        reader.get(address, DbIpRecord::class.java)
            ?.country
            ?.isoCode

    override fun close() {
        reader.close()
    }

    data class DbIpRecord @MaxMindDbConstructor constructor(
        @param:MaxMindDbParameter(name = "country")
        val country: DbIpCountry?,
    )

    data class DbIpCountry @MaxMindDbConstructor constructor(
        @param:MaxMindDbParameter(name = "iso_code")
        val isoCode: String?,
    )
}

class LocalDatabaseAdMarketResolver(
    private val config: AdMarketRuntimeConfig,
    private val lookup: CountryByIpLookup = MmdbCountryByIpLookup(
        requireNotNull(config.databasePath),
    ),
) : AdMarketResolver, AutoCloseable {
    init {
        require(config.source == AdMarketSource.LOCAL_IP_DATABASE)
    }

    override fun resolve(
        remoteAddress: String,
        trustedHeaderValue: String?,
    ): PublicAdMarket {
        val rawAddress = if (remoteAddress in config.trustedProxyHosts) {
            trustedHeaderValue
        } else {
            remoteAddress
        } ?: return PublicAdMarket.UNKNOWN
        val address = parseNumericAddress(rawAddress) ?: return PublicAdMarket.UNKNOWN
        return marketForCountryCode(
            runCatching { lookup.countryCode(address) }.getOrNull(),
        )
    }

    override fun close() {
        (lookup as? AutoCloseable)?.close()
    }
}

fun Application.configureAdMarketRoutes(
    config: AdMarketRuntimeConfig?,
    resolver: AdMarketResolver = when (config?.source) {
        AdMarketSource.TRUSTED_COUNTRY_HEADER -> TrustedProxyAdMarketResolver(config)
        AdMarketSource.LOCAL_IP_DATABASE -> LocalDatabaseAdMarketResolver(config)
        null -> UnknownAdMarketResolver
    },
) {
    (resolver as? AutoCloseable)?.let { resource ->
        environment.monitor.subscribe(ApplicationStopped) {
            resource.close()
        }
    }
    routing {
        get("/api/v1/runtime/ad-market") {
            val market = resolver.resolve(
                remoteAddress = call.request.local.remoteAddress,
                trustedHeaderValue = config?.let { call.request.header(it.trustedHeader) },
            )
            call.response.cacheControl(CacheControl.NoStore(null))
            if (config?.source == AdMarketSource.LOCAL_IP_DATABASE) {
                call.response.headers.append(
                    HttpHeaders.Link,
                    """<https://db-ip.com>; rel="via"""",
                )
            }
            call.respondText(
                text = """{"market":"${market.name}"}""",
                contentType = ContentType.Application.Json,
            )
        }
    }
}

private fun marketForCountryCode(countryCode: String?): PublicAdMarket {
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

private fun parseNumericAddress(rawAddress: String): InetAddress? {
    val value = rawAddress.trim()
    if (value.isEmpty() || value.length > MaximumIpAddressLength) return null
    if (value.contains(':')) {
        if (!value.matches(Ipv6Characters)) return null
        return runCatching { InetAddress.getByName(value) }
            .getOrNull()
            ?.takeIf { it is Inet6Address }
    }
    val octets = value.split('.')
    if (octets.size != 4) return null
    val bytes = octets.map { octet ->
        if (octet.isEmpty() || octet.length > 3 || octet.any { !it.isDigit() }) return null
        octet.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
    }
    return InetAddress.getByAddress(bytes.map(Int::toByte).toByteArray())
}

private val IsoCountryCode = Regex("[A-Z]{2}")
private val Ipv6Characters = Regex("[0-9A-Fa-f:.]+")
private const val RussiaCountryCode = "RU"
private const val MaximumIpAddressLength = 45
