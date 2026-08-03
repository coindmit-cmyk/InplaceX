package com.mirkori.inplacex.backend.identity

import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
import com.mirkori.inplacex.backend.persistence.JdbcSaveRepository
import com.mirkori.inplacex.backend.persistence.IdempotencyKeyReusedException
import com.mirkori.inplacex.logging.InplaceXLogger
import com.mirkori.inplacex.logging.LogLevel
import com.mirkori.inplacex.testsupport.RecordingLogSink
import org.h2.jdbcx.JdbcDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

class GuestIdentityServiceTest {
    @Test
    fun `bootstrap replay survives restart and rejects a changed payload`() {
        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
        val dataSource = testDataSource("bootstrap-replay")
        val command = bootstrap("installation-bootstrap-replay")
        val idempotencyKey = "00000000-0000-4000-8000-000000000020"
        val first = service(clock, dataSource = dataSource).bootstrap(command, idempotencyKey)
        val replayed = service(clock, dataSource = dataSource).bootstrap(command, idempotencyKey)

        assertEquals(first.playerId, replayed.playerId)
        assertEquals(first.credentials.accessToken, replayed.credentials.accessToken)
        assertEquals(first.credentials.refreshToken, replayed.credentials.refreshToken)
        assertEquals(first.credentials.accessExpiresAt, replayed.credentials.accessExpiresAt)
        assertEquals(first.credentials.refreshExpiresAt, replayed.credentials.refreshExpiresAt)
        assertThrows(IdempotencyKeyReusedException::class.java) {
            service(clock, dataSource = dataSource).bootstrap(
                command.copy(locale = "en"),
                idempotencyKey,
            )
        }
    }

