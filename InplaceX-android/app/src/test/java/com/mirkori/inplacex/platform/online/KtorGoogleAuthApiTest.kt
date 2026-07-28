package com.mirkori.inplacex.platform.online

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KtorGoogleAuthApiTest {
    @Test
    fun `challenge and exchange decode without exposing Google token`() = runBlocking {
        val playerId = UUID.randomUUID().toString()
        val nonce = "n".repeat(43)
        val boundary = QueueBoundary(
            ArrayDeque(
                listOf(
                    RemoteCallResult.Success(
                        RemoteResponse(
                            200,
                            emptyMap(),
                            """{"nonce":"$nonce","expiresAtEpochMs":2000}""",
                        ),
                    ),
                    RemoteCallResult.Success(
                        RemoteResponse(
                            200,
                            emptyMap(),
                            """
                            {
                              "playerId":"$playerId",
                              "accountKind":"google",
                              "credentials":{
                                "accessToken":"access-token",
                                "refreshToken":"refresh-token",
                                "accessExpiresAtEpochMs":3000,
                                "refreshExpiresAtEpochMs":4000
                              }
                            }
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        val api = KtorGoogleAuthApi(boundary)

        val challenge = api.challenge() as GoogleChallengeResult.Ready
        val authenticated = api.authenticate(
            idToken = "header.payload.signature",
            nonce = challenge.challenge.nonce,
        ) as GuestAuthResult.Authenticated

        assertEquals(nonce, challenge.challenge.nonce)
        assertEquals(playerId, authenticated.session.playerId)
        assertFalse(boundary.requests.joinToString().contains("header.payload.signature"))
        assertFalse(boundary.requests.joinToString().contains(nonce))
    }

    @Test
    fun `provider failures fail closed`() = runBlocking {
        val unavailable = KtorGoogleAuthApi(
            QueueBoundary(
                ArrayDeque(
                    listOf(
                        RemoteCallResult.HttpFailure(
                            RemoteResponse(503, emptyMap(), """{"error":"provider_unavailable"}"""),
                        ),
                    ),
                ),
            ),
        ).challenge()

        assertEquals(GoogleChallengeResult.ProviderUnavailable, unavailable)
    }

    private class QueueBoundary(
        private val results: ArrayDeque<RemoteCallResult>,
    ) : TransportBoundary {
        val requests = mutableListOf<RemoteRequestSpec>()

        override suspend fun execute(request: RemoteRequestSpec): RemoteCallResult {
            requests += request
            return results.removeFirst()
        }

        override suspend fun openSession(request: RemoteWebSocketSpec): OnlineSessionOpenResult =
            OnlineSessionOpenResult.Offline
    }
}
