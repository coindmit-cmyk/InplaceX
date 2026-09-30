package com.mirkori.inplacex.platform.online

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestAuthSessionManagerTest {
    @Test
    fun `transport reuses a newer stored access token instead of rotating refresh twice`() = runBlocking {
        val store = MemorySessionStore(session(accessToken = "access-new"))
        val api = RecordingGuestAuthApi()
        val manager = GuestAuthSessionManager(
            api = api,
            store = store,
            clockMs = { Now },
        )

        val refreshed = manager.refreshAccessToken(AccessToken.from("access-rejected"))

        assertEquals("Bearer access-new", refreshed?.authorizationHeader())
        assertEquals(0, api.refreshCalls)
    }

    @Test
    fun `transport refresh rotates an expired access token and persists the replacement`() = runBlocking {
        val replacement = session(accessToken = "access-replacement")
        val store = MemorySessionStore(session(accessToken = "access-expired", accessExpiresAt = Now - 1))
        val api = RecordingGuestAuthApi(
            refreshResult = GuestAuthResult.Authenticated(replacement),
        )
        val manager = GuestAuthSessionManager(
            api = api,
            store = store,
            clockMs = { Now },
        )

        val refreshed = manager.refreshAccessToken(AccessToken.from("access-expired"))

        assertEquals("Bearer access-replacement", refreshed?.authorizationHeader())
        assertEquals(1, api.refreshCalls)
        assertEquals(replacement, store.value)
    }

    @Test
    fun `transport clears a session when refresh is rejected`() = runBlocking {
        val store = MemorySessionStore(session(accessExpiresAt = Now - 1))
        val manager = GuestAuthSessionManager(
            api = RecordingGuestAuthApi(refreshResult = GuestAuthResult.Rejected),
            store = store,
            clockMs = { Now },
        )

        val refreshed = manager.refreshAccessToken(AccessToken.from("access-current"))

        assertNull(refreshed)
        assertNull(store.value)
    }

    @Test
    fun `transport keeps a session when refresh is offline`() = runBlocking {
        val existing = session(accessExpiresAt = Now - 1)
        val store = MemorySessionStore(existing)
        val manager = GuestAuthSessionManager(
            api = RecordingGuestAuthApi(refreshResult = GuestAuthResult.Offline),
            store = store,
            clockMs = { Now },
        )

        val refreshed = manager.refreshAccessToken(AccessToken.from("access-current"))

        assertNull(refreshed)
        assertEquals(existing, store.value)
    }

    private class RecordingGuestAuthApi(
        private val refreshResult: GuestAuthResult = GuestAuthResult.TemporarilyUnavailable,
    ) : GuestAuthApi {
        var refreshCalls = 0
            private set

        override fun bootstrap(installation: GuestInstallation): GuestAuthResult =
            GuestAuthResult.TemporarilyUnavailable

        override fun refresh(playerId: String, refreshToken: String): GuestAuthResult {
            refreshCalls += 1
            return refreshResult
        }
    }

    private class MemorySessionStore(
        var value: GuestSession?,
    ) : SecureGuestSessionStore {
        override fun read(): GuestSession? = value

        override fun write(session: GuestSession) {
            value = session
        }

        override fun clear() {
            value = null
        }
    }

    private companion object {
        const val Now = 10_000L

        fun session(
            accessToken: String = "access-current",
            accessExpiresAt: Long = Now + 1_000,
        ) = GuestSession(
            playerId = "player-1",
            accessToken = accessToken,
            refreshToken = "refresh-current",
            accessExpiresAtEpochMs = accessExpiresAt,
            refreshExpiresAtEpochMs = Now + 10_000,
        )
    }
}
