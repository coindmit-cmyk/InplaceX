package com.mirkori.inplacex.backend.identity

import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
import com.mirkori.inplacex.backend.persistence.JdbcSaveRepository
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.h2.jdbcx.JdbcDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityRoutesTest {
    private val now = Instant.parse("2026-07-27T10:00:00Z")

    @Test
    fun `bootstrap and refresh expose the Android contract over HTTP`() = testApplication {
        application { configureIdentityRoutes(service()) }

        val bootstrap = client.post("/api/v1/auth/bootstrap") {
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType(ContentType.Application.Json)
            setBody(
                """{"installationId":"install-1","platform":"android","appVersion":"1.0","locale":"ru-RU"}""",
            )
        }

        assertEquals(HttpStatusCode.OK, bootstrap.status)
        val bootstrapJson = Json.parseToJsonElement(bootstrap.bodyAsText()).jsonObject
        val credentials = bootstrapJson.getValue("credentials").jsonObject
        val accessToken = credentials.getValue("accessToken").jsonPrimitive.content
        val refreshToken = credentials.getValue("refreshToken").jsonPrimitive.content
        assertEquals(3, accessToken.split('.').size)

        val refresh = client.post("/api/v1/auth/refresh") {
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }

        assertEquals(HttpStatusCode.OK, refresh.status)
        val refreshed = Json.parseToJsonElement(refresh.bodyAsText()).jsonObject
        assertNotEquals(refreshToken, refreshed.getValue("refreshToken").jsonPrimitive.content)
        assertEquals(3, refreshed.getValue("accessToken").jsonPrimitive.content.split('.').size)
    }

    @Test
    fun `invalid requests fail without echoing credentials`() = testApplication {
        application { configureIdentityRoutes(service()) }
        val secret = "raw-refresh-token-that-must-not-return"

        val missingKey = client.post("/api/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$secret"}""")
        }
        val malformed = client.post("/api/v1/auth/refresh") {
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$secret","refreshToken":"duplicate"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, missingKey.status)
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertFalse(missingKey.bodyAsText().contains(secret))
        assertFalse(malformed.bodyAsText().contains(secret))
    }

    private fun service(): GuestIdentityService {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:identity-routes-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        JdbcMigrationRunner().migrate(dataSource)
        val policy = CredentialPolicy("inplacex-identity", "inplacex-game-api")
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return GuestIdentityService(
            identities = JdbcGuestIdentityRepository(dataSource),
            saves = JdbcSaveRepository(dataSource),
            policy = policy,
            accessTokenIssuer = Rs256AccessTokenIssuer(keyPair.private, policy),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
    }
}
