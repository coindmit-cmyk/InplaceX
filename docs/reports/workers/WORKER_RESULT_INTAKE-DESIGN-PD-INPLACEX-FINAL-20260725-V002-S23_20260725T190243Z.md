# Worker Result INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S23

- Generated: `2026-07-25T19:02:43Z`
- Worker: `auto-worker-5.5`
- Model: `gpt-5.6-terra`
- Branch: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5/intake-design-pd-inplacex-final-20260725-v002-s23/guest-identity-profile-and-versioned-cloud-save`
- Worktree: `/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5/intake-design-pd-inplacex-final-20260725-v002-s23-20260725T185458Z`
- Result: `agent_done`
- Check evidence: `passed`
- Next owner: `Integrator`

## Check Evidence

```json
{
  "ok": true,
  "route": "agent_done",
  "check_status": "passed",
  "integration_status": "pending",
  "reason": "worker output contains explicit passed check_status",
  "negative_markers": [],
  "positive_markers": [
    "passed",
    "git diff --check",
    "check_status=passed"
  ]
}
```

## stdout

```text
Готово: добавлены guest identity, ротация refresh-токенов, ревизионный профиль и идемпотентные cloud saves в `InplaceX-backend`.

Отчёт: [worker result](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.5/intake-design-pd-inplacex-final-20260725-v002-s23-20260725T185458Z/docs/reports/workers/WORKER_RESULT_INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S23_20260725T190149Z.md).

Проверки:

- `bash gradlew :InplaceX-backend:test` — passed, 25 тестов.
- `git diff --check` — passed.
- `git diff --check --no-index /dev/null <new-file>` для новых файлов — passed.

`check_status=passed`

Очередь и события `AiStudio/Task_manager` не изменялись: они принадлежат central runner по ограничениям пакета.

```

## stderr

```text
 KEY,
