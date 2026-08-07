package com.mirkori.inplacex.backend.app

import com.mirkori.inplacex.backend.ads.AdMarketSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

class BackendRuntimeConfigTest {
    @Test
    fun `configuration uses supported environment variables without requiring secrets`() {
        val config = BackendRuntimeConfig.fromEnvironment(
            mapOf(
                "INPLACEX_BACKEND_HOST" to "127.0.0.1",
                "INPLACEX_BACKEND_PORT" to "9080",
                "INPLACEX_BACKEND_ENVIRONMENT" to "test",
                "DATABASE_PASSWORD" to "must-not-be-read",
            ),
        )

        assertEquals("127.0.0.1", config.host)
        assertEquals(9080, config.port)
        assertEquals("test", config.environment)
    }

    @Test
    fun `configuration supports platform port and safe defaults`() {
        val platformConfig = BackendRuntimeConfig.fromEnvironment(mapOf("PORT" to "9090"))
        val defaults = BackendRuntimeConfig.fromEnvironment(emptyMap())

        assertEquals(9090, platformConfig.port)
        assertEquals(BackendRuntimeConfig.DefaultHost, defaults.host)
        assertEquals(BackendRuntimeConfig.DefaultPort, defaults.port)
        assertEquals(BackendRuntimeConfig.DefaultEnvironment, defaults.environment)
    }

    @Test
    fun `database credentials are accepted only from the process environment and not rendered`() {
        val config = BackendRuntimeConfig.fromEnvironment(
            mapOf(
                DatabaseRuntimeConfig.JdbcUrlEnvironmentKey to "jdbc:postgresql://db/inplacex",
                DatabaseRuntimeConfig.UsernameEnvironmentKey to "inplacex",
                DatabaseRuntimeConfig.PasswordEnvironmentKey to "test-password",
            ),
        )

        assertNotNull(config.database)
        val database = requireNotNull(config.database)
        assertEquals("jdbc:postgresql://db/inplacex", database.jdbcUrl)
        assertEquals("inplacex", database.username)
        assertFalse(database.toString().contains("test-password"))
    }

    @Test
    fun `database password can be loaded only from a regular external file`() {
        val passwordFile = Files.createTempFile("inplacex-db-password-", ".txt")
        Files.writeString(passwordFile, "external-database-password\n")

        val config = BackendRuntimeConfig.fromEnvironment(
            mapOf(
                DatabaseRuntimeConfig.JdbcUrlEnvironmentKey to "jdbc:postgresql://db/inplacex",
                DatabaseRuntimeConfig.UsernameEnvironmentKey to "inplacex",
                DatabaseRuntimeConfig.PasswordPathEnvironmentKey to passwordFile.toString(),
            ),
        )

        assertFalse(requireNotNull(config.database).toString().contains("external-database-password"))
        assertThrows(IllegalArgumentException::class.java) {
            BackendRuntimeConfig.fromEnvironment(
                mapOf(
                    DatabaseRuntimeConfig.JdbcUrlEnvironmentKey to "jdbc:postgresql://db/inplacex",
                    DatabaseRuntimeConfig.UsernameEnvironmentKey to "inplacex",
                    DatabaseRuntimeConfig.PasswordEnvironmentKey to "inline-password",
                    DatabaseRuntimeConfig.PasswordPathEnvironmentKey to passwordFile.toString(),
                ),
            )
        }
    }

    @Test
    fun `database URL rejects embedded credentials query and userinfo`() {
        listOf(
            "jdbc:postgresql://db/inplacex?password=leaked",
            "jdbc:postgresql://user@db/inplacex",
            "jdbc:postgresql://db/inplacex#fragment",
        ).forEach { jdbcUrl ->
            assertThrows(IllegalArgumentException::class.java) {
                BackendRuntimeConfig.fromEnvironment(
                    mapOf(
                        DatabaseRuntimeConfig.JdbcUrlEnvironmentKey to jdbcUrl,
                        DatabaseRuntimeConfig.UsernameEnvironmentKey to "inplacex",
                        DatabaseRuntimeConfig.PasswordEnvironmentKey to "test-password",
                    ),
                )
            }
        }
    }

