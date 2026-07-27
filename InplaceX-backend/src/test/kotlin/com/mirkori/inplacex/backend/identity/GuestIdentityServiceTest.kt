package com.mirkori.inplacex.backend.identity

import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
import com.mirkori.inplacex.backend.persistence.JdbcSaveRepository
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
import javax.sql.DataSource

class GuestIdentityServiceTest {
    @Test
    fun `bootstrap reuses guest identity and returns bounded renewable credentials`() {
        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
        val service = service(clock)

        val first = service.bootstrap(bootstrap("installation-a"))
        val repeated = service.bootstrap(bootstrap("installation-a"))

        assertEquals(first.playerId, repeated.playerId)
        assertEquals("guest", first.accountKind)
        assertEquals(clock.instant().plusSeconds(15 * 60), first.credentials.accessExpiresAt)
        assertEquals(clock.instant().plusSeconds(30L * 24 * 60 * 60), first.credentials.refreshExpiresAt)
        assertNotEquals(first.credentials.refreshToken, repeated.credentials.refreshToken)
        assertFalse(first.credentials.toString().contains(first.credentials.refreshToken))
        assertEquals(3, first.credentials.accessToken.split('.').size)
    }

    @Test
    fun `refresh rotates once and replay revokes the entire token family`() {
        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
        val service = service(clock)
        val bootstrap = service.bootstrap(bootstrap("installation-b"))

        val rotated = service.refresh(bootstrap.credentials.refreshToken)

        assertNotEquals(bootstrap.credentials.refreshToken, rotated.refreshToken)
        assertThrows(RefreshTokenRejectedException::class.java) {
            service.refresh(bootstrap.credentials.refreshToken)
        }
        assertThrows(RefreshTokenRejectedException::class.java) {
            service.refresh(rotated.refreshToken)
        }
    }

    @Test
    fun `expired refresh token is rejected`() {
        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
        val service = service(clock)
        val bootstrap = service.bootstrap(bootstrap("installation-c"))
        clock.advanceSeconds(31L * 24 * 60 * 60)

        assertThrows(RefreshTokenRejectedException::class.java) {
            service.refresh(bootstrap.credentials.refreshToken)
        }
    }

    @Test
    fun `profile and cloud save return current snapshots on optimistic conflicts and replay saves`() {
        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
        val service = service(clock)
        val playerId = service.bootstrap(bootstrap("installation-d")).playerId

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
        val bootstrap = service.bootstrap(bootstrap(installation))
        service.refresh(bootstrap.credentials.refreshToken)
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

    private fun bootstrap(installationId: String) = GuestBootstrapCommand(
        installationId = installationId,
        platform = GuestPlatform.ANDROID,
        appVersion = "1.0.0",
        locale = "ru",
        regionHint = "RU",
    )

    private fun service(clock: Clock, sink: RecordingLogSink = RecordingLogSink()): GuestIdentityService {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:identity-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        JdbcMigrationRunner().migrate(dataSource)
        return GuestIdentityService(
            identities = JdbcGuestIdentityRepository(dataSource),
            saves = JdbcSaveRepository(dataSource),
            policy = CredentialPolicy(issuer = "inplacex-test", audience = "inplacex-client"),
            accessTokenIssuer = AccessTokenIssuer { playerId, issuedAt, expiresAt ->
                "test.${playerId}:${issuedAt.epochSecond}:${expiresAt.epochSecond}.signature"
            },
            clock = clock,
            logger = InplaceXLogger(sink, LogLevel.DEBUG),
        )
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
