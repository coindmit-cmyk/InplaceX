package com.mirkori.inplacex.backend.online

import com.mirkori.inplacex.backend.app.RuntimeDrainController
import com.mirkori.inplacex.backend.auth.JwtAccessTokenVerifier
import com.mirkori.inplacex.backend.auth.JwtVerificationPolicy
import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineSessionEventSequence
import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineLobbyRepository
import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineSessionRepository
import com.mirkori.inplacex.backend.online.persistence.OnlineStateCipher
import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
import com.mirkori.inplacex.backend.persistence.JdbcPlayerRepository
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
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.h2.jdbcx.JdbcDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineRoutesTest {
    private val now = Instant.parse("2026-07-27T12:00:00Z")
    private val playerId = UUID.randomUUID().toString()
    private val attackerId = UUID.randomUUID().toString()
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val verifier = JwtAccessTokenVerifier(
        verificationKey = keys.public,
        policy = JwtVerificationPolicy.platformGame(
            issuer = TokenIssuer,
            audience = TokenAudience,
            gameId = GameId,
        ),
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )
    private val playerToken = platformToken(playerId)
    private val attackerToken = platformToken(attackerId)

    @Test
    fun `WebSocket heartbeat deadline advances only after a valid ping`() {
        val ticker = AtomicLong(100)
        val deadline = WebSocketHeartbeatDeadline(ticker::get, timeoutNanos = 10)

        ticker.set(111)
        assertTrue(deadline.hasExpired())
        deadline.recordPing()
        assertFalse(deadline.hasExpired())
        ticker.set(122)
        assertTrue(deadline.hasExpired())
    }

    @Test
    fun `REST abuse limits are scoped by principal operation and invalid authentication`() = testApplication {
        val serviceClock = RouteMutableClock(now)
        val service = AuthoritativeOnlineDuelService(serviceClock, Duration.ofSeconds(5))
        val abuseProtector = OnlineAbuseProtector(
            clock = serviceClock,
            invalidAuthenticationLimit = 1,
            operationLimits = OnlineOperation.entries.associateWith { 1 },
        )
        application { configureOnlineRoutes(verifier, service, abuseProtector = abuseProtector) }

        val acceptedOnce = client.get("/api/v1/matchmaking/tickets/not-a-uuid") {
            bearer(playerToken)
        }
        assertEquals(HttpStatusCode.BadRequest, acceptedOnce.status)
        val principalLimited = client.get("/api/v1/matchmaking/tickets/not-a-uuid") {
            bearer(playerToken)
        }
        assertEquals(HttpStatusCode.TooManyRequests, principalLimited.status)
        assertEquals("60", principalLimited.headers[HttpHeaders.RetryAfter])
        assertEquals("{\"error\":\"rate_limited\"}", principalLimited.bodyAsText())

        val invalidOnce = client.get("/api/v1/matchmaking/tickets/not-a-uuid")
        assertEquals(HttpStatusCode.Unauthorized, invalidOnce.status)
        val invalidLimited = client.get("/api/v1/matchmaking/tickets/not-a-uuid")
        assertEquals(HttpStatusCode.TooManyRequests, invalidLimited.status)
        assertEquals("60", invalidLimited.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `successful REST authentication bypasses the failed auth IP budget`() = testApplication {
        val serviceClock = RouteMutableClock(now)
        val service = AuthoritativeOnlineDuelService(serviceClock, Duration.ofSeconds(5))
        val abuseProtector = OnlineAbuseProtector(
            clock = serviceClock,
            authenticationAttemptLimit = 1,
            invalidAuthenticationLimit = 10,
            operationLimits = OnlineOperation.entries.associateWith { 1 },
        )
        application { configureOnlineRoutes(verifier, service, abuseProtector = abuseProtector) }

        listOf(playerToken, attackerToken).forEach { token ->
            val authenticated = client.get("/api/v1/matchmaking/tickets/not-a-uuid") {
                bearer(token)
            }
            assertEquals(HttpStatusCode.BadRequest, authenticated.status)
        }
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/api/v1/matchmaking/tickets/not-a-uuid").status,
        )
        val authenticationAttemptLimited = client.get("/api/v1/matchmaking/tickets/not-a-uuid") {
            header(HttpHeaders.Authorization, "Basic invalid")
        }
        assertEquals(HttpStatusCode.TooManyRequests, authenticationAttemptLimited.status)
        assertEquals("60", authenticationAttemptLimited.headers[HttpHeaders.RetryAfter])

        val principalLimited = client.get("/api/v1/matchmaking/tickets/not-a-uuid") {
            bearer(playerToken)
        }
        assertEquals(HttpStatusCode.TooManyRequests, principalLimited.status)
    }

    @Test
    fun `exhausted failed-auth budget gates REST and WebSocket before token verification`() = testApplication {
        val serviceClock = RouteMutableClock(now)
        val service = AuthoritativeOnlineDuelService(serviceClock, Duration.ofSeconds(5))
        val abuseProtector = OnlineAbuseProtector(
            clock = serviceClock,
            authenticationAttemptLimit = 1,
            invalidAuthenticationLimit = 10,
            operationLimits = OnlineOperation.entries.associateWith { 10 },
        )
        application { configureOnlineRoutes(verifier, service, abuseProtector = abuseProtector) }
        val forgedKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val forgedToken = platformToken(playerId, forgedKeys.private)

        val rejectedRest = client.get("/api/v1/matchmaking/tickets/not-a-uuid") {
            bearer(forgedToken)
        }
        assertEquals(HttpStatusCode.Unauthorized, rejectedRest.status)
        val gatedValidRest = client.get("/api/v1/matchmaking/tickets/not-a-uuid") {
            bearer(playerToken)
        }
        assertEquals(HttpStatusCode.TooManyRequests, gatedValidRest.status)

        serviceClock.advance(Duration.ofMinutes(1))
        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket(
            urlString = "/api/v1/ws/sessions/not-a-uuid",
            request = {
                bearer(forgedToken)
                header(HttpHeaders.SecWebSocketProtocol, "inplacex.online.v1")
            },
        ) {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
        wsClient.webSocket(
            urlString = "/api/v1/ws/sessions/not-a-uuid",
            request = {
                bearer(playerToken)
                header(HttpHeaders.SecWebSocketProtocol, "inplacex.online.v1")
            },
        ) {
            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, closeReason.await()?.code)
        }
    }

    @Test
    fun `legacy membership migration is protected by its own principal budget`() = testApplication {
        val serviceClock = RouteMutableClock(now)
        val service = AuthoritativeOnlineDuelService(serviceClock, Duration.ofSeconds(5))
        val operationLimits = OnlineOperation.entries.associateWith { operation ->
            if (operation == OnlineOperation.MigrateLegacyMembership) 1 else 10
        }
        val abuseProtector = OnlineAbuseProtector(
            clock = serviceClock,
            operationLimits = operationLimits,
        )
        application { configureOnlineRoutes(verifier, service, abuseProtector = abuseProtector) }

        val acceptedOnce = client.post("/api/v1/sessions/not-a-uuid/legacy-membership") {
            bearer(playerToken)
        }
        assertEquals(HttpStatusCode.BadRequest, acceptedOnce.status)

        val principalLimited = client.post("/api/v1/sessions/not-a-uuid/legacy-membership") {
            bearer(playerToken)
        }
        assertEquals(HttpStatusCode.TooManyRequests, principalLimited.status)
        assertEquals("60", principalLimited.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun `authenticated REST flow creates and plays an authoritative online duel`() = testApplication {
        val serviceClock = RouteMutableClock(now)
        val service = AuthoritativeOnlineDuelService(serviceClock, Duration.ofSeconds(5))
        application { configureOnlineRoutes(verifier, service) }
        val ticket = createMatchedTicket(playerToken, serviceClock)
        val sessionId = ticket.getValue("sessionId").jsonPrimitive.content

        val initial = client.get("/api/v1/sessions/$sessionId") {
            bearer(playerToken)
        }
        assertEquals(HttpStatusCode.OK, initial.status)
        val initialJson = json(initial.bodyAsText())
        assertEquals("setup", initialJson.getValue("phase").jsonPrimitive.content)
        assertEquals("race", initialJson.getValue("playStyle").jsonPrimitive.content)
        assertEquals(6, initialJson.getValue("codeLength").jsonPrimitive.content.toInt())
        assertEquals("null", initialJson.getValue("attemptLimit").toString())

        val secretCommand = UUID.randomUUID().toString()
        val active = client.post("/api/v1/sessions/$sessionId/setup/secret") {
            bearer(playerToken)
            header("Idempotency-Key", secretCommand)
            contentType(ContentType.Application.Json)
            setBody(
                """{"commandId":"$secretCommand","expectedRevision":0,"secret":"111234"}""",
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
                """{"commandId":"$guessCommand","expectedRevision":$activeRevision,"guess":"001001"}""",
            )
        }
        assertEquals(HttpStatusCode.OK, turn.status)
        val turnBody = turn.bodyAsText()
        val turnJson = json(turnBody)
        assertTrue(turnJson.getValue("revision").jsonPrimitive.content.toLong() > activeRevision)
        val attempts = turnJson.getValue("attempts").jsonArray
        assertEquals("001001", attempts.first().jsonObject.getValue("ownGuess").jsonPrimitive.content)
        assertTrue(
            attempts
                .map { it.jsonObject }
                .filter { it.getValue("actor").jsonPrimitive.content == "opponent" }
                .all { it.getValue("ownGuess").toString() == "null" },
        )
        assertFalse(turnBody.contains("111234"))
    }

    @Test
    fun `authenticated WebSocket subscribe resync and ping use the v1 envelope`() = testApplication {
        val serviceClock = RouteMutableClock(now)
        val service = AuthoritativeOnlineDuelService(serviceClock, Duration.ofSeconds(5))
        application { configureOnlineRoutes(verifier, service) }
        val sessionId = createMatchedTicket(playerToken, serviceClock)
            .getValue("sessionId").jsonPrimitive.content
        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocket(
            urlString = "/api/v1/ws/sessions/$sessionId",
            request = {
                bearer(playerToken)
                header(HttpHeaders.SecWebSocketProtocol, "inplacex.online.v1")
            },
        ) {
            val subscribeRequestId = UUID.randomUUID().toString()
            send(Frame.Text(webSocketControl(sessionId, subscribeRequestId, "session.subscribe", "{}")))
            val snapshot = json((incoming.receive() as Frame.Text).readText())
            assertEquals("1.0", snapshot.getValue("schemaVersion").jsonPrimitive.content)
            assertEquals(subscribeRequestId, snapshot.getValue("requestId").jsonPrimitive.content)
            assertEquals(sessionId, snapshot.getValue("sessionId").jsonPrimitive.content)
            assertEquals("session.snapshot", snapshot.getValue("type").jsonPrimitive.content)
            val snapshotEventSequence = snapshot.getValue("eventSeq").jsonPrimitive.content.toLong()
            val initialSnapshot = snapshot.getValue("payload").jsonObject
                .getValue("snapshot").jsonObject
            assertEquals(
                snapshotEventSequence,
                initialSnapshot.getValue("eventSeq").jsonPrimitive.content.toLong(),
            )
            assertEquals("SETUP_SECRET_A", initialSnapshot.getValue("phase").jsonPrimitive.content)

            val active = service.submitSecret(
                playerId = playerId,
                sessionId = sessionId,
                commandId = UUID.randomUUID().toString(),
                expectedRevision = 0,
                secret = "111234",
            )
            service.submitGuess(
                playerId = playerId,
                sessionId = sessionId,
                commandId = UUID.randomUUID().toString(),
                expectedRevision = active.revision,
                guess = "001001",
            )
            val resyncRequestId = UUID.randomUUID().toString()
            send(
                Frame.Text(
                    webSocketControl(
                        sessionId,
                        resyncRequestId,
                        "session.resync",
                        """{"lastSeenEventSeq":$snapshotEventSequence}""",
                    ),
                ),
            )
            val replay = json((incoming.receive() as Frame.Text).readText())
            assertEquals("session.snapshot", replay.getValue("type").jsonPrimitive.content)
            assertEquals(resyncRequestId, replay.getValue("requestId").jsonPrimitive.content)
            assertTrue(
                replay.getValue("eventSeq").jsonPrimitive.content.toLong() > snapshotEventSequence,
            )
            val recoveredSnapshot = replay.getValue("payload").jsonObject
                .getValue("snapshot").jsonObject
            assertEquals("ACTIVE_RACE", recoveredSnapshot.getValue("phase").jsonPrimitive.content)
            assertEquals(
                replay.getValue("eventSeq").jsonPrimitive.content.toLong(),
                recoveredSnapshot.getValue("eventSeq").jsonPrimitive.content.toLong(),
            )
            val turns = recoveredSnapshot.getValue("turns").jsonArray.map { it.jsonObject }
            assertEquals("001001", turns.first().getValue("ownGuess").jsonPrimitive.content)
            assertEquals("null", turns.last().getValue("ownGuess").toString())

            val pingRequestId = UUID.randomUUID().toString()
            send(Frame.Text(webSocketControl(sessionId, pingRequestId, "session.ping", "{}")))
            val heartbeat = json((incoming.receive() as Frame.Text).readText())
            assertEquals("connection.heartbeat", heartbeat.getValue("type").jsonPrimitive.content)
            assertEquals(pingRequestId, heartbeat.getValue("requestId").jsonPrimitive.content)
            assertTrue(
                heartbeat.getValue("eventSeq").jsonPrimitive.content.toLong() >
                    replay.getValue("eventSeq").jsonPrimitive.content.toLong(),
            )

            val missingCursorRequestId = UUID.randomUUID().toString()
            send(
                Frame.Text(
                    webSocketControl(
                        sessionId,
                        missingCursorRequestId,
                        "session.resync",
                        """{"lastSeenEventSeq":999999}""",
                    ),
                ),
            )
            val replayGap = json((incoming.receive() as Frame.Text).readText())
            assertEquals("session.replayGap", replayGap.getValue("type").jsonPrimitive.content)
            assertEquals(missingCursorRequestId, replayGap.getValue("requestId").jsonPrimitive.content)
        }
    }

    @Test
    fun `drain marker actively closes an established WebSocket`() = testApplication {
        val temporaryDirectory = Files.createTempDirectory("inplacex-ws-drain-")
        val drainMarker = temporaryDirectory.resolve("drain.flag")
        try {
            val drainController = RuntimeDrainController.fromEnvironment(
                mapOf(RuntimeDrainController.DrainMarkerPathEnvironmentKey to drainMarker.toString()),
                production = true,
            )
            val serviceClock = RouteMutableClock(now)
            val service = AuthoritativeOnlineDuelService(serviceClock, Duration.ofSeconds(5))
            application {
                configureOnlineRoutes(
                    verifier = verifier,
                    service = service,
                    drainController = drainController,
                )
            }
            val sessionId = createMatchedTicket(playerToken, serviceClock)
                .getValue("sessionId").jsonPrimitive.content
            val wsClient = createClient { install(WebSockets) }

            wsClient.webSocket(
                urlString = "/api/v1/ws/sessions/$sessionId",
                request = {
                    bearer(playerToken)
                    header(HttpHeaders.SecWebSocketProtocol, "inplacex.online.v1")
                },
            ) {
                send(Frame.Text(webSocketControl(sessionId, UUID.randomUUID().toString(), "session.subscribe", "{}")))
                assertEquals(
                    "session.snapshot",
                    json((incoming.receive() as Frame.Text).readText()).getValue("type").jsonPrimitive.content,
                )

                Files.writeString(drainMarker, "deployment-id\n")

                val reason = withTimeout(3_000) { closeReason.await() }
                assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
                assertEquals("service_draining", reason?.message)
            }
        } finally {
            Files.deleteIfExists(drainMarker)
            Files.deleteIfExists(temporaryDirectory)
        }
    }

    @Test
    fun `WebSocket receives a session change written through another database event store instance`() =
        testApplication {
            val dataSource = JdbcDataSource().apply {
                setURL("jdbc:h2:mem:ws-fanout-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            }
            JdbcMigrationRunner().migrate(dataSource)
            val writerEvents = JdbcOnlineSessionEventSequence(dataSource)
            val readerEvents = JdbcOnlineSessionEventSequence(dataSource)
            val serviceClock = RouteMutableClock(now)
            val service = AuthoritativeOnlineDuelService(
                clock = serviceClock,
                botFallbackDelay = Duration.ofSeconds(5),
                sessionEvents = writerEvents,
            )
            application { configureOnlineRoutes(verifier, service, readerEvents) }
            val sessionId = createMatchedTicket(playerToken, serviceClock)
                .getValue("sessionId").jsonPrimitive.content
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO duel_sessions(id, mode, status, config_json, version, created_at)
                    VALUES (?, 'ONLINE_DUEL', 'SETUP', '{}', 0, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.setObject(2, now.atOffset(ZoneOffset.UTC))
                    statement.executeUpdate()
                }
            }

            val wsClient = createClient { install(WebSockets) }
            wsClient.webSocket(
                urlString = "/api/v1/ws/sessions/$sessionId",
                request = {
                    bearer(playerToken)
                    header(HttpHeaders.SecWebSocketProtocol, "inplacex.online.v1")
                },
            ) {
                send(Frame.Text(webSocketControl(sessionId, UUID.randomUUID().toString(), "session.subscribe", "{}")))
                assertEquals("session.snapshot", json((incoming.receive() as Frame.Text).readText())
                    .getValue("type").jsonPrimitive.content)

                service.submitSecret(
                    playerId = playerId,
                    sessionId = sessionId,
                    commandId = UUID.randomUUID().toString(),
                    expectedRevision = 0,
                    secret = "111234",
                )
                val pingRequestId = UUID.randomUUID().toString()
                send(Frame.Text(webSocketControl(sessionId, pingRequestId, "session.ping", "{}")))

                val liveFrameText = withTimeout(3_000) { (incoming.receive() as Frame.Text).readText() }
                val liveFrame = json(liveFrameText)
                assertEquals("session.snapshot", liveFrame.getValue("type").jsonPrimitive.content)
                assertEquals("null", liveFrame.getValue("requestId").toString())
                assertEquals(1L, liveFrame.getValue("payload").jsonObject
                    .getValue("snapshot").jsonObject.getValue("revision").jsonPrimitive.content.toLong())
                assertFalse(liveFrameText.contains("111234"))

                val heartbeat = json(withTimeout(3_000) { (incoming.receive() as Frame.Text).readText() })
                assertEquals("connection.heartbeat", heartbeat.getValue("type").jsonPrimitive.content)
                assertEquals(pingRequestId, heartbeat.getValue("requestId").jsonPrimitive.content)
                assertTrue(
                    heartbeat.getValue("eventSeq").jsonPrimitive.content.toLong() >
                        liveFrame.getValue("eventSeq").jsonPrimitive.content.toLong(),
                )
            }
        }

    @Test
    fun `WebSocket rejects query credentials foreign members and legacy frames`() = testApplication {
        val serviceClock = RouteMutableClock(now)
        val service = AuthoritativeOnlineDuelService(serviceClock, Duration.ofSeconds(5))
        val operationLimits = OnlineOperation.entries.associateWith { operation ->
            if (operation == OnlineOperation.OpenWebSocket) 1 else 20
        }
        val abuseProtector = OnlineAbuseProtector(
            clock = serviceClock,
            authenticationAttemptLimit = 1,
            invalidAuthenticationLimit = 2,
            operationLimits = operationLimits,
        )
        application { configureOnlineRoutes(verifier, service, abuseProtector = abuseProtector) }
        val sessionId = createMatchedTicket(playerToken, serviceClock)
            .getValue("sessionId").jsonPrimitive.content
        val wsClient = createClient { install(WebSockets) }

        wsClient.webSocket(
            urlString = "/api/v1/ws/sessions/$sessionId?access_token=$playerToken",
            request = { header(HttpHeaders.SecWebSocketProtocol, "inplacex.online.v1") },
        ) {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
        wsClient.webSocket(
            urlString = "/api/v1/ws/sessions/$sessionId",
            request = {
                header(HttpHeaders.Authorization, "Basic invalid")
                header(HttpHeaders.SecWebSocketProtocol, "inplacex.online.v1")
            },
        ) {
            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, closeReason.await()?.code)
        }
        serviceClock.advance(Duration.ofMinutes(1))
        wsClient.webSocket(
            urlString = "/api/v1/ws/sessions/$sessionId",
            request = {
                bearer(attackerToken)
                header(HttpHeaders.SecWebSocketProtocol, "inplacex.online.v1")
            },
        ) {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
        wsClient.webSocket(
            urlString = "/api/v1/ws/sessions/$sessionId",
            request = {
                bearer(playerToken)
                header(HttpHeaders.SecWebSocketProtocol, "inplacex.online.v1")
            },
        ) {
            send(Frame.Text("""{"type":"snapshot.request"}"""))
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
        wsClient.webSocket(
            urlString = "/api/v1/ws/sessions/$sessionId",
            request = {
                bearer(playerToken)
                header(HttpHeaders.SecWebSocketProtocol, "inplacex.online.v1")
            },
        ) {
            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, closeReason.await()?.code)
        }
    }

    @Test
    fun `WebSocket charges malformed upgrades and control frames to principal budgets`() = testApplication {
        val serviceClock = RouteMutableClock(now)
        val service = AuthoritativeOnlineDuelService(serviceClock, Duration.ofSeconds(5))
        val operationLimits = OnlineOperation.entries.associateWith { operation ->
            when (operation) {
                OnlineOperation.OpenWebSocket, OnlineOperation.WebSocketControl -> 1
                else -> 20
            }
        }
        val abuseProtector = OnlineAbuseProtector(
            clock = serviceClock,
            operationLimits = operationLimits,
        )
        application { configureOnlineRoutes(verifier, service, abuseProtector = abuseProtector) }
        val sessionId = createMatchedTicket(attackerToken, serviceClock)
            .getValue("sessionId").jsonPrimitive.content
        val wsClient = createClient { install(WebSockets) }

        wsClient.webSocket(
            urlString = "/api/v1/ws/sessions/not-a-uuid",
            request = {
                bearer(playerToken)
                header(HttpHeaders.SecWebSocketProtocol, "inplacex.online.v1")
            },
        ) {
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closeReason.await()?.code)
        }
        wsClient.webSocket(
            urlString = "/api/v1/ws/sessions/not-a-uuid",
            request = {
                bearer(playerToken)
                header(HttpHeaders.SecWebSocketProtocol, "inplacex.online.v1")
            },
        ) {
            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, closeReason.await()?.code)
        }

        wsClient.webSocket(
            urlString = "/api/v1/ws/sessions/$sessionId",
            request = {
                bearer(attackerToken)
                header(HttpHeaders.SecWebSocketProtocol, "inplacex.online.v1")
            },
        ) {
            send(Frame.Text(webSocketControl(sessionId, UUID.randomUUID().toString(), "session.subscribe", "{}")))
            assertEquals(
                "session.snapshot",
                json((incoming.receive() as Frame.Text).readText()).getValue("type").jsonPrimitive.content,
            )
            send(Frame.Text(webSocketControl(sessionId, UUID.randomUUID().toString(), "session.ping", "{}")))
            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, closeReason.await()?.code)
        }
    }

    @Test
    fun `private friend invite creates one shared human session for two tokens`() = testApplication {
        val service = AuthoritativeOnlineDuelService(
            Clock.fixed(now, ZoneOffset.UTC),
            Duration.ofSeconds(5),
        )
        application { configureOnlineRoutes(verifier, service) }

        val createCommand = UUID.randomUUID().toString()
        val createdResponse = client.post("/api/v1/friends/invites") {
            bearer(playerToken)
            header("Idempotency-Key", createCommand)
            contentType(ContentType.Application.Json)
            setBody(
                """{"commandId":"$createCommand","playStyle":"race","codeLength":6}""",
            )
        }
        assertEquals(HttpStatusCode.OK, createdResponse.status)
        val created = json(createdResponse.bodyAsText())
        val inviteCode = created.getValue("inviteCode").jsonPrimitive.content
        assertEquals("waiting", created.getValue("status").jsonPrimitive.content)
        assertEquals("race", created.getValue("playStyle").jsonPrimitive.content)
        assertEquals(6, created.getValue("codeLength").jsonPrimitive.content.toInt())

        val acceptCommand = UUID.randomUUID().toString()
        val acceptedResponse = client.post("/api/v1/friends/invites/$inviteCode/accept") {
            bearer(attackerToken)
            header("Idempotency-Key", acceptCommand)
            contentType(ContentType.Application.Json)
            setBody("""{"commandId":"$acceptCommand"}""")
        }
        assertEquals(HttpStatusCode.OK, acceptedResponse.status)
        val accepted = json(acceptedResponse.bodyAsText())
        val sessionId = accepted.getValue("sessionId").jsonPrimitive.content
        assertEquals("matched", accepted.getValue("status").jsonPrimitive.content)

        val ownerPoll = client.get("/api/v1/friends/invites/$inviteCode") {
            bearer(playerToken)
        }
        assertEquals(HttpStatusCode.OK, ownerPoll.status)
        assertEquals(
            sessionId,
            json(ownerPoll.bodyAsText()).getValue("sessionId").jsonPrimitive.content,
        )

        val firstSession = client.get("/api/v1/sessions/$sessionId") { bearer(playerToken) }
        val secondSession = client.get("/api/v1/sessions/$sessionId") { bearer(attackerToken) }
        assertEquals(HttpStatusCode.OK, firstSession.status)
        assertEquals(HttpStatusCode.OK, secondSession.status)
    }

    @Test
    fun `targeted friend invite appears only in recipient collection`() = testApplication {
        val service = AuthoritativeOnlineDuelService(Clock.fixed(now, ZoneOffset.UTC))
        application { configureOnlineRoutes(verifier, service) }
        val commandId = UUID.randomUUID().toString()

        val created = client.post("/api/v1/friends/invites") {
            bearer(playerToken)
            header("Idempotency-Key", commandId)
            contentType(ContentType.Application.Json)
            setBody(
                """{"commandId":"$commandId","playStyle":"race","codeLength":4,"targetPlayerId":"$attackerId"}""",
            )
        }
        assertEquals(HttpStatusCode.OK, created.status)

        val ownerList = client.get("/api/v1/friends/invites") { bearer(playerToken) }
        val recipientList = client.get("/api/v1/friends/invites") { bearer(attackerToken) }
        assertEquals(0, Json.parseToJsonElement(ownerList.bodyAsText()).jsonArray.size)
        assertEquals(1, Json.parseToJsonElement(recipientList.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `forged token and stale revision fail closed`() = testApplication {
        val serviceClock = RouteMutableClock(now)
        val service = AuthoritativeOnlineDuelService(serviceClock, Duration.ofSeconds(5))
        application { configureOnlineRoutes(verifier, service) }
        val forgedKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val forgedToken = platformToken(playerId, forgedKeys.private)

        val unauthorized = client.post("/api/v1/matchmaking/tickets") {
            bearer(forgedToken)
            val commandId = UUID.randomUUID().toString()
            header("Idempotency-Key", commandId)
            contentType(ContentType.Application.Json)
            setBody("""{"commandId":"$commandId","mode":"classic"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

        val sessionId = createMatchedTicket(playerToken, serviceClock)
            .getValue("sessionId").jsonPrimitive.content
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

    @Test
    fun `legacy membership endpoint is authenticated bounded and idempotent`() = testApplication {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:legacy-route-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        }
        JdbcMigrationRunner().migrate(dataSource)
        val legacyPlayerId = UUID.randomUUID().toString()
        val opponentPlayerId = UUID.randomUUID().toString()
        val players = JdbcPlayerRepository(dataSource)
        players.create(legacyPlayerId, "Legacy")
        players.create(opponentPlayerId, "Opponent")
        players.create(playerId, "Platform")
        val legacyRefreshToken = "legacy-${"r".repeat(43)}"
        insertLegacyRefreshCredential(dataSource, legacyPlayerId, legacyRefreshToken)
        val sessions = JdbcOnlineSessionRepository(dataSource, OnlineStateCipher(ByteArray(32) { 4 }))
        val service = AuthoritativeOnlineDuelService(
            clock = Clock.fixed(now, ZoneOffset.UTC),
            sessionRepository = sessions,
            lobbyRepository = JdbcOnlineLobbyRepository(dataSource, sessions),
        )
        val invite = service.createPrivateInvite(
            legacyPlayerId,
            UUID.randomUUID().toString(),
            OnlineFriendPlayStyle.RACE,
            4,
        )
        val sessionId = requireNotNull(
            service.acceptPrivateInvite(
                opponentPlayerId,
                UUID.randomUUID().toString(),
                invite.inviteCode,
            ).sessionId,
        )
        application {
            configureOnlineRoutes(
                verifier = verifier,
                service = service,
                playerProvisioner = OnlinePlayerProvisioner(players::ensurePlatformPlayer),
            )
        }
        val commandId = UUID.randomUUID().toString()
        val body = """{"commandId":"$commandId","legacyRefreshToken":"$legacyRefreshToken"}"""

        val unauthenticated = client.post("/api/v1/sessions/$sessionId/legacy-membership") {
            header("Idempotency-Key", commandId)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)

        val oversized = client.post("/api/v1/sessions/$sessionId/legacy-membership") {
            bearer(playerToken)
            header("Idempotency-Key", commandId)
            contentType(ContentType.Application.Json)
            setBody("x".repeat(2 * 1024 + 1))
        }
        assertEquals(HttpStatusCode.BadRequest, oversized.status)

        val migrated = client.post("/api/v1/sessions/$sessionId/legacy-membership") {
            bearer(playerToken)
            header("Idempotency-Key", commandId)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, migrated.status)
        assertEquals("migrated", json(migrated.bodyAsText()).getValue("status").jsonPrimitive.content)

        val replayed = client.post("/api/v1/sessions/$sessionId/legacy-membership") {
            bearer(playerToken)
            header("Idempotency-Key", commandId)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, replayed.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/sessions/$sessionId") { bearer(playerToken) }.status)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.createTicket(token: String) =
        client.post("/api/v1/matchmaking/tickets") {
            bearer(token)
            val commandId = UUID.randomUUID().toString()
            header("Idempotency-Key", commandId)
            contentType(ContentType.Application.Json)
            setBody(
                """{"commandId":"$commandId","mode":"classic","playStyle":"race","codeLength":6}""",
            )
        }.let { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            json(response.bodyAsText())
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.createMatchedTicket(
        token: String,
        clock: RouteMutableClock,
    ) = createTicket(token).let { created ->
        assertEquals("searching", created.getValue("status").jsonPrimitive.content)
        val ticketId = created.getValue("ticketId").jsonPrimitive.content
        clock.advance(Duration.ofSeconds(5))
        client.get("/api/v1/matchmaking/tickets/$ticketId") {
            bearer(token)
        }.let { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            val matched = json(response.bodyAsText())
            assertEquals("matched", matched.getValue("status").jsonPrimitive.content)
            assertFalse(matched.getValue("sessionId").toString() == "null")
            matched
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun webSocketControl(
        sessionId: String,
        requestId: String,
        type: String,
        payload: String,
    ): String =
        """{"schemaVersion":"1.0","messageId":"${UUID.randomUUID()}","requestId":"$requestId","sessionId":"$sessionId","type":"$type","payload":$payload}"""

    private fun json(source: String) = Json.parseToJsonElement(source).jsonObject

    private fun platformToken(
        gamePlayerId: String,
        signingKey: PrivateKey = keys.private,
    ): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString(
            """{"iss":"$TokenIssuer","aud":"$TokenAudience","sub":"${UUID.randomUUID()}","pid":"$gamePlayerId","gid":"$GameId","sid":"${UUID.randomUUID()}","amr":"guest","iat":${now.epochSecond},"exp":${now.plusSeconds(900).epochSecond},"jti":"${UUID.randomUUID()}"}"""
                .toByteArray(StandardCharsets.UTF_8),
        )
        val unsigned = "$header.$payload"
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(signingKey)
            update(unsigned.toByteArray(StandardCharsets.US_ASCII))
            sign()
        }
        return "$unsigned.${encoder.encodeToString(signature)}"
    }

    private fun insertLegacyRefreshCredential(
        dataSource: JdbcDataSource,
        legacyPlayerId: String,
        refreshToken: String,
    ) {
        val familyId = UUID.randomUUID().toString()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO refresh_token_families(id, player_id, expires_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, familyId)
                statement.setString(2, legacyPlayerId)
                statement.setObject(3, now.plusSeconds(600).atOffset(ZoneOffset.UTC))
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO refresh_tokens(token_hash, family_id, expires_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(
                    1,
                    MessageDigest.getInstance("SHA-256")
                        .digest(refreshToken.toByteArray(StandardCharsets.UTF_8))
                        .joinToString("") { byte -> "%02x".format(byte) },
                )
                statement.setString(2, familyId)
                statement.setObject(3, now.plusSeconds(600).atOffset(ZoneOffset.UTC))
                statement.executeUpdate()
            }
        }
    }

    private companion object {
        const val TokenIssuer = "mirkori-platform"
        const val TokenAudience = "mirkori-games"
        const val GameId = "inplacex"
    }
}

private class RouteMutableClock(
    @Volatile private var current: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = RouteMutableClock(current, zone)

    override fun instant(): Instant = current

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