+    family_id VARCHAR(64) NOT NULL REFERENCES refresh_token_families(id),
+    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
+    consumed_at TIMESTAMP WITH TIME ZONE
+);
+
+CREATE TABLE save_commands (
+    player_id VARCHAR(64) NOT NULL REFERENCES players(id),
+    command_id VARCHAR(64) NOT NULL,
+    fingerprint VARCHAR(64) NOT NULL,
+    revision BIGINT NOT NULL,
+    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
+    PRIMARY KEY (player_id, command_id)
+);
+
+CREATE INDEX idx_guest_installations_player ON guest_installations(player_id);
+CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id);
diff --git a/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/identity/GuestIdentityServiceTest.kt b/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/identity/GuestIdentityServiceTest.kt
new file mode 100644
index 0000000000000000000000000000000000000000..a4a52fc84182c25c18c7f7d2ee2e75f972fe699a
--- /dev/null
+++ b/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/identity/GuestIdentityServiceTest.kt
@@ -0,0 +1,165 @@
+package com.mirkori.inplacex.backend.identity
+
+import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
+import com.mirkori.inplacex.backend.persistence.JdbcSaveRepository
+import com.mirkori.inplacex.logging.InplaceXLogger
+import com.mirkori.inplacex.logging.LogLevel
+import com.mirkori.inplacex.testsupport.RecordingLogSink
+import org.h2.jdbcx.JdbcDataSource
+import org.junit.Assert.assertEquals
+import org.junit.Assert.assertFalse
+import org.junit.Assert.assertNotEquals
+import org.junit.Assert.assertThrows
+import org.junit.Assert.assertTrue
+import org.junit.Test
+import java.time.Clock
+import java.time.Instant
+import java.time.ZoneOffset
+import javax.sql.DataSource
+
+class GuestIdentityServiceTest {
+    @Test
+    fun `bootstrap reuses guest identity and returns bounded renewable credentials`() {
+        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
+        val service = service(clock)
+
+        val first = service.bootstrap(bootstrap("installation-a"))
+        val repeated = service.bootstrap(bootstrap("installation-a"))
+
+        assertEquals(first.playerId, repeated.playerId)
+        assertEquals("guest", first.accountKind)
+        assertEquals(clock.instant().plusSeconds(15 * 60), first.credentials.accessExpiresAt)
+        assertEquals(clock.instant().plusSeconds(30L * 24 * 60 * 60), first.credentials.refreshExpiresAt)
+        assertNotEquals(first.credentials.refreshToken, repeated.credentials.refreshToken)
+        assertFalse(first.credentials.toString().contains(first.credentials.refreshToken))
+        assertEquals(3, first.credentials.accessToken.split('.').size)
+    }
+
+    @Test
+    fun `refresh rotates once and replay revokes the entire token family`() {
+        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
+        val service = service(clock)
+        val bootstrap = service.bootstrap(bootstrap("installation-b"))
+
+        val rotated = service.refresh(bootstrap.credentials.refreshToken)
+
+        assertNotEquals(bootstrap.credentials.refreshToken, rotated.refreshToken)
+        assertThrows(RefreshTokenRejectedException::class.java) {
+            service.refresh(bootstrap.credentials.refreshToken)
+        }
+        assertThrows(RefreshTokenRejectedException::class.java) {
+            service.refresh(rotated.refreshToken)
+        }
+    }
+
+    @Test
+    fun `expired refresh token is rejected`() {
+        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
+        val service = service(clock)
+        val bootstrap = service.bootstrap(bootstrap("installation-c"))
+        clock.advanceSeconds(31L * 24 * 60 * 60)
+
+        assertThrows(RefreshTokenRejectedException::class.java) {
+            service.refresh(bootstrap.credentials.refreshToken)
+        }
+    }
+
+    @Test
+    fun `profile and cloud save return current snapshots on optimistic conflicts and replay saves`() {
+        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
+        val service = service(clock)
+        val playerId = service.bootstrap(bootstrap("installation-d")).playerId
+
+        val changed = service.updateProfile(
+            playerId,
+            ProfileUpdateCommand(expectedRevision = 0, locale = "ru", regionHint = "RU"),
+        ) as ProfileUpdateResult.Applied
+        val profileConflict = service.updateProfile(
+            playerId,
+            ProfileUpdateCommand(expectedRevision = 0, locale = "en", regionHint = "US"),
+        ) as ProfileUpdateResult.Conflict
+
+        assertEquals(1, changed.profile.revision)
+        assertEquals(1, profileConflict.current.revision)
+        assertEquals("ru", profileConflict.current.locale)
+
+        val command = CloudSavePutCommand(
+            commandId = "00000000-0000-4000-8000-000000000001",
+            expectedRevision = 0,
+            saveSchemaVersion = 1,
+            stateJson = "{\"progress\":7}",
+        )
+        val stored = service.putCloudSave(playerId, command) as CloudSaveWriteResult.Applied
+        val replayed = service.putCloudSave(playerId, command) as CloudSaveWriteResult.Applied
+        val conflict = service.putCloudSave(
+            playerId,
+            command.copy(commandId = "00000000-0000-4000-8000-000000000002", stateJson = "{\"progress\":8}"),
+        ) as CloudSaveWriteResult.Conflict
+
+        assertEquals(1, stored.snapshot.revision)
+        assertTrue(replayed.replayed)
+        assertEquals(1, replayed.snapshot.revision)
+        assertEquals(0, conflict.expectedRevision)
+        assertEquals(1, conflict.current.revision)
+        assertEquals("{\"progress\":7}", conflict.current.stateJson)
+    }
+
+    @Test
+    fun `identity operations never log raw installation tokens or save payloads`() {
+        val clock = MutableClock(Instant.parse("2026-07-25T12:00:00Z"))
+        val sink = RecordingLogSink()
+        val service = service(clock, sink)
+        val installation = "installation-private-marker"
+        val bootstrap = service.bootstrap(bootstrap(installation))
+        service.refresh(bootstrap.credentials.refreshToken)
+        service.putCloudSave(
+            bootstrap.playerId,
+            CloudSavePutCommand(
+                commandId = "00000000-0000-4000-8000-000000000003",
+                expectedRevision = 0,
+                saveSchemaVersion = 1,
+                stateJson = "{\"secretPayload\":\"private-marker\"}",
+            ),
+        )
+
+        val renderedEvents = sink.events.joinToString()
+        assertFalse(renderedEvents.contains(installation))
+        assertFalse(renderedEvents.contains(bootstrap.credentials.accessToken))
+        assertFalse(renderedEvents.contains(bootstrap.credentials.refreshToken))
+        assertFalse(renderedEvents.contains("private-marker"))
+    }
+
+    private fun bootstrap(installationId: String) = GuestBootstrapCommand(
+        installationId = installationId,
+        platform = GuestPlatform.ANDROID,
+        appVersion = "1.0.0",
+        locale = "ru",
+        regionHint = "RU",
+    )
+
+    private fun service(clock: Clock, sink: RecordingLogSink = RecordingLogSink()): GuestIdentityService {
+        val dataSource = JdbcDataSource().apply {
+            setURL("jdbc:h2:mem:identity-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
+            user = "sa"
+            password = ""
+        }
+        JdbcMigrationRunner().migrate(dataSource)
+        return GuestIdentityService(
+            identities = JdbcGuestIdentityRepository(dataSource),
+            saves = JdbcSaveRepository(dataSource),
+            policy = CredentialPolicy(issuer = "inplacex-test", audience = "inplacex-client"),
+            signingSecret = ByteArray(32) { 7 },
+            clock = clock,
+            logger = InplaceXLogger(sink, LogLevel.DEBUG),
+        )
+    }
+}
+
+private class MutableClock(private var current: Instant) : Clock() {
+    override fun getZone() = ZoneOffset.UTC
+    override fun withZone(zone: java.time.ZoneId): Clock = this
+    override fun instant(): Instant = current
+    fun advanceSeconds(seconds: Long) {
+        current = current.plusSeconds(seconds)
+    }
+}
diff --git a/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/persistence/JdbcPersistenceTest.kt b/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/persistence/JdbcPersistenceTest.kt
index 430dd44898b44d0a0021b2164b20202485460cfd..2c8bf8728ca132f75c65222f56f9a31db0f0dded
--- a/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/persistence/JdbcPersistenceTest.kt
+++ b/InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/persistence/JdbcPersistenceTest.kt
@@ -42,7 +42,7 @@
                     )
                 },
             )
