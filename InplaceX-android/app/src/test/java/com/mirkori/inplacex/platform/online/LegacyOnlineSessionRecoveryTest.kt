package com.mirkori.inplacex.platform.online

import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyOnlineSessionRecoveryTest {
    private val sessionId = UUID.randomUUID().toString()

    @Test
    fun `response loss preserves proof and next platform read completes migration`() = runBlocking {
        val store = RecordingLegacyStore(legacySession())
        val attemptStore = RecordingAttemptStore()
        val boundary = QueueTransportBoundary(
            failure(403),
            RemoteCallResult.NetworkFailure("migration_response_lost"),
            snapshotSuccess(),
        )
        val recovery = LegacyOnlineSessionRecovery(OnlineDuelClient(boundary), store, attemptStore)

        assertEquals(OnlineClientResult.TemporarilyUnavailable, recovery.readSession(sessionId))
        assertFalse(store.cleared)
        val pending = requireNotNull(attemptStore.read())
        assertEquals(sessionId, pending.sessionId)
        assertEquals(pending.commandId, migrationCommandId(boundary.requests[1]))

        val recovered = LegacyOnlineSessionRecovery(
            OnlineDuelClient(boundary),
            store,
            attemptStore,
        ).readSession(sessionId)

        assertTrue(recovered is OnlineClientResult.Success)
        assertTrue(store.cleared)
        assertTrue(attemptStore.cleared)
        assertEquals(
            listOf("session.read", "session.legacy.migrate", "session.read"),
            boundary.requests.map(RemoteRequestSpec::operation),
        )
        assertMigrationRequestIsRedacted(boundary.requests[1])
    }

    @Test
    fun `lost confirmation preserves proof until a later platform read succeeds`() = runBlocking {
        val store = RecordingLegacyStore(legacySession())
        val attemptStore = RecordingAttemptStore()
        val boundary = QueueTransportBoundary(
            failure(403),
            migrationSuccess(),
            RemoteCallResult.NetworkFailure("confirmation_response_lost"),
            snapshotSuccess(),
        )
        val recovery = LegacyOnlineSessionRecovery(OnlineDuelClient(boundary), store, attemptStore)

        assertEquals(OnlineClientResult.TemporarilyUnavailable, recovery.readSession(sessionId))
        assertFalse(store.cleared)
        assertTrue(attemptStore.read() != null)
        val recovered = LegacyOnlineSessionRecovery(
            OnlineDuelClient(boundary),
            store,
            attemptStore,
        ).readSession(sessionId)

        assertTrue(recovered is OnlineClientResult.Success)
        assertTrue(store.cleared)
        assertTrue(attemptStore.cleared)
        assertEquals(
            listOf("session.read", "session.legacy.migrate", "session.read", "session.read"),
            boundary.requests.map(RemoteRequestSpec::operation),
        )
        assertMigrationRequestIsRedacted(boundary.requests[1])
    }

    @Test
    fun `recreated runtime retries an uncommitted request with the same command`() = runBlocking {
        val store = RecordingLegacyStore(legacySession())
        val attemptStore = RecordingAttemptStore()
        val boundary = QueueTransportBoundary(
            failure(403),
            RemoteCallResult.NetworkFailure("migration_request_lost"),
            failure(403),
            migrationSuccess(),
            snapshotSuccess(),
        )

        assertEquals(
            OnlineClientResult.TemporarilyUnavailable,
            LegacyOnlineSessionRecovery(
                OnlineDuelClient(boundary),
                store,
                attemptStore,
            ).readSession(sessionId),
        )
        val originalCommandId = migrationCommandId(boundary.requests[1])

        val recovered = LegacyOnlineSessionRecovery(
            OnlineDuelClient(boundary),
            store,
            attemptStore,
        ).readSession(sessionId)

        assertTrue(recovered is OnlineClientResult.Success)
        assertEquals(originalCommandId, migrationCommandId(boundary.requests[3]))
        assertTrue(store.cleared)
        assertTrue(attemptStore.cleared)
    }

    @Test
    fun `temporary migration outage preserves active proof`() = runBlocking {
        val store = RecordingLegacyStore(legacySession())
        val attemptStore = RecordingAttemptStore()
        val recovery = LegacyOnlineSessionRecovery(
            OnlineDuelClient(
                QueueTransportBoundary(
                    failure(403),
                    RemoteCallResult.AccessTokenTemporarilyUnavailable,
                ),
            ),
            store,
            attemptStore,
        )

        assertEquals(OnlineClientResult.TemporarilyUnavailable, recovery.readSession(sessionId))
        assertFalse(store.cleared)
        assertTrue(store.read() != null)
        assertEquals(sessionId, attemptStore.read()?.sessionId)
    }

    @Test
    fun `authoritatively rejected legacy proof is deleted`() = runBlocking {
        val store = RecordingLegacyStore(legacySession())
        val attemptStore = RecordingAttemptStore()
        val recovery = LegacyOnlineSessionRecovery(
            OnlineDuelClient(QueueTransportBoundary(failure(403), failure(403))),
            store,
            attemptStore,
        )

        assertEquals(OnlineClientResult.MembershipRejected, recovery.readSession(sessionId))
        assertTrue(store.cleared)
        assertTrue(store.read() == null)
        assertTrue(attemptStore.cleared)
        assertTrue(attemptStore.read() == null)
    }

    @Test
    fun `successful unrelated platform session does not delete legacy proof`() = runBlocking {
        val store = RecordingLegacyStore(legacySession())
        val oldSessionId = UUID.randomUUID().toString()
        val pending = LegacyMembershipMigrationAttempt(oldSessionId, UUID.randomUUID().toString())
        val attemptStore = RecordingAttemptStore(pending)
        val recovery = LegacyOnlineSessionRecovery(
            OnlineDuelClient(QueueTransportBoundary(snapshotSuccess())),
            store,
            attemptStore,
        )

        assertTrue(recovery.readSession(sessionId) is OnlineClientResult.Success)
        assertFalse(store.cleared)
        assertTrue(store.read() != null)
        assertEquals(pending, attemptStore.read())
    }

    @Test
    fun `successful platform session without pending migration preserves old proof`() = runBlocking {
        val store = RecordingLegacyStore(legacySession())
        val attemptStore = RecordingAttemptStore()
        val recovery = LegacyOnlineSessionRecovery(
            OnlineDuelClient(QueueTransportBoundary(snapshotSuccess())),
            store,
            attemptStore,
        )

        assertTrue(recovery.readSession(sessionId) is OnlineClientResult.Success)
        assertFalse(store.cleared)
        assertTrue(store.read() != null)
        assertTrue(attemptStore.read() == null)
    }

    @Test
    fun `pending migration for another session never sends proof`() = runBlocking {
        val store = RecordingLegacyStore(legacySession())
        val pending = LegacyMembershipMigrationAttempt(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
        )
        val attemptStore = RecordingAttemptStore(pending)
        val boundary = QueueTransportBoundary(failure(403))
        val recovery = LegacyOnlineSessionRecovery(OnlineDuelClient(boundary), store, attemptStore)

        assertEquals(OnlineClientResult.MembershipRejected, recovery.readSession(sessionId))
        assertEquals(listOf("session.read"), boundary.requests.map(RemoteRequestSpec::operation))
        assertFalse(store.cleared)
        assertEquals(pending, attemptStore.read())
    }

    private fun assertMigrationRequestIsRedacted(migration: RemoteRequestSpec) {
        val body = Json.parseToJsonElement(requireNotNull(migration.bodyJson)).jsonObject
        assertEquals(body.getValue("commandId").jsonPrimitive.content, migration.idempotencyKey)
        assertEquals(LegacyRefreshToken, body.getValue("legacyRefreshToken").jsonPrimitive.content)
        assertFalse(migration.toString().contains(LegacyRefreshToken))
    }

    private fun migrationCommandId(migration: RemoteRequestSpec): String =
        Json.parseToJsonElement(requireNotNull(migration.bodyJson))
            .jsonObject.getValue("commandId").jsonPrimitive.content

    private fun legacySession() = GuestSession(
        playerId = UUID.randomUUID().toString(),
        accessToken = "legacy.access",
        refreshToken = LegacyRefreshToken,
        accessExpiresAtEpochMs = 1,
        refreshExpiresAtEpochMs = Long.MAX_VALUE,
    )

    private fun migrationSuccess() = RemoteCallResult.Success(
        RemoteResponse(
            statusCode = 200,
            headers = emptyMap(),
            body = """{"sessionId":"$sessionId","status":"migrated"}""",
        ),
    )

    private fun snapshotSuccess() = RemoteCallResult.Success(
        RemoteResponse(200, emptyMap(), snapshot(sessionId)),
    )

    private fun failure(status: Int) = RemoteCallResult.HttpFailure(
        RemoteResponse(status, emptyMap(), """{"error":"failure"}"""),
    )

    private companion object {
        val LegacyRefreshToken = "legacy-${"r".repeat(43)}"
    }
}