    @Test
    fun `legacy checksum baseline requires exact acknowledgement`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackendRuntimeConfig.fromEnvironment(
                mapOf(
                    DatabaseRuntimeConfig.JdbcUrlEnvironmentKey to "jdbc:postgresql://db/inplacex",
                    DatabaseRuntimeConfig.UsernameEnvironmentKey to "inplacex",
                    DatabaseRuntimeConfig.PasswordEnvironmentKey to "test-password",
                    DatabaseRuntimeConfig.LegacyChecksumBaselineAcknowledgementEnvironmentKey to "yes",
                ),
            )
        }
        val config = BackendRuntimeConfig.fromEnvironment(
            mapOf(
                DatabaseRuntimeConfig.JdbcUrlEnvironmentKey to "jdbc:postgresql://db/inplacex",
                DatabaseRuntimeConfig.UsernameEnvironmentKey to "inplacex",
                DatabaseRuntimeConfig.PasswordEnvironmentKey to "test-password",
                DatabaseRuntimeConfig.LegacyChecksumBaselineAcknowledgementEnvironmentKey to
                    DatabaseRuntimeConfig.LegacyChecksumBaselineAcknowledgement,
            ),
        )
        assertEquals(true, requireNotNull(config.database).acknowledgeLegacyChecksumBaseline)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `configuration rejects an invalid port`() {
        BackendRuntimeConfig.fromEnvironment(mapOf("INPLACEX_BACKEND_PORT" to "0"))
    }

    @Test
    fun `online game API accepts only public verification material`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val encodedPublicKey = Base64.getEncoder().encodeToString(keys.public.encoded)

        val config = BackendRuntimeConfig.fromEnvironment(
            mapOf(
                OnlineRuntimeConfig.IssuerKey to "mirkori-platform",
                OnlineRuntimeConfig.AudienceKey to "mirkori-games",
                OnlineRuntimeConfig.PublicKeyKey to encodedPublicKey,
            ),
        )

        assertEquals("RSA", requireNotNull(config.online).verificationKey.algorithm)
        assertEquals("inplacex", config.online?.gameId)
        assertEquals(Duration.ofSeconds(5), config.online?.botFallbackDelay)
        assertFalse(config.online.toString().contains(encodedPublicKey))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `online game API rejects RSA verification keys below 2048 bits`() {
        val weakKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        BackendRuntimeConfig.fromEnvironment(
            mapOf(
                OnlineRuntimeConfig.IssuerKey to "mirkori-platform",
                OnlineRuntimeConfig.AudienceKey to "mirkori-games",
                OnlineRuntimeConfig.PublicKeyKey to Base64.getEncoder().encodeToString(weakKeys.public.encoded),
            ),
        )
    }

    @Test
    fun `online game API accepts a bounded bot fallback delay`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val config = BackendRuntimeConfig.fromEnvironment(
            mapOf(
                OnlineRuntimeConfig.IssuerKey to "mirkori-platform",
                OnlineRuntimeConfig.AudienceKey to "mirkori-games",
                OnlineRuntimeConfig.PublicKeyKey to Base64.getEncoder().encodeToString(keys.public.encoded),
                OnlineRuntimeConfig.BotFallbackSecondsKey to "8",
            ),
        )

        assertEquals(Duration.ofSeconds(8), config.online?.botFallbackDelay)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `online game API rejects an unbounded bot fallback delay`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        BackendRuntimeConfig.fromEnvironment(
            mapOf(
                OnlineRuntimeConfig.IssuerKey to "mirkori-platform",
                OnlineRuntimeConfig.AudienceKey to "mirkori-games",
                OnlineRuntimeConfig.PublicKeyKey to Base64.getEncoder().encodeToString(keys.public.encoded),
                OnlineRuntimeConfig.BotFallbackSecondsKey to "600",
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partial online verification config fails closed`() {
        BackendRuntimeConfig.fromEnvironment(
            mapOf(OnlineRuntimeConfig.IssuerKey to "inplacex-identity"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `PostgreSQL online runtime requires durable state encryption key`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        BackendRuntimeConfig.fromEnvironment(
            mapOf(
                DatabaseRuntimeConfig.JdbcUrlEnvironmentKey to "jdbc:postgresql://db/inplacex",
                DatabaseRuntimeConfig.UsernameEnvironmentKey to "inplacex",
                DatabaseRuntimeConfig.PasswordEnvironmentKey to "test-password",
                OnlineRuntimeConfig.IssuerKey to "mirkori-platform",
                OnlineRuntimeConfig.AudienceKey to "mirkori-games",
                OnlineRuntimeConfig.PublicKeyKey to Base64.getEncoder().encodeToString(keys.public.encoded),
            ),
        )
    }

    @Test
    fun `PostgreSQL online runtime accepts a redacted 256 bit state key`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val encodedStateKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val config = BackendRuntimeConfig.fromEnvironment(
            mapOf(
                DatabaseRuntimeConfig.JdbcUrlEnvironmentKey to "jdbc:postgresql://db/inplacex",
                DatabaseRuntimeConfig.UsernameEnvironmentKey to "inplacex",
                DatabaseRuntimeConfig.PasswordEnvironmentKey to "test-password",
                OnlineRuntimeConfig.IssuerKey to "inplacex-identity",
                OnlineRuntimeConfig.AudienceKey to "inplacex-game-api",
                OnlineRuntimeConfig.PublicKeyKey to Base64.getEncoder().encodeToString(keys.public.encoded),
                OnlineRuntimeConfig.StateEncryptionKey to encodedStateKey,
            ),
        )

        assertNotNull(config.online?.stateEncryptionKey)
        assertFalse(config.toString().contains(encodedStateKey))
    }

    @Test
    fun `ad market accepts only a complete trusted proxy configuration`() {
        val config = BackendRuntimeConfig.fromEnvironment(
            mapOf(
                "INPLACEX_AD_MARKET_COUNTRY_HEADER" to "CF-IPCountry",
                "INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS" to "127.0.0.1, 10.0.0.5",
            ),
        )

        assertEquals(AdMarketSource.TRUSTED_COUNTRY_HEADER, config.adMarket?.source)
        assertEquals("CF-IPCountry", config.adMarket?.trustedCountryHeader)
        assertEquals(setOf("127.0.0.1", "10.0.0.5"), config.adMarket?.trustedProxyHosts)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partial ad market configuration fails closed`() {
        BackendRuntimeConfig.fromEnvironment(
            mapOf("INPLACEX_AD_MARKET_COUNTRY_HEADER" to "CF-IPCountry"),
        )
    }

    @Test
    fun `ad market accepts local IP database with a dedicated proxy header`() {
        val config = BackendRuntimeConfig.fromEnvironment(
            mapOf(
                "INPLACEX_AD_MARKET_REQUIRED" to "true",
                "INPLACEX_AD_MARKET_DB_PATH" to "/var/lib/inplacex/geoip/dbip-country-lite.mmdb",
                "INPLACEX_AD_MARKET_CLIENT_IP_HEADER" to "X-InplaceX-Client-IP",
                "INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS" to "127.0.0.1, ::1",
            ),
        )

        assertEquals(AdMarketSource.LOCAL_IP_DATABASE, config.adMarket?.source)
        assertEquals("X-InplaceX-Client-IP", config.adMarket?.trustedClientIpHeader)
        assertEquals(setOf("127.0.0.1", "::1"), config.adMarket?.trustedProxyHosts)
        assertEquals(
            Path.of("/var/lib/inplacex/geoip/dbip-country-lite.mmdb"),
            config.adMarket?.databasePath,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `required ad market rejects missing source`() {
        BackendRuntimeConfig.fromEnvironment(
            mapOf("INPLACEX_AD_MARKET_REQUIRED" to "true"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ad market rejects ambiguous country and database sources`() {
        BackendRuntimeConfig.fromEnvironment(
            mapOf(
                "INPLACEX_AD_MARKET_COUNTRY_HEADER" to "CF-IPCountry",
                "INPLACEX_AD_MARKET_DB_PATH" to "/tmp/country.mmdb",
                "INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS" to "127.0.0.1",
            ),
        )
    }

    @Test
    fun `production fails closed until every runtime capability is configured`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackendRuntimeConfig.fromEnvironment(
                mapOf("INPLACEX_BACKEND_ENVIRONMENT" to BackendRuntimeConfig.ProductionEnvironment),
            )
        }
    }

    @Test
    fun `production rejects inline database and online state secrets`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val passwordFile = Files.createTempFile("inplacex-prod-inline-check-", ".txt")
        Files.writeString(passwordFile, "production-database-password\n")
        val inlineStateKey = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val common = mapOf(
            "INPLACEX_BACKEND_ENVIRONMENT" to BackendRuntimeConfig.ProductionEnvironment,
            DatabaseRuntimeConfig.JdbcUrlEnvironmentKey to "jdbc:postgresql://postgres/inplacex",
            DatabaseRuntimeConfig.UsernameEnvironmentKey to "inplacex",
            OnlineRuntimeConfig.IssuerKey to "mirkori-platform",
            OnlineRuntimeConfig.AudienceKey to "mirkori-games",
            OnlineRuntimeConfig.PublicKeyKey to Base64.getEncoder().encodeToString(keys.public.encoded),
            BackendReleaseIdentity.ReleaseIdEnvironmentKey to "inplacex-backend-20260807-1",
            BackendReleaseIdentity.GitShaEnvironmentKey to "a".repeat(40),
            BackendReleaseIdentity.ImageDigestEnvironmentKey to "sha256:${"b".repeat(64)}",
            "INPLACEX_AD_MARKET_COUNTRY_HEADER" to "CF-IPCountry",
            "INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS" to "127.0.0.1",
        )

        assertThrows(IllegalArgumentException::class.java) {
            BackendRuntimeConfig.fromEnvironment(
                common + mapOf(
                    DatabaseRuntimeConfig.PasswordEnvironmentKey to "inline-database-password",
                    OnlineRuntimeConfig.StateEncryptionKey to inlineStateKey,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackendRuntimeConfig.fromEnvironment(
                common + mapOf(
                    DatabaseRuntimeConfig.PasswordPathEnvironmentKey to passwordFile.toString(),
                    OnlineRuntimeConfig.StateEncryptionKey to inlineStateKey,
                ),
            )
        }
    }

    @Test
    fun `production rejects an inline platform verification key`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val passwordFile = Files.createTempFile("inplacex-prod-db-", ".txt")
        val stateKeyFile = Files.createTempFile("inplacex-prod-state-", ".txt")
        Files.writeString(passwordFile, "production-database-password\n")
        Files.writeString(stateKeyFile, Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }) + "\n")

        assertThrows(IllegalArgumentException::class.java) {
            BackendRuntimeConfig.fromEnvironment(
                mapOf(
                    "INPLACEX_BACKEND_ENVIRONMENT" to BackendRuntimeConfig.ProductionEnvironment,
                    DatabaseRuntimeConfig.JdbcUrlEnvironmentKey to "jdbc:postgresql://postgres/inplacex",
                    DatabaseRuntimeConfig.UsernameEnvironmentKey to "inplacex",
                    DatabaseRuntimeConfig.PasswordPathEnvironmentKey to passwordFile.toString(),
                    OnlineRuntimeConfig.IssuerKey to "mirkori-platform",
                    OnlineRuntimeConfig.AudienceKey to "mirkori-games",
                    OnlineRuntimeConfig.PublicKeyKey to Base64.getEncoder().encodeToString(keys.public.encoded),
                    OnlineRuntimeConfig.StateEncryptionKeyPath to stateKeyFile.toString(),
                    BackendReleaseIdentity.ReleaseIdEnvironmentKey to "inplacex-backend-20260807-1",
                    BackendReleaseIdentity.GitShaEnvironmentKey to "a".repeat(40),
                    BackendReleaseIdentity.ImageDigestEnvironmentKey to "sha256:${"b".repeat(64)}",
                    "INPLACEX_AD_MARKET_COUNTRY_HEADER" to "CF-IPCountry",
                    "INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS" to "127.0.0.1",
                ),
            )
        }
    }

    @Test
    fun `production accepts external database online keys and explicit ad market`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val passwordFile = Files.createTempFile("inplacex-prod-db-", ".txt")
        val publicKeyFile = Files.createTempFile("inplacex-prod-public-", ".txt")
        val stateKeyFile = Files.createTempFile("inplacex-prod-state-", ".txt")
        Files.writeString(passwordFile, "production-database-password\n")
        Files.writeString(publicKeyFile, Base64.getEncoder().encodeToString(keys.public.encoded) + "\n")
        Files.writeString(stateKeyFile, Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }) + "\n")

        val config = BackendRuntimeConfig.fromEnvironment(
            mapOf(
                "INPLACEX_BACKEND_ENVIRONMENT" to BackendRuntimeConfig.ProductionEnvironment,
                DatabaseRuntimeConfig.JdbcUrlEnvironmentKey to "jdbc:postgresql://postgres/inplacex",
                DatabaseRuntimeConfig.UsernameEnvironmentKey to "inplacex",
                DatabaseRuntimeConfig.PasswordPathEnvironmentKey to passwordFile.toString(),
                OnlineRuntimeConfig.IssuerKey to "mirkori-platform",
                OnlineRuntimeConfig.AudienceKey to "mirkori-games",
                OnlineRuntimeConfig.PublicKeyPathKey to publicKeyFile.toString(),
                OnlineRuntimeConfig.StateEncryptionKeyPath to stateKeyFile.toString(),
                BackendReleaseIdentity.ReleaseIdEnvironmentKey to "inplacex-backend-20260807-1",
                BackendReleaseIdentity.GitShaEnvironmentKey to "a".repeat(40),
                BackendReleaseIdentity.ImageDigestEnvironmentKey to "sha256:${"b".repeat(64)}",
                "INPLACEX_AD_MARKET_COUNTRY_HEADER" to "CF-IPCountry",
                "INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS" to "127.0.0.1",
            ),
        )

        assertEquals(true, config.isProduction)
        assertNotNull(config.database)
        assertNotNull(config.online)
        assertNotNull(config.adMarket)
        assertNotNull(config.releaseIdentity)
    }
}
