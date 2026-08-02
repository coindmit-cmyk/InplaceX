package com.mirkori.inplacex.platform.online

import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineDuelClientTest {
    @Test
    fun `create match binds command id to transport idempotency key`() = runBlocking {
        val ticketId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val boundary = QueueBoundary(
            RemoteCallResult.Success(
                RemoteResponse(
                    200,
                    emptyMap(),
                    """
                        {
                          "ticketId":"$ticketId",
                          "status":"matched",
                          "sessionId":"$sessionId",
                          "matchedWithBot":true,
                          "createdAtEpochMs":1000
                        }
                    """.trimIndent(),
                ),
            ),
        )

        val result = OnlineDuelClient(boundary).createMatch(
            mode = RemoteMatchmakingMode.CLASSIC,
            playStyle = RemoteFriendPlayStyle.RACE,
            codeLength = 6,
        )

        assertEquals(
            OnlineClientResult.Success(
                OnlineMatchTicket(
                    ticketId = ticketId,
                    status = OnlineMatchStatus.MATCHED,
                    sessionId = sessionId,
                    matchedWithBot = true,
                ),
            ),
            result,
        )
        val request = requireNotNull(boundary.requests.single())
        val body = Json.parseToJsonElement(requireNotNull(request.bodyJson)).jsonObject
        val bodyCommandId = body.getValue("commandId").jsonPrimitive.content
        assertEquals(bodyCommandId, request.idempotencyKey)
        assertEquals("race", body.getValue("playStyle").jsonPrimitive.content)
        assertEquals("6", body.getValue("codeLength").jsonPrimitive.content)
    }

    @Test
    fun `searching ticket stays explicit and can be polled`() = runBlocking {
        val ticketId = UUID.randomUUID().toString()
        val boundary = QueueBoundary(
            RemoteCallResult.Success(
                RemoteResponse(
                    200,
                    emptyMap(),
                    """
                        {
                          "ticketId":"$ticketId",
                          "status":"searching",
                          "sessionId":null,
                          "matchedWithBot":false,
                          "createdAtEpochMs":1000
                        }
                    """.trimIndent(),
                ),
            ),
        )

        val result = OnlineDuelClient(boundary).readTicket(ticketId)

        assertEquals(
            OnlineClientResult.Success(
                OnlineMatchTicket(
                    ticketId = ticketId,
                    status = OnlineMatchStatus.SEARCHING,
                    sessionId = null,
                    matchedWithBot = false,
                ),
            ),
            result,
        )
        assertEquals("/api/v1/matchmaking/tickets/$ticketId", boundary.requests.single().path)
    }

    @Test
    fun `friend invite encodes a safe private code and shared session`() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        val inviteCode = "7KMQ3NWP"
        val boundary = QueueBoundary(
            RemoteCallResult.Success(
                RemoteResponse(
                    200,
                    emptyMap(),
                    """
                        {
                          "inviteCode":"$inviteCode",
                          "status":"matched",
                           "sessionId":"$sessionId",
                           "createdAtEpochMs":1000,
                           "expiresAtEpochMs":601000,
                           "playStyle":"race",
                           "codeLength":6,
                           "allowDuplicates":true,
                           "maxConsecutiveDuplicateDigits":3,
                           "matchDurationSeconds":600
                        }
                    """.trimIndent(),
                ),
            ),
        )

        val result = OnlineDuelClient(boundary).acceptFriendInvite(inviteCode.lowercase())

        assertEquals(
            OnlineClientResult.Success(
                OnlineFriendInvite(
                    inviteCode = inviteCode,
                    status = OnlineFriendInviteStatus.MATCHED,
                    sessionId = sessionId,
                    expiresAtEpochMs = 601000,
                    playStyle = RemoteFriendPlayStyle.RACE,
                    codeLength = 6,
                ),
            ),
            result,
        )
        val request = boundary.requests.single()
        assertEquals("/api/v1/friends/invites/$inviteCode/accept", request.path)
        val commandId = Json.parseToJsonElement(requireNotNull(request.bodyJson))
            .jsonObject
            .getValue("commandId")
            .jsonPrimitive
            .content
        assertEquals(commandId, request.idempotencyKey)
    }

    @Test
    fun `friend room creation sends owner selected style and length`() = runBlocking {
        val boundary = QueueBoundary(
            RemoteCallResult.Success(
                RemoteResponse(
                    200,
                    emptyMap(),
                    """
                        {
                          "inviteCode":"7KMQ3NWP",
                          "status":"waiting",
                          "sessionId":null,
                          "createdAtEpochMs":1000,
                          "expiresAtEpochMs":601000,
                          "playStyle":"turn_based",
                          "codeLength":8,
                          "allowDuplicates":true,
                          "maxConsecutiveDuplicateDigits":3,
                          "matchDurationSeconds":600
                        }
                    """.trimIndent(),
                ),
            ),
        )

        val result = OnlineDuelClient(boundary).createFriendInvite(
            RemoteFriendPlayStyle.TURN_BASED,
            8,
        )

        assertTrue(result is OnlineClientResult.Success)
        val request = boundary.requests.single()
        val body = Json.parseToJsonElement(requireNotNull(request.bodyJson)).jsonObject
        assertEquals("turn_based", body.getValue("playStyle").jsonPrimitive.content)
        assertEquals("8", body.getValue("codeLength").jsonPrimitive.content)
    }

    @Test
    fun `snapshot parser accepts authoritative progress and no secret field`() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        val boundary = QueueBoundary(
            RemoteCallResult.Success(RemoteResponse(200, emptyMap(), snapshot(sessionId))),
        )

        val result = OnlineDuelClient(boundary).readSession(sessionId)

        val snapshot = (result as OnlineClientResult.Success).value
        assertEquals(2, snapshot.revision)
        assertEquals("player", snapshot.currentTurn)
        assertEquals(2, snapshot.attempts.size)
        assertTrue(boundary.requests.single().bodyJson == null)
    }

    @Test
    fun `auth membership and revision failures stay explicit`() = runBlocking {
        val sessionId = UUID.randomUUID().toString()

        assertEquals(
            OnlineClientResult.AuthenticationRequired,
            OnlineDuelClient(QueueBoundary(failure(401))).readSession(sessionId),
        )
        assertEquals(
            OnlineClientResult.MembershipRejected,
            OnlineDuelClient(QueueBoundary(failure(403))).readSession(sessionId),
        )
        assertEquals(
            OnlineClientResult.RevisionConflict,
            OnlineDuelClient(QueueBoundary(failure(409))).readSession(sessionId),
        )
    }

    @Test
    fun `malformed server snapshot fails closed`() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        val malformed = snapshot(sessionId).replace(
            "\"participants\":[",
            "\"secret\":\"1234\",\"participants\":[",
        )

        assertEquals(
            OnlineClientResult.InvalidResponse,
            OnlineDuelClient(
                QueueBoundary(RemoteCallResult.Success(RemoteResponse(200, emptyMap(), malformed))),
            ).readSession(sessionId),
        )
    }

    private class QueueBoundary(
        private val result: RemoteCallResult,
    ) : TransportBoundary {
        val requests = mutableListOf<RemoteRequestSpec>()

        override suspend fun execute(request: RemoteRequestSpec): RemoteCallResult {
            requests += request
            return result
        }

        override suspend fun openSession(request: RemoteWebSocketSpec): OnlineSessionOpenResult =
            OnlineSessionOpenResult.Offline
    }

    private fun failure(status: Int) = RemoteCallResult.HttpFailure(
        RemoteResponse(status, emptyMap(), """{"error":"failure"}"""),
    )

    private fun snapshot(sessionId: String) = """
        {
          "sessionId":"$sessionId",
          "revision":2,
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
          "attempts":[
            {"actor":"player","exactMatches":1,"number":1},
            {"actor":"opponent","exactMatches":0,"number":2}
          ],
          "participants":[
            {"actor":"player","secretConfigured":true,"attemptsUsed":1,"attemptsLeft":8},
            {"actor":"opponent","secretConfigured":true,"attemptsUsed":1,"attemptsLeft":8}
          ]
        }
    """.trimIndent()
}
