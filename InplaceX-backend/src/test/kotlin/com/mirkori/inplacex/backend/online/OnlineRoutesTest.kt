package com.mirkori.inplacex.backend.online

import com.mirkori.inplacex.backend.auth.JwtAccessTokenVerifier
import com.mirkori.inplacex.backend.auth.JwtVerificationPolicy
import com.mirkori.inplacex.backend.identity.CredentialPolicy
import com.mirkori.inplacex.backend.identity.Rs256AccessTokenIssuer
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineRoutesTest {
    private val now = Instant.parse("2026-07-27T12:00:00Z")
    private val playerId = UUID.randomUUID().toString()
    private val attackerId = UUID.randomUUID().toString()
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val tokenPolicy = CredentialPolicy("inplacex-identity", "inplacex-game-api")
    private val issuer = Rs256AccessTokenIssuer(keys.private, tokenPolicy)
    private val verifier = JwtAccessTokenVerifier(
        verificationKey = keys.public,
        policy = JwtVerificationPolicy(
            issuer = tokenPolicy.issuer,
            audience = tokenPolicy.audience,
            maximumTokenLifetime = tokenPolicy.accessTtl,
        ),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )
    private val playerToken = issuer.issue(playerId, now, now.plus(tokenPolicy.accessTtl))
    private val attackerToken = issuer.issue(attackerId, now, now.plus(tokenPolicy.accessTtl))

    @Test
    fun `authenticated REST flow creates and plays an authoritative online duel`() = testApplication {
        val service = AuthoritativeOnlineDuelService(Clock.fixed(now, ZoneOffset.UTC))
        application { configureOnlineRoutes(verifier, service) }
        val ticket = createTicket(playerToken)
        val sessionId = ticket.getValue("sessionId").jsonPrimitive.content

        val initial = client.get("/api/v1/sessions/$sessionId") {
            bearer(playerToken)
        }
        assertEquals(HttpStatusCode.OK, initial.status)
        assertEquals("setup", json(initial.bodyAsText()).getValue("phase").jsonPrimitive.content)

        val secretCommand = UUID.randomUUID().toString()
        val active = client.post("/api/v1/sessions/$sessionId/setup/secret") {
            bearer(playerToken)
            header("Idempotency-Key", secretCommand)
            contentType(ContentType.Application.Json)
            setBody(
                """{"commandId":"$secretCommand","expectedRevision":0,"secret":"1234"}""",
            )
        }
        assertEquals(HttpStatusCode.OK, active.status)
        val activeJson = json(active.bodyAsText())
        assertEquals("active", activeJson.getValue("phase").jsonPrimitive.content)
        val activeRevision = activeJson.getValue("revision").jsonPrimitive.content.toLong()

        val guessCommand = UUID.randomUUID().toString()
        val turn = client.post("/api/v1/sessions/$sessionId/turns") {
            bearer(playerToken)
            header("Idempotency-Key", guessCommand)
            contentType(ContentType.Application.Json)
            setBody(
                """{"commandId":"$guessCommand","expectedRevision":$activeRevision,"guess":"0123"}""",
            )
        }
        assertEquals(HttpStatusCode.OK, turn.status)
        val turnBody = turn.bodyAsText()
        assertTrue(json(turnBody).getValue("revision").jsonPrimitive.content.toLong() > activeRevision)
        assertFalse(turnBody.contains("1234"))
    }

    @Test
    fun `authenticated WebSocket returns authoritative snapshot and rejects foreign membership`() = testApplication {
        val service = AuthoritativeOnlineDuelService(Clock.fixed(now, ZoneOffset.UTC))
        application { configureOnlineRoutes(verifier, service) }
        val sessionId = createTicket(playerToken).getValue("sessionId").jsonPrimitive.content
        val wsClient = createClient { install(WebSockets) }
        var snapshotFrame = ""

        wsClient.webSocket(
            urlString = "/api/v1/ws/sessions/$sessionId",
            request = {
                bearer(playerToken)
                header(HttpHeaders.SecWebSocketProtocol, "inplacex.online.v1")
            },
        ) {
            snapshotFrame = (incoming.receive() as Frame.Text).readText()
        }

        assertEquals("session.snapshot", json(snapshotFrame).getValue("type").jsonPrimitive.content)

        val foreign = client.get("/api/v1/sessions/$sessionId") {
            bearer(attackerToken)
        }
        assertEquals(HttpStatusCode.Forbidden, foreign.status)
        assertFalse(foreign.bodyAsText().contains(playerId))
    }

    @Test
    fun `forged token and stale revision fail closed`() = testApplication {
        val service = AuthoritativeOnlineDuelService(Clock.fixed(now, ZoneOffset.UTC))
        application { configureOnlineRoutes(verifier, service) }
        val forgedKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val forgedToken = Rs256AccessTokenIssuer(forgedKeys.private, tokenPolicy)
            .issue(playerId, now, now.plus(tokenPolicy.accessTtl))

        val unauthorized = client.post("/api/v1/matchmaking/tickets") {
            bearer(forgedToken)
            val commandId = UUID.randomUUID().toString()
            header("Idempotency-Key", commandId)
            contentType(ContentType.Application.Json)
            setBody("""{"commandId":"$commandId","mode":"classic"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

        val sessionId = createTicket(playerToken).getValue("sessionId").jsonPrimitive.content
        val staleCommand = UUID.randomUUID().toString()
        val stale = client.post("/api/v1/sessions/$sessionId/turns") {
            bearer(playerToken)
            header("Idempotency-Key", staleCommand)
            contentType(ContentType.Application.Json)
            setBody(
                """{"commandId":"$staleCommand","expectedRevision":9,"guess":"0123"}""",
            )
        }
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertEquals("revision_conflict", json(stale.bodyAsText()).getValue("error").jsonPrimitive.content)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.createTicket(token: String) =
        client.post("/api/v1/matchmaking/tickets") {
            bearer(token)
            val commandId = UUID.randomUUID().toString()
            header("Idempotency-Key", commandId)
            contentType(ContentType.Application.Json)
            setBody("""{"commandId":"$commandId","mode":"classic"}""")
        }.let { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            json(response.bodyAsText())
        }

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun json(source: String) = Json.parseToJsonElement(source).jsonObject
}