private class RecordingLegacyStore(initial: GuestSession?) : SecureGuestSessionStore {
    private var value = initial
    var cleared = false
        private set

    override fun read(): GuestSession? = value

    override fun write(session: GuestSession) {
        value = session
    }

    override fun clear() {
        cleared = true
        value = null
    }
}

private class RecordingAttemptStore(
    initial: LegacyMembershipMigrationAttempt? = null,
) : LegacyMembershipMigrationAttemptStore {
    private var value = initial
    var cleared = false
        private set

    override fun read(): LegacyMembershipMigrationAttempt? = value

    override fun write(attempt: LegacyMembershipMigrationAttempt) {
        value = attempt
    }

    override fun clear() {
        cleared = true
        value = null
    }
}

private class QueueTransportBoundary(vararg results: RemoteCallResult) : TransportBoundary {
    private val results = results.toMutableList()
    val requests = mutableListOf<RemoteRequestSpec>()

    override suspend fun execute(request: RemoteRequestSpec): RemoteCallResult {
        requests += request
        return results.removeFirst()
    }

    override suspend fun openSession(request: RemoteWebSocketSpec): OnlineSessionOpenResult =
        OnlineSessionOpenResult.Offline
}

private fun snapshot(sessionId: String) = """
    {
      "sessionId":"$sessionId",
      "revision":3,
      "phase":"active",
      "currentTurn":"player",
      "winner":null,
      "finishReason":null,
      "playStyle":"turn_based",
      "codeLength":4,
      "attemptLimit":9,
      "allowDuplicates":false,
      "maxConsecutiveDuplicateDigits":null,
      "startedAtEpochMs":1000,
      "deadlineAtEpochMs":601000,
      "serverTimeEpochMs":2000,
      "attempts":[],
      "participants":[
        {"actor":"player","secretConfigured":true,"attemptsUsed":0,"attemptsLeft":9},
        {"actor":"opponent","secretConfigured":true,"attemptsUsed":0,"attemptsLeft":9}
      ]
    }
""".trimIndent()
