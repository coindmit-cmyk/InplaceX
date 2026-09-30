# Worker Result CRB-S25-PREREQ-A-AUTH-PRINCIPAL-MEMBERSHIP

- Generated: `2026-07-26T12:23:58Z`
- Worker: `auto-worker-5.5`
- Model: `gpt-5.6-terra`
- Branch: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership/retry-s25-prerequisite-a-unforgeable-jvm-princip-retry-20260726T122225Z`
- Worktree: `/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5/crb-s25-prereq-a-auth-principal-membership-20260726T122227Z`
- Result: `needs_worker_fix`
- Check evidence: `blocked`
- Next owner: `worker`

## Check Evidence

```json
{
  "ok": false,
  "route": "needs_worker_fix",
  "check_status": "blocked",
  "integration_status": "needs_worker_fix",
  "reason": "worker explicitly reported required checks as blocked",
  "negative_markers": [],
  "positive_markers": [
    "git diff --check"
  ],
  "explicit_check_statuses": [
    "blocked"
  ]
}
```

## stdout

```text
Работа заблокирована до внесения изменений: в активном Task_manager-пакете отсутствует runner lease (`lease`, `lease_id`, `lease_expires_at` — `null`), хотя lock активен. По обязательному Worker Protocol это не даёт права на запись даже при наличии lock.

Проверено:

- `python3 scripts/agent_control/github_freshness_guard.py --project-root . --base-ref origin/develop --fetch --json` — `failed`: локальная неизменяемая база отстаёт на один коммит.
- `git diff --name-status 2219bd...origin/develop` — изменены только `AiStudio/Task_manager/agent_locks.json` и `task_queue.json`; это допустимый state-only drift.
- Проверены пакет, lock, process state и все обязательные S25 review-документы.
- `git diff --check`, `bash gradlew :InplaceX-backend:test --rerun-tasks`, `bash gradlew verifyProject` — `not_run`, поскольку запуск реализации и создание отчёта запрещены до выдачи валидного lease.

Итог: `check_status=blocked`. Нужен Dispatcher/central runner: добавить действующий lease, связанный с этим task id, branch и worktree, затем перезапустить worker.

```

## stderr

```text
 ProfileUpdateCommand): PlayerProfile? = dataSource.transaction { connection ->
        val changed = connection.prepareStatement(
            """
            UPDATE player_profiles
            SET locale = ?, region_hint = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
            WHERE player_id = ? AND revision = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, command.locale)
            statement.setString(2, command.regionHint)
            statement.setString(3, playerId)
            statement.setLong(4, command.expectedRevision)
            statement.executeUpdate()
        }
        if (changed != 1) return@transaction null
        profile(connection, playerId)
    }

    private fun findPlayerId(connection: Connection, installationHash: String): String? = connection.prepareStatement(
        "SELECT player_id FROM guest_installations WHERE installation_hash = ?",
    ).use { statement ->
        statement.setString(1, installationHash)
        statement.executeQuery().use { resultSet -> if (resultSet.next()) resultSet.getString("player_id") else null }
    }

    private fun tokenRecord(connection: Connection, tokenHash: String): RefreshTokenRecord? = connection.prepareStatement(
        """
        SELECT families.id, families.player_id, families.expires_at AS family_expires_at, families.revoked_at,
               tokens.expires_at AS token_expires_at
        FROM refresh_tokens tokens
        JOIN refresh_token_families families ON families.id = tokens.family_id
        WHERE tokens.token_hash = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, tokenHash)
        statement.executeQuery().use { resultSet ->
            if (!resultSet.next()) return@use null
            RefreshTokenRecord(
                familyId = resultSet.getString("id"),
                playerId = resultSet.getString("player_id"),
                familyExpiresAt = resultSet.instant("family_expires_at"),
                tokenExpiresAt = resultSet.instant("token_expires_at"),
                revokedAt = resultSet.getObject("revoked_at", java.time.OffsetDateTime::class.java)?.toInstant(),
            )
        }
    }

    private fun revokeFamily(connection: Connection, familyId: String, now: Instant) {
        connection.prepareStatement(
            "UPDATE refresh_token_families SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
        ).use { statement ->
            statement.setObject(1, now)
            statement.setString(2, familyId)
            statement.executeUpdate()
        }
    }

    private fun profile(connection: Connection, playerId: String): PlayerProfile = connection.prepareStatement(
        """
        SELECT players.display_name, profiles.locale, profiles.region_hint, profiles.revision, profiles.updated_at
        FROM players JOIN player_profiles profiles ON profiles.player_id = players.id

--- InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/session/authorization/SessionMembershipResolver.kt ---
sed: can't read InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/session/authorization/SessionMembershipResolver.kt: No such file or directory

--- tests ---

--- InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/auth/JwtAccessTokenServiceTest.kt ---
sed: can't read InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/auth/JwtAccessTokenServiceTest.kt: No such file or directory

--- InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/identity/GuestIdentityServiceTest.kt ---
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
            signingSecret = ByteArray(32) { 7 },
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

--- InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/session/authorization/SessionMembershipResolverTest.kt ---
sed: can't read InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/session/authorization/SessionMembershipResolverTest.kt: No such file or directory

codex
Работа заблокирована до внесения изменений: в активном Task_manager-пакете отсутствует runner lease (`lease`, `lease_id`, `lease_expires_at` — `null`), хотя lock активен. По обязательному Worker Protocol это не даёт права на запись даже при наличии lock.

Проверено:

- `python3 scripts/agent_control/github_freshness_guard.py --project-root . --base-ref origin/develop --fetch --json` — `failed`: локальная неизменяемая база отстаёт на один коммит.
- `git diff --name-status 2219bd...origin/develop` — изменены только `AiStudio/Task_manager/agent_locks.json` и `task_queue.json`; это допустимый state-only drift.
- Проверены пакет, lock, process state и все обязательные S25 review-документы.
- `git diff --check`, `bash gradlew :InplaceX-backend:test --rerun-tasks`, `bash gradlew verifyProject` — `not_run`, поскольку запуск реализации и создание отчёта запрещены до выдачи валидного lease.

Итог: `check_status=blocked`. Нужен Dispatcher/central runner: добавить действующий lease, связанный с этим task id, branch и worktree, затем перезапустить worker.
tokens used
57 597

```
