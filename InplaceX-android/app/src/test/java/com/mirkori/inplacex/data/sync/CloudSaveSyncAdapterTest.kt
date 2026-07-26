package com.mirkori.inplacex.data.sync

import com.mirkori.inplacex.platform.online.GuestAuthApi
import com.mirkori.inplacex.platform.online.GuestAuthResult
import com.mirkori.inplacex.platform.online.GuestAuthSessionManager
import com.mirkori.inplacex.platform.online.GuestInstallation
import com.mirkori.inplacex.platform.online.GuestSession
import com.mirkori.inplacex.platform.online.SecureGuestSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSaveSyncAdapterTest {
    @Test
    fun `bootstrap persists credentials without exposing them through toString`() {
        val store = MemorySessionStore()
        val manager = GuestAuthSessionManager(
            api = FakeAuthApi(bootstrapResult = GuestAuthResult.Authenticated(initialSession())),
            store = store,
            clockMs = { Now },
        )

        val result = manager.bootstrap(GuestInstallation("install-1", "ru-RU", "RU"))

        assertEquals(GuestAuthResult.Authenticated(initialSession()), result)
        assertEquals(initialSession(), store.session)
        assertFalse(store.session.toString().contains("access-token"))
        assertFalse(store.session.toString().contains("refresh-token"))
    }

    @Test
    fun `temporary cloud failure retries the exact queued command before completing it`() {
        val queue = MemoryQueue().apply { enqueue(mutation()) }
        val calls = mutableListOf<CloudSaveMutation>()
        val adapter = adapter(
            queue = queue,
            api = CloudSaveApi { _, mutation ->
                calls += mutation
                if (calls.size == 1) CloudSavePushResult.TemporarilyUnavailable
                else CloudSavePushResult.Applied(snapshot(revision = 2))
            },
        )

        val result = adapter.reconcile()

        assertEquals(CloudSaveReconcileResult.Applied(listOf(mutation().commandId)), result)
        assertEquals(listOf(mutation().commandId, mutation().commandId), calls.map(CloudSaveMutation::commandId))
        assertEquals(listOf(mutation().commandId), queue.completed)
        assertTrue(queue.retried.isEmpty())
    }

    @Test
    fun `revision conflict is explicit and keeps queued mutation for user resolution`() {
        val queue = MemoryQueue().apply { enqueue(mutation()) }
        val adapter = adapter(
            queue = queue,
            api = CloudSaveApi { _, _ -> CloudSavePushResult.Conflict(snapshot(revision = 7)) },
        )

        val result = adapter.reconcile()

        assertEquals(
            CloudSaveReconcileResult.Conflict(mutation().commandId, snapshot(revision = 7)),
            result,
        )
        assertTrue(queue.completed.isEmpty())
        assertEquals(listOf(mutation().commandId), queue.conflicts)
        assertEquals(listOf(mutation()), queue.entries)
    }

    @Test
    fun `expired access token refreshes before queued cloud save is sent`() {
        val queue = MemoryQueue().apply { enqueue(mutation()) }
        val refreshedSession = initialSession(accessExpiresAt = Now + 1_000)
        val auth = FakeAuthApi(refreshResult = GuestAuthResult.Authenticated(refreshedSession))
        var usedSession: GuestSession? = null
        val adapter = CloudSaveSyncAdapter(
            auth = GuestAuthSessionManager(
                api = auth,
                store = MemorySessionStore(initialSession(accessExpiresAt = Now - 1)),
                clockMs = { Now },
            ),
            api = CloudSaveApi { session, _ ->
                usedSession = session
                CloudSavePushResult.Applied(snapshot(revision = 2))
            },
            queue = queue,
        )

        val result = adapter.reconcile()

        assertEquals(CloudSaveReconcileResult.Applied(listOf(mutation().commandId)), result)
        assertEquals(1, auth.refreshCalls)
        assertEquals(refreshedSession, usedSession)
    }

    @Test
    fun `expired refresh token clears credentials and leaves queue untouched`() {
        val queue = MemoryQueue().apply { enqueue(mutation()) }
        val store = MemorySessionStore(initialSession(accessExpiresAt = Now - 1, refreshExpiresAt = Now - 1))
        val adapter = CloudSaveSyncAdapter(
            auth = GuestAuthSessionManager(FakeAuthApi(), store, clockMs = { Now }),
            api = CloudSaveApi { _, _ -> error("cloud API must not be called") },
            queue = queue,
        )

        assertEquals(CloudSaveReconcileResult.AuthenticationUnavailable, adapter.reconcile())
        assertNull(store.session)
        assertEquals(listOf(mutation()), queue.entries)
    }

    private fun adapter(queue: MemoryQueue, api: CloudSaveApi): CloudSaveSyncAdapter = CloudSaveSyncAdapter(
        auth = GuestAuthSessionManager(
            api = FakeAuthApi(),
            store = MemorySessionStore(initialSession()),
            clockMs = { Now },
        ),
        api = api,
        queue = queue,
    )

    private class FakeAuthApi(
        private val bootstrapResult: GuestAuthResult = GuestAuthResult.TemporarilyUnavailable,
        private val refreshResult: GuestAuthResult = GuestAuthResult.TemporarilyUnavailable,
    ) : GuestAuthApi {
        var refreshCalls = 0
            private set

        override fun bootstrap(installation: GuestInstallation): GuestAuthResult = bootstrapResult

        override fun refresh(refreshToken: String): GuestAuthResult {
            refreshCalls += 1
            return refreshResult
        }
    }

    private class MemorySessionStore(
        var session: GuestSession? = null,
    ) : SecureGuestSessionStore {
        override fun read(): GuestSession? = session

        override fun write(session: GuestSession) {
            this.session = session
        }

        override fun clear() {
            session = null
        }
    }

    private class MemoryQueue : CloudSaveQueueStore {
        val entries = mutableListOf<CloudSaveMutation>()
        val completed = mutableListOf<String>()
        val retried = mutableListOf<String>()
        val conflicts = mutableListOf<String>()

        override fun enqueue(mutation: CloudSaveMutation) {
            entries += mutation
        }

        override fun pending(): List<CloudSaveMutation> = entries.filterNot { it.commandId in completed || it.commandId in conflicts }

        override fun complete(commandId: String) {
            completed += commandId
        }

        override fun retry(commandId: String) {
            retried += commandId
        }

        override fun conflict(commandId: String) {
            conflicts += commandId
        }
    }

    private companion object {
        const val Now = 1_000L

        fun mutation() = CloudSaveMutation(
            commandId = "00000000-0000-0000-0000-000000000001",
            playerId = "player-1",
            expectedRevision = 1,
            saveSchemaVersion = 1,
            stateJson = "{\"level\":2}",
        )

        fun snapshot(revision: Long) = CloudSaveSnapshot(
            saveSchemaVersion = 1,
            revision = revision,
            stateJson = "{\"level\":2}",
        )

        fun initialSession(
            accessExpiresAt: Long = Now + 1_000,
            refreshExpiresAt: Long = Now + 10_000,
        ) = GuestSession(
            playerId = "player-1",
            accessToken = "access-token",
            refreshToken = "refresh-token",
            accessExpiresAtEpochMs = accessExpiresAt,
            refreshExpiresAtEpochMs = refreshExpiresAt,
        )
    }
}