-            assertEquals(1, connection.createStatement().use { statement ->
+            assertEquals(2, connection.createStatement().use { statement ->
                 statement.executeQuery("SELECT COUNT(*) FROM inplacex_schema_history").use { resultSet ->
                     resultSet.next()
                     resultSet.getInt(1)
@@ -56,7 +56,7 @@
         val dataSource = newDataSource()
         JdbcMigrationRunner().migrate(dataSource)
         val failingMigration = SqlMigration(
-            version = "2",
+            version = "3",
             description = "rollback test",
             sql = "INSERT INTO players(id, display_name) VALUES ('rolled-back', 'Rollback'); INSERT INTO absent_table VALUES (1)",
         )
@@ -67,7 +67,7 @@

         dataSource.connection.use { connection ->
             assertEquals(0, count(connection, "SELECT COUNT(*) FROM players WHERE id = 'rolled-back'"))
-            assertEquals(0, count(connection, "SELECT COUNT(*) FROM inplacex_schema_history WHERE version = '2'"))
+            assertEquals(0, count(connection, "SELECT COUNT(*) FROM inplacex_schema_history WHERE version = '3'"))
         }
     }

diff --git a/docs/reports/workers/WORKER_RESULT_INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S23_20260725T190149Z.md b/docs/reports/workers/WORKER_RESULT_INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S23_20260725T190149Z.md
new file mode 100644
index 0000000000000000000000000000000000000000..34784695b136ab24df8637fe789f84a291586829
--- /dev/null
+++ b/docs/reports/workers/WORKER_RESULT_INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S23_20260725T190149Z.md
@@ -0,0 +1,29 @@
+# Worker result: INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S23
+
+Status: `integration_requested`
+
+## Delivered
+
+- Added migration `V2` for guest-installation hashes, revisioned player profiles,
+  refresh-token families, hashed refresh tokens, and idempotent cloud-save commands.
+- Added a transport-neutral identity/save service with stable guest bootstrap,
+  signed short-lived access tokens, bounded rotating refresh tokens, refresh-family
+  revocation on replay, profile revision conflicts, and idempotent cloud-save writes.
+- Added tests for bootstrap replay, refresh replay and expiry, revision conflicts,
+  save retry, and absence of raw installation IDs, tokens, and save payloads from logs.
+
+## Verification
+
+| Command | Result |
+| --- | --- |
+| `bash gradlew :InplaceX-backend:test` | passed — 25 tests completed successfully |
+| `git diff --check` | passed |
+| `git diff --check --no-index /dev/null <each new file>` | passed — no whitespace errors in untracked additions |
+
+## Integration notes
+
+- The service is intentionally transport-neutral. The later REST/WebSocket route
+  task must supply the signing secret from managed runtime configuration and map
+  `ProfileUpdateResult` / `CloudSaveWriteResult` to the v1 HTTP responses.
+- No `AiStudio/Task_manager` state or event was edited: this worker packet and
+  repository instructions reserve those runner-owned files for central automation.

tokens used
109 863

```
