package com.mirkori.inplacex.platform.online

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KtorGuestAuthApiTest {
    @Test
    fun `bootstrap decodes isolated identity response without logging credentials`() {
        val playerId = UUID.randomUUID().toString()
        val boundary = RecordingBoundary(
            RemoteCallResult.Success(
                RemoteResponse(
                    statusCode = 200,
                    headers = emptyMap(),
                    body = """
                        {
                          "playerId":"$playerId",
                          "accountKind":"guest",
                          "credentials":{
                            "accessToken":"access-token",
                            "refreshToken":"refresh-token",
                            "accessExpiresAtEpochMs":2000,
                            "refreshExpiresAtEpochMs":3000
                          }
                        }
                    """.trimIndent(),
                ),
            ),
        )

        val result = KtorGuestAuthApi(boundary).bootstrap(
            GuestInstallation("install-1", "ru-RU", "RU", "1.0"),
        )

        val session = (result as GuestAuthResult.Authenticated).session
        assertEquals(playerId, session.playerId)
        assertEquals("access-token", session.accessToken)
        assertFalse(session.toString().contains("access-token"))
        assertEquals(false, boundary.lastRequest?.requiresAuthentication)
    }

    @Test
    fun `refresh retains the authenticated player identity`() {
        val playerId = UUID.randomUUID().toString()
        val boundary = RecordingBoundary(
            RemoteCallResult.Success(
                RemoteResponse(
                    200,
                    emptyMap(),
                    """
                        {
                          "accessToken":"access-new",
                          "refreshToken":"refresh-new",
                          "accessExpiresAtEpochMs":4000,
                          "refreshExpiresAtEpochMs":5000
                        }
                    """.trimIndent(),
                ),
            ),
        )

        val result = KtorGuestAuthApi(boundary).refresh(playerId, "refresh-old")

        assertEquals(playerId, (result as GuestAuthResult.Authenticated).session.playerId)
        assertEquals("access-new", result.session.accessToken)
    }

    @Test
    fun `malformed and unauthorized responses fail closed`() {
        val malformed = KtorGuestAuthApi(
            RecordingBoundary(
                RemoteCallResult.Success(RemoteResponse(200, emptyMap(), """{"playerId":"forged"}""")),
            ),
        ).bootstrap(GuestInstallation("install-1", "ru-RU", "RU"))
        val unauthorized = KtorGuestAuthApi(
            RecordingBoundary(
                RemoteCallResult.HttpFailure(RemoteResponse(401, emptyMap(), """{"error":"unauthorized"}""")),
            ),
        ).refresh(UUID.randomUUID().toString(), "refresh-old")

        assertEquals(GuestAuthResult.Rejected, malformed)
        assertEquals(GuestAuthResult.Rejected, unauthorized)
    }

    @Test
    fun `offline bootstrap remains distinguishable from rejected credentials`() {
        val result = KtorGuestAuthApi(
            RecordingBoundary(RemoteCallResult.Offline),
        ).bootstrap(GuestInstallation("install-1", "ru-RU", "RU"))

        assertEquals(GuestAuthResult.Offline, result)
    }

    private class RecordingBoundary(
        private val result: RemoteCallResult,
    ) : TransportBoundary {
        var lastRequest: RemoteRequestSpec? = null

        override suspend fun execute(request: RemoteRequestSpec): RemoteCallResult {
            lastRequest = request
            return result
        }

        override suspend fun openSession(request: RemoteWebSocketSpec): OnlineSessionOpenResult =
            OnlineSessionOpenResult.Offline
    }
}