    @Test
    fun `concurrent duplicate bootstrap creates one identity and credential family`() {
        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
        val dataSource = testDataSource("bootstrap-concurrent")
        val service = service(clock, dataSource = dataSource)
        val command = bootstrap("installation-bootstrap-concurrent")
        val idempotencyKey = "00000000-0000-4000-8000-000000000021"
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures = List(2) {
                executor.submit<GuestBootstrapResult> {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    service.bootstrap(command, idempotencyKey)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(results[0].playerId, results[1].playerId)
            assertEquals(results[0].credentials.accessToken, results[1].credentials.accessToken)
            assertEquals(results[0].credentials.refreshToken, results[1].credentials.refreshToken)
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    listOf(
                        "players",
                        "guest_installations",
                        "refresh_token_families",
                        "refresh_tokens",
                    ).forEach { table ->
                        statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                            result.next()
                            assertEquals("unexpected rows in $table", 1, result.getInt(1))
                        }
                    }
                    statement.executeQuery(
                        "SELECT COUNT(*) FROM auth_idempotency_results WHERE operation = 'bootstrap'",
                    ).use { result ->
                        result.next()
                        assertEquals(1, result.getInt(1))
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `bootstrap reuses guest identity and returns bounded renewable credentials`() {
        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
        val service = service(clock)

        val first = service.bootstrapForTest(bootstrap("installation-a"))
        val repeated = service.bootstrapForTest(bootstrap("installation-a"))

        assertEquals(first.playerId, repeated.playerId)
        assertEquals("guest", first.accountKind)
        assertEquals(clock.instant().plusSeconds(15 * 60), first.credentials.accessExpiresAt)
        assertEquals(clock.instant().plusSeconds(30L * 24 * 60 * 60), first.credentials.refreshExpiresAt)
        assertNotEquals(first.credentials.refreshToken, repeated.credentials.refreshToken)
        assertFalse(first.credentials.toString().contains(first.credentials.refreshToken))
        assertEquals(3, first.credentials.accessToken.split('.').size)
    }

    @Test
    fun `refresh replay returns the exact committed credentials after response loss`() {
        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
        val dataSource = testDataSource("refresh-replay")
        val service = service(clock, dataSource = dataSource)
        val bootstrap = service.bootstrapForTest(bootstrap("installation-b"))
        val idempotencyKey = "00000000-0000-4000-8000-000000000010"

        val rotated = service.refresh(bootstrap.credentials.refreshToken, idempotencyKey)
        val restartedService = service(clock, dataSource = dataSource)
        val replayed = restartedService.refresh(bootstrap.credentials.refreshToken, idempotencyKey)

        assertNotEquals(bootstrap.credentials.refreshToken, rotated.refreshToken)
        assertEquals(rotated.accessToken, replayed.accessToken)
        assertEquals(rotated.refreshToken, replayed.refreshToken)
        assertEquals(rotated.accessExpiresAt, replayed.accessExpiresAt)
        assertEquals(rotated.refreshExpiresAt, replayed.refreshExpiresAt)

        assertThrows(IdempotencyKeyReusedException::class.java) {
            service.refresh(rotated.refreshToken, idempotencyKey)
        }

        val next = service.refresh(
            rotated.refreshToken,
            "00000000-0000-4000-8000-000000000011",
        )
        assertNotEquals(rotated.refreshToken, next.refreshToken)

        assertThrows(RefreshTokenRejectedException::class.java) {
            service.refresh(
                bootstrap.credentials.refreshToken,
                "00000000-0000-4000-8000-000000000012",
            )
        }
    }

    @Test
    fun `concurrent duplicate refresh rotates and persists exactly once`() {
        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
        val dataSource = testDataSource("refresh-concurrent")
        val service = service(clock, dataSource = dataSource)
        val bootstrap = service.bootstrapForTest(bootstrap("installation-concurrent"))
        val idempotencyKey = "00000000-0000-4000-8000-000000000015"
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures = List(2) {
                executor.submit<RenewableCredentials> {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    service.refresh(bootstrap.credentials.refreshToken, idempotencyKey)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(results[0].accessToken, results[1].accessToken)
            assertEquals(results[0].refreshToken, results[1].refreshToken)
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT COUNT(*) FROM auth_idempotency_results WHERE operation = 'refresh'",
                    ).use { result ->
                        result.next()
                        assertEquals(1, result.getInt(1))
                    }
                    statement.executeQuery("SELECT COUNT(*) FROM refresh_tokens").use { result ->
                        result.next()
                        assertEquals(2, result.getInt(1))
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `expired refresh token is rejected`() {
        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
        val service = service(clock)
        val bootstrap = service.bootstrapForTest(bootstrap("installation-c"))
        clock.advanceSeconds(31L * 24 * 60 * 60)

        assertThrows(RefreshTokenRejectedException::class.java) {
            service.refresh(
                bootstrap.credentials.refreshToken,
                "00000000-0000-4000-8000-000000000013",
            )
        }
    }

    @Test
    fun `profile and cloud save return current snapshots on optimistic conflicts and replay saves`() {
        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
        val service = service(clock)
        val playerId = service.bootstrapForTest(bootstrap("installation-d")).playerId

        val changed = service.updateProfile(
            playerId,
            ProfileUpdateCommand(expectedRevision = 0, locale = "ru", regionHint = "RU"),
        ) as ProfileUpdateResult.Applied
        val profileConflict = service.updateProfile(
            playerId,
            ProfileUpdateCommand(expectedRevision = 0, locale = "en", regionHint = "US"),
        ) as ProfileUpdateResult.Conflict

        assertEquals(1, changed.profile.revision)
        assertEquals(1, profileConflict.current.revision)
        assertEquals("ru", profileConflict.current.locale)

        val command = CloudSavePutCommand(
            commandId = "00000000-0000-4000-8000-000000000001",
            expectedRevision = 0,
            saveSchemaVersion = 1,
            stateJson = "{\"progress\":7}",
        )
        val stored = service.putCloudSave(playerId, command) as CloudSaveWriteResult.Applied
        val replayed = service.putCloudSave(playerId, command) as CloudSaveWriteResult.Applied
        val conflict = service.putCloudSave(
            playerId,
            command.copy(commandId = "00000000-0000-4000-8000-000000000002", stateJson = "{\"progress\":8}"),
        ) as CloudSaveWriteResult.Conflict

        assertEquals(1, stored.snapshot.revision)
        assertTrue(replayed.replayed)
        assertEquals(1, replayed.snapshot.revision)
        assertEquals(0, conflict.expectedRevision)
        assertEquals(1, conflict.current.revision)
        assertEquals("{\"progress\":7}", conflict.current.stateJson)
    }

    @Test
    fun `identity operations never log raw installation tokens or save payloads`() {
        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
        val sink = RecordingLogSink()
        val service = service(clock, sink)
        val installation = "installation-private-marker"
        val bootstrap = service.bootstrapForTest(bootstrap(installation))
        service.refresh(
            bootstrap.credentials.refreshToken,
            "00000000-0000-4000-8000-000000000014",
        )
        service.putCloudSave(
            bootstrap.playerId,
            CloudSavePutCommand(
                commandId = "00000000-0000-4000-8000-000000000003",
                expectedRevision = 0,
                saveSchemaVersion = 1,
                stateJson = "{\"secretPayload\":\"private-marker\"}",
            ),
        )

        val renderedEvents = sink.events.joinToString()
        assertFalse(renderedEvents.contains(installation))
        assertFalse(renderedEvents.contains(bootstrap.credentials.accessToken))
        assertFalse(renderedEvents.contains(bootstrap.credentials.refreshToken))
        assertFalse(renderedEvents.contains("private-marker"))
    }

    @Test
    fun `Google identity links a guest once and restores the same player`() {
        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:google-identity-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        JdbcMigrationRunner().migrate(dataSource)
        val verifier = GoogleIdentityVerifier { idToken, expectedNonce ->
            if (idToken == "valid-google-token" && expectedNonce.isNotBlank()) {
                VerifiedGoogleIdentity(subject = "google-subject-1", displayName = "Verified Player")
            } else {
                null
            }
        }
        val service = service(clock, dataSource = dataSource, googleIdentityVerifier = verifier)
        val firstGuest = service.bootstrapForTest(bootstrap("google-installation-a"))
        val firstChallenge = service.createGoogleChallenge(firstGuest.playerId)

        val linked = service.authenticateWithGoogle(
            currentPlayerId = firstGuest.playerId,
            idToken = "valid-google-token",
            nonce = firstChallenge.nonce,
        )

        assertEquals(firstGuest.playerId, linked.playerId)
        assertEquals("google", linked.accountKind)
        assertThrows(GoogleIdentityRejectedException::class.java) {
            service.authenticateWithGoogle(
                currentPlayerId = firstGuest.playerId,
                idToken = "valid-google-token",
                nonce = firstChallenge.nonce,
            )
        }

        val secondGuest = service.bootstrapForTest(bootstrap("google-installation-b"))
        val secondChallenge = service.createGoogleChallenge(secondGuest.playerId)
        val restored = service.authenticateWithGoogle(
            currentPlayerId = secondGuest.playerId,
            idToken = "valid-google-token",
            nonce = secondChallenge.nonce,
        )

        assertEquals(firstGuest.playerId, restored.playerId)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM player_identities").use { result ->
                    result.next()
                    assertEquals(1, result.getInt(1))
                }
                statement.executeQuery(
                    "SELECT account_kind, display_name FROM players WHERE id = '${firstGuest.playerId}'",
                ).use { result ->
                    result.next()
                    assertEquals("google", result.getString("account_kind"))
                    assertEquals("Verified Player", result.getString("display_name"))
                }
            }
        }
    }

    private fun bootstrap(installationId: String) = GuestBootstrapCommand(
        installationId = installationId,
        platform = GuestPlatform.ANDROID,
        appVersion = "1.0.0",
        locale = "ru",
        regionHint = "RU",
    )

    private fun GuestIdentityService.bootstrapForTest(command: GuestBootstrapCommand): GuestBootstrapResult =
        bootstrap(command, java.util.UUID.randomUUID().toString())

    private fun service(
        clock: Clock,
        sink: RecordingLogSink = RecordingLogSink(),
        dataSource: DataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:identity-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        },
        googleIdentityVerifier: GoogleIdentityVerifier? = null,
    ): GuestIdentityService {
        JdbcMigrationRunner().migrate(dataSource)
        return GuestIdentityService(
            identities = JdbcGuestIdentityRepository(dataSource),
            saves = JdbcSaveRepository(dataSource),
            policy = CredentialPolicy(issuer = "inplacex-test", audience = "inplacex-client"),
            accessTokenIssuer = AccessTokenIssuer { playerId, issuedAt, expiresAt ->
                "test.${playerId}:${issuedAt.epochSecond}:${expiresAt.epochSecond}.signature"
            },
            googleIdentityVerifier = googleIdentityVerifier,
            clock = clock,
            logger = InplaceXLogger(sink, LogLevel.DEBUG),
        )
    }

    private fun testDataSource(name: String): JdbcDataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:$name-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        user = "sa"
        password = ""
    }
}

private class MutableClock(private var current: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId): Clock = this
    override fun instant(): Instant = current
    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }
}
