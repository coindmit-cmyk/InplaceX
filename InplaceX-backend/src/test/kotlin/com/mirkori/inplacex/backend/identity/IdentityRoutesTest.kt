package com.mirkori.inplacex.backend.identity

import com.mirkori.inplacex.backend.auth.JwtAccessTokenVerifier
import com.mirkori.inplacex.backend.auth.JwtVerificationPolicy
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

        val bootstrapKey = UUID.randomUUID().toString()
        val bootstrapBody =
            """{"installationId":"install-1","platform":"android","appVersion":"1.0","locale":"ru-RU"}"""
        val bootstrap = client.post("/api/v1/auth/bootstrap") {
            header("Idempotency-Key", bootstrapKey)
            contentType(ContentType.Application.Json)
            setBody(bootstrapBody)
        }

        assertEquals(HttpStatusCode.OK, bootstrap.status)
        val bootstrapJson = Json.parseToJsonElement(bootstrap.bodyAsText()).jsonObject
        val credentials = bootstrapJson.getValue("credentials").jsonObject
        val accessToken = credentials.getValue("accessToken").jsonPrimitive.content
        val refreshToken = credentials.getValue("refreshToken").jsonPrimitive.content
        assertEquals(3, accessToken.split('.').size)

        val bootstrapReplay = client.post("/api/v1/auth/bootstrap") {
            header("Idempotency-Key", bootstrapKey)
            contentType(ContentType.Application.Json)
            setBody(bootstrapBody)
        }
        assertEquals(HttpStatusCode.OK, bootstrapReplay.status)
        assertEquals(bootstrap.bodyAsText(), bootstrapReplay.bodyAsText())

        val bootstrapConflict = client.post("/api/v1/auth/bootstrap") {
            header("Idempotency-Key", bootstrapKey)
            contentType(ContentType.Application.Json)
            setBody(
                """{"installationId":"install-1","platform":"android","appVersion":"1.0","locale":"en-US"}""",
            )
        }
        assertEquals(HttpStatusCode.Conflict, bootstrapConflict.status)
        assertEquals("idempotency_key_reused", errorCode(bootstrapConflict.bodyAsText()))

        val refreshKey = UUID.randomUUID().toString()
        val refresh = client.post("/api/v1/auth/refresh") {
            header("Idempotency-Key", refreshKey)
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }

        assertEquals(HttpStatusCode.OK, refresh.status)
        val refreshed = Json.parseToJsonElement(refresh.bodyAsText()).jsonObject
        val rotatedRefreshToken = refreshed.getValue("refreshToken").jsonPrimitive.content
        assertNotEquals(refreshToken, rotatedRefreshToken)
        assertEquals(3, refreshed.getValue("accessToken").jsonPrimitive.content.split('.').size)

        val replay = client.post("/api/v1/auth/refresh") {
            header("Idempotency-Key", refreshKey)
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }
        assertEquals(HttpStatusCode.OK, replay.status)
        assertEquals(refresh.bodyAsText(), replay.bodyAsText())

        val conflictingReuse = client.post("/api/v1/auth/refresh") {
            header("Idempotency-Key", refreshKey)
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$rotatedRefreshToken"}""")
        }
        assertEquals(HttpStatusCode.Conflict, conflictingReuse.status)
        assertEquals("idempotency_key_reused", errorCode(conflictingReuse.bodyAsText()))
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

    @Test
    fun `Google exchange requires player auth and consumes a server challenge`() = testApplication {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:identity-google-routes-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        JdbcMigrationRunner().migrate(dataSource)
        val policy = CredentialPolicy("inplacex-identity", "inplacex-game-api")
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val service = GuestIdentityService(
            identities = JdbcGuestIdentityRepository(dataSource),
            saves = JdbcSaveRepository(dataSource),
            policy = policy,
            accessTokenIssuer = Rs256AccessTokenIssuer(keyPair.private, policy),
            googleIdentityVerifier = GoogleIdentityVerifier { idToken, nonce ->
                if (idToken == "verified-google-token" && nonce.isNotBlank()) {
                    VerifiedGoogleIdentity("verified-subject", "Verified Player")
                } else {
                    null
                }
            },
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )
        val accessVerifier = JwtAccessTokenVerifier(
            keyPair.public,
            JwtVerificationPolicy(policy.issuer, policy.audience),
            Clock.fixed(now, ZoneOffset.UTC),
        )
        application { configureIdentityRoutes(service, accessVerifier) }

        val bootstrap = client.post("/api/v1/auth/bootstrap") {
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType(ContentType.Application.Json)
            setBody("""{"installationId":"google-install","platform":"android"}""")
        }
        val bootstrapJson = Json.parseToJsonElement(bootstrap.bodyAsText()).jsonObject
        val accessToken = bootstrapJson
            .getValue("credentials")
            .jsonObject
            .getValue("accessToken")
            .jsonPrimitive
            .content

        val unauthorized = client.post("/api/v1/auth/google/challenge") {
            header("Idempotency-Key", UUID.randomUUID().toString())
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

        val challengeKey = UUID.randomUUID().toString()
        val challenge = client.post("/api/v1/auth/google/challenge") {
            header("Idempotency-Key", challengeKey)
            header("Authorization", "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, challenge.status)
        val challengeBody = challenge.bodyAsText()
        val challengeReplay = client.post("/api/v1/auth/google/challenge") {
            header("Idempotency-Key", challengeKey)
            header("Authorization", "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, challengeReplay.status)
        assertEquals(challengeBody, challengeReplay.bodyAsText())
        val nonce = Json.parseToJsonElement(challengeBody)
            .jsonObject
            .getValue("nonce")
            .jsonPrimitive
            .content

        val exchange = client.post("/api/v1/auth/google") {
            header("Idempotency-Key", UUID.randomUUID().toString())
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"idToken":"verified-google-token","nonce":"$nonce"}""")
        }
        assertEquals(HttpStatusCode.OK, exchange.status)
        val exchangeJson = Json.parseToJsonElement(exchange.bodyAsText()).jsonObject
        assertEquals("google", exchangeJson.getValue("accountKind").jsonPrimitive.content)
        assertEquals(
            bootstrapJson.getValue("playerId").jsonPrimitive.content,
            exchangeJson.getValue("playerId").jsonPrimitive.content,
        )

        val replay = client.post("/api/v1/auth/google") {
            header("Idempotency-Key", UUID.randomUUID().toString())
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody("""{"idToken":"verified-google-token","nonce":"$nonce"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
        assertFalse(replay.bodyAsText().contains("verified-google-token"))
        assertFalse(replay.bodyAsText().contains(nonce))
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

    private fun errorCode(source: String): String = Json.parseToJsonElement(source)
        .jsonObject
        .getValue("error")
        .jsonPrimitive
        .content
}
