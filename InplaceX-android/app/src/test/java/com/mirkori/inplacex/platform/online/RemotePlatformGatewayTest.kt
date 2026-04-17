package com.mirkori.inplacex.platform.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePlatformGatewayTest {
    private val gateway = StubRemotePlatformGateway()

    @Test
    fun `preparePushProgress builds idempotent progress request`() {
        val request = gateway.preparePushProgress(
            payload = RemoteProgressPayload(
                playerId = "player-1",
                gameSlug = "inplacex",
                baseRevision = 7,
                stats = mapOf("matchesWon" to 12),
                balances = mapOf("coins" to 120),
                stateJson = """{"level":4}""",
            ),
            idempotencyKey = "sync-123",
        )

        assertEquals(RemoteHttpMethod.PUT, request.method)
        assertEquals("/v1/players/player-1/progress/inplacex", request.path)
        assertEquals("sync-123", request.idempotencyKey)
        assertEquals("sync-123", request.headers["Idempotency-Key"])
        assertEquals(7L, request.body["baseRevision"])
    }

    @Test
    fun `prepareCreateRoom builds room creation request`() {
        val request = gateway.prepareCreateRoom(
            payload = RemoteCreateRoomPayload(
                playerId = "player-1",
                gameSlug = "inplacex",
                roomName = "Ranked duel",
                visibility = RemoteRoomVisibility.PRIVATE,
                maxMembers = 2,
                inviteCode = "DUEL42",
                configJson = """{"mode":"pvp"}""",
            ),
            idempotencyKey = "room-abc",
        )

        assertEquals(RemoteHttpMethod.POST, request.method)
        assertEquals("/v1/rooms", request.path)
        assertEquals("Ranked duel", request.body["roomName"])
        assertEquals("PRIVATE", request.body["visibility"])
        assertEquals("room-abc", request.headers["Idempotency-Key"])
    }

    @Test
    fun `prepareRoomTransport returns websocket session envelope`() {
        val transportSession = gateway.prepareRoomTransport(
            roomId = "room-1",
            playerId = "player-1",
        )

        assertTrue(transportSession.session.sessionId.startsWith("ws-"))
        assertEquals(RemoteHttpMethod.GET, transportSession.connectRequest.method)
        assertEquals("/v1/rooms/room-1/ws", transportSession.connectRequest.path)
        assertEquals("player-1", transportSession.connectRequest.queryParameters["playerId"])
    }
}
