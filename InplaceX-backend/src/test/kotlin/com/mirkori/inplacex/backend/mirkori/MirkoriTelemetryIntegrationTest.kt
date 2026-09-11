package com.mirkori.inplacex.backend.mirkori

import com.mirkori.inplacex.backend.app.MirkoriTelemetryRuntimeConfig
import com.mirkori.inplacex.backend.online.AuthoritativeOnlineDuelService
import com.mirkori.inplacex.backend.online.OnlineFriendPlayStyle
import com.mirkori.inplacex.backend.online.persistence.DurableOnlineSession
import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineLobbyRepository
import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineSessionRepository
import com.mirkori.inplacex.backend.online.persistence.OnlineStateCipher
import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
import com.mirkori.platform.sdk.MirkoriGameServerCredential
import com.mirkori.platform.sdk.MirkoriGameServerTelemetryClient
import com.mirkori.platform.sdk.MirkoriGameServerTelemetryConfig
import com.mirkori.platform.sdk.PlatformAchievementFact
import com.mirkori.platform.sdk.PlatformAchievementTelemetryDecision
import com.mirkori.platform.sdk.PlatformGameplayEventType
import com.mirkori.platform.sdk.PlatformGameplayFact
import com.mirkori.platform.sdk.PlatformGameplaySessionStatus
import com.mirkori.platform.sdk.PlatformGameplayTelemetryDecision
import com.mirkori.platform.sdk.PlatformHttpResponse
import com.mirkori.platform.sdk.PlatformTransport
import com.mirkori.platform.sdk.PlatformTransportException
import com.mirkori.platform.sdk.PlatformTransportFailure
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlinx.coroutines.runBlocking
import org.h2.jdbcx.JdbcDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirkoriTelemetryIntegrationTest {
    @Test
    fun `server SDK sends strict achievement and gameplay facts with a redacted credential`() = runBlocking {
        val gameProfileId = UUID.randomUUID().toString()
        val policyVersionId = UUID.randomUUID().toString()
        val candidateId = UUID.randomUUID().toString()
        val token = "${UUID.randomUUID()}.${"A".repeat(43)}"
        val requests = mutableListOf<com.mirkori.platform.sdk.PlatformHttpRequest>()
        val transport = PlatformTransport { request ->
            requests += request
            when {
                request.url.endsWith("/telemetry/achievements") -> PlatformHttpResponse(
                    status = 200,
                    body = """{"schemaVersion":1,"eventId":"achievement-1","gameId":"inplacex","decision":"eligible","policyVersionId":"$policyVersionId","candidateId":"$candidateId"}""",
                )
                else -> PlatformHttpResponse(
                    status = 200,
                    body = """{"schemaVersion":1,"eventId":"gameplay-1","gameId":"inplacex","sessionId":"duel-1","decision":"accepted","sessionStatus":"open","trustedDurationSeconds":0}""",
                )
            }
        }
        val credential = MirkoriGameServerCredential.fromToken(token)
        val client = MirkoriGameServerTelemetryClient(
            config = MirkoriGameServerTelemetryConfig(
                platformBaseUrl = "http://127.0.0.1:8080",
                gameId = "inplacex",
                allowCleartextLoopback = true,
            ),
            transport = transport,
        )

        val achievement = client.submitAchievement(
            credential,
            PlatformAchievementFact("achievement-1", gameProfileId, "first_win", Instant.parse("2026-09-11T10:00:00Z")),
        )
        val gameplay = client.submitGameplay(
            credential,
            PlatformGameplayFact(
                eventId = "gameplay-1",
                gameProfileId = gameProfileId,
                sessionId = "duel-1",
                sequenceNumber = 0,
                eventType = PlatformGameplayEventType.START,
                occurredAt = Instant.parse("2026-09-11T10:00:00Z"),
            ),
        )

        assertEquals(PlatformAchievementTelemetryDecision.ELIGIBLE, achievement.decision)
        assertEquals(PlatformGameplayTelemetryDecision.ACCEPTED, gameplay.decision)
        assertEquals(PlatformGameplaySessionStatus.OPEN, gameplay.sessionStatus)
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals("MirkoriGame $token", request.headers["Authorization"])
            assertFalse(request.toString().contains(token))
        }
        assertFalse(credential.toString().contains(token))
    }

    @Test
    fun `authoritative duel transaction records ordered gameplay and one first win`() {
        val dataSource = migratedDataSource("duel")
        val owner = UUID.randomUUID().toString()
        val guest = UUID.randomUUID().toString()
        registerPlayers(dataSource, owner, guest)
        val clock = MutableTestClock(Instant.parse("2026-09-11T10:00:00Z"))
        val journal = JdbcMirkoriTelemetryJournal(dataSource)
        val repository = JdbcOnlineSessionRepository(
            dataSource = dataSource,
            cipher = OnlineStateCipher(ByteArray(32) { (it + 1).toByte() }),
            telemetryJournal = journal,
        )
        AuthoritativeOnlineDuelService(
            clock = clock,
            sessionRepository = repository,
            lobbyRepository = JdbcOnlineLobbyRepository(dataSource, repository),
        ).use { service ->
            val invite = service.createPrivateInvite(
                playerId = owner,
                commandId = UUID.randomUUID().toString(),
                playStyle = OnlineFriendPlayStyle.TURN_BASED,
                codeLength = 4,
            )
            val matched = service.acceptPrivateInvite(
                playerId = guest,
                commandId = UUID.randomUUID().toString(),
                inviteCode = invite.inviteCode,
            )
            val sessionId = requireNotNull(matched.sessionId)
            service.submitSecret(owner, sessionId, UUID.randomUUID().toString(), 0, "1234")
            service.submitSecret(guest, sessionId, UUID.randomUUID().toString(), 1, "5678")

            clock.advance(Duration.ofMinutes(4))
            assertEquals(2, journal.enqueueDueHeartbeats(clock.instant(), Duration.ofMinutes(4)))

            val winningCommand = UUID.randomUUID().toString()
            service.submitGuess(owner, sessionId, winningCommand, 2, "5678")
            service.submitGuess(owner, sessionId, winningCommand, 2, "5678")
        }

        val gameplayByPlayer = linkedMapOf<String, MutableList<Pair<Long, String>>>()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT game_profile_id, sequence_number, gameplay_event_type
                FROM mirkori_telemetry_outbox
                WHERE fact_type = 'gameplay'
                ORDER BY game_profile_id, sequence_number
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { results ->
                    while (results.next()) {
                        gameplayByPlayer.getOrPut(results.getString(1)) { mutableListOf() }
                            .add(results.getLong(2) to results.getString(3))
                    }
                }
            }
            connection.prepareStatement(
                """
                SELECT game_profile_id, achievement_id, COUNT(*)
                FROM mirkori_telemetry_outbox
                WHERE fact_type = 'achievement'
                GROUP BY game_profile_id, achievement_id
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { results ->
                    assertTrue(results.next())
                    assertEquals(owner, results.getString(1))
                    assertEquals("first_win", results.getString(2))
                    assertEquals(1, results.getInt(3))
                    assertFalse(results.next())
                }
            }
        }
        assertEquals(setOf(owner, guest), gameplayByPlayer.keys)
        gameplayByPlayer.values.forEach { events ->
            assertEquals(listOf(0L to "start", 1L to "heartbeat", 2L to "end"), events)
        }
    }

    @Test
    fun `owner gated runtime retries the same durable event and then marks it delivered`() = runBlocking {
        assertNull(MirkoriTelemetryRuntimeConfig.fromEnvironmentOrNull(emptyMap(), production = false))
        val secretFile = Files.createTempFile("mirkori-game-credential", ".secret")
        val token = "${UUID.randomUUID()}.${"B".repeat(43)}"
        Files.writeString(secretFile, token)
        try {
            val runtime = MirkoriTelemetryRuntimeConfig.fromEnvironmentOrNull(
                environment = mapOf(
                    MirkoriTelemetryRuntimeConfig.EnabledEnvironmentKey to "true",
                    MirkoriTelemetryRuntimeConfig.PlatformBaseUrlEnvironmentKey to "http://127.0.0.1:8080",
                    MirkoriTelemetryRuntimeConfig.CredentialFileEnvironmentKey to secretFile.toString(),
                    MirkoriTelemetryRuntimeConfig.AllowCleartextLoopbackEnvironmentKey to "true",
                ),
                production = false,
            )
            assertNotNull(runtime)
            assertFalse(requireNotNull(runtime).readCredential().toString().contains(token))

            val dataSource = migratedDataSource("retry")
            val journal = JdbcMirkoriTelemetryJournal(dataSource)
            val startedAt = Instant.parse("2026-09-11T12:00:00Z")
            val profileId = UUID.randomUUID().toString()
            JdbcOnlineSessionRepository(
                dataSource = dataSource,
                cipher = OnlineStateCipher(ByteArray(32) { (it + 7).toByte() }),
                telemetryJournal = journal,
            ).use { repository ->
                repository.create(
                    DurableOnlineSession(
                        sessionId = UUID.randomUUID().toString(),
                        revision = 0,
                        status = "ACTIVE",
                        stateJson = "{}",
                        createdAt = startedAt,
                        startedAt = startedAt,
                        finishedAt = null,
                        expiresAt = null,
                        telemetry = DurableMirkoriTelemetryProjection(setOf(profileId), null),
                    ),
                )
            }
            val attempts = mutableListOf<String>()
            val worker = MirkoriTelemetryWorker(
                journal = journal,
                sender = MirkoriTelemetrySender { fact ->
                    attempts += fact.eventId
                    if (attempts.size == 1) throw PlatformTransportException(PlatformTransportFailure.TIMEOUT)
                    MirkoriTelemetryDeliveryReceipt(
                        decision = "accepted",
                        sessionStatus = "open",
                        trustedDurationSeconds = 0,
                    )
                },
                clock = Clock.fixed(startedAt, ZoneOffset.UTC),
                retryDelay = { Duration.ZERO },
            )

            assertFalse(worker.processOnce(startedAt))
            assertTrue(worker.processOnce(startedAt))
            assertEquals(2, attempts.size)
            assertEquals(attempts[0], attempts[1])
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT status, attempt_count, last_error_code FROM mirkori_telemetry_outbox WHERE event_id = ?",
                ).use { statement ->
                    statement.setString(1, attempts.singleDistinct())
                    statement.executeQuery().use { results ->
                        assertTrue(results.next())
                        assertEquals("delivered", results.getString("status"))
                        assertEquals(2, results.getInt("attempt_count"))
                        assertNull(results.getString("last_error_code"))
                    }
                }
            }
        } finally {
            Files.deleteIfExists(secretFile)
        }
    }

    @Test
    fun `scheduled delivery drains a bounded batch before the next poll`() = runBlocking {
        val dataSource = migratedDataSource("batch")
        val journal = JdbcMirkoriTelemetryJournal(dataSource)
        val startedAt = Instant.parse("2026-09-11T13:00:00Z")
        JdbcOnlineSessionRepository(
            dataSource = dataSource,
            cipher = OnlineStateCipher(ByteArray(32) { (it + 11).toByte() }),
            telemetryJournal = journal,
        ).use { repository ->
            repeat(3) {
                repository.create(
                    DurableOnlineSession(
                        sessionId = UUID.randomUUID().toString(),
                        revision = 0,
                        status = "ACTIVE",
                        stateJson = "{}",
                        createdAt = startedAt,
                        startedAt = startedAt,
                        finishedAt = null,
                        expiresAt = null,
                        telemetry = DurableMirkoriTelemetryProjection(setOf(UUID.randomUUID().toString()), null),
                    ),
                )
            }
        }
        val delivered = mutableListOf<String>()
        val worker = MirkoriTelemetryWorker(
            journal = journal,
            sender = MirkoriTelemetrySender { fact ->
                delivered += fact.eventId
                MirkoriTelemetryDeliveryReceipt(
                    decision = "accepted",
                    sessionStatus = "open",
                    trustedDurationSeconds = 0,
                )
            },
            clock = Clock.fixed(startedAt, ZoneOffset.UTC),
            deliveryBatchSize = 10,
        )

        assertEquals(3, worker.processAvailableBatch(startedAt))
        assertEquals(3, delivered.distinct().size)
        assertEquals(0, worker.processAvailableBatch(startedAt))
    }

    @Test
    fun `shutdown cancellation releases the active claim without consuming an attempt`() {
        val dataSource = migratedDataSource("shutdown")
        val journal = JdbcMirkoriTelemetryJournal(dataSource)
        val startedAt = Instant.parse("2026-09-11T14:00:00Z")
        JdbcOnlineSessionRepository(
            dataSource = dataSource,
            cipher = OnlineStateCipher(ByteArray(32) { (it + 17).toByte() }),
            telemetryJournal = journal,
        ).use { repository ->
            repository.create(
                DurableOnlineSession(
                    sessionId = UUID.randomUUID().toString(),
                    revision = 0,
                    status = "ACTIVE",
                    stateJson = "{}",
                    createdAt = startedAt,
                    startedAt = startedAt,
                    finishedAt = null,
                    expiresAt = null,
                    telemetry = DurableMirkoriTelemetryProjection(setOf(UUID.randomUUID().toString()), null),
                ),
            )
        }
        val senderStarted = CountDownLatch(1)
        val blockUntilInterrupted = CountDownLatch(1)
        val worker = MirkoriTelemetryWorker(
            journal = journal,
            sender = MirkoriTelemetrySender {
                senderStarted.countDown()
                try {
                    blockUntilInterrupted.await()
                    error("Telemetry sender unexpectedly resumed")
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw PlatformTransportException(PlatformTransportFailure.CANCELLED)
                }
            },
            clock = Clock.fixed(startedAt, ZoneOffset.UTC),
            pollInterval = Duration.ofHours(1),
        )

        worker.start()
        assertTrue(senderStarted.await(5, TimeUnit.SECONDS))
        worker.close()

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT status, attempt_count, claim_token, claimed_until FROM mirkori_telemetry_outbox",
            ).use { statement ->
                statement.executeQuery().use { results ->
                    assertTrue(results.next())
                    assertEquals("pending", results.getString("status"))
                    assertEquals(0, results.getInt("attempt_count"))
                    assertNull(results.getString("claim_token"))
                    assertNull(results.getObject("claimed_until"))
                    assertFalse(results.next())
                }
            }
        }
    }

    private fun migratedDataSource(name: String): DataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:mirkori-telemetry-$name-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    }.also { JdbcMigrationRunner().migrate(it) }

    private fun registerPlayers(dataSource: DataSource, vararg playerIds: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO players(id, display_name) VALUES (?, ?)").use { statement ->
                playerIds.forEachIndexed { index, playerId ->
                    statement.setString(1, playerId)
                    statement.setString(2, "Telemetry Player ${index + 1}")
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }
}

private class MutableTestClock(
    private var current: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableTestClock(current, zone)
    override fun instant(): Instant = current
    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}

private fun List<String>.singleDistinct(): String = distinct().single()
