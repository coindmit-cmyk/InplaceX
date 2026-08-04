package com.mirkori.inplacex.backend.online

import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineLobbyRepository
import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineSessionRepository
import com.mirkori.inplacex.backend.online.persistence.OnlineStateCipher
import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
import org.h2.jdbcx.JdbcDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

class OnlineSessionRestartRecoveryTest {
    @Test
    fun `two players reconnect and continue their match after backend restart`() {
        val dataSource = newDataSource()
        JdbcMigrationRunner().migrate(dataSource)
        val key = ByteArray(32) { index -> (index + 1).toByte() }
        val clock = Clock.fixed(Instant.parse("2026-08-04T10:00:00Z"), ZoneOffset.UTC)
        val owner = UUID.randomUUID().toString()
        val guest = UUID.randomUUID().toString()
        registerPlayers(dataSource, owner, guest)
        val firstGuessCommand = UUID.randomUUID().toString()

        val sessionId = newService(dataSource, key, clock).use { firstRuntime ->
            val invite = firstRuntime.createPrivateInvite(
                playerId = owner,
                commandId = UUID.randomUUID().toString(),
                playStyle = OnlineFriendPlayStyle.TURN_BASED,
                codeLength = 4,
            )
            val accepted = firstRuntime.acceptPrivateInvite(
                playerId = guest,
                commandId = UUID.randomUUID().toString(),
                inviteCode = invite.inviteCode,
            )
            val id = requireNotNull(accepted.sessionId)
            firstRuntime.submitSecret(owner, id, UUID.randomUUID().toString(), 0, "1234")
            firstRuntime.submitSecret(guest, id, UUID.randomUUID().toString(), 1, "5678")
            val afterGuess = firstRuntime.submitGuess(owner, id, firstGuessCommand, 2, "9012")
            assertEquals(3, afterGuess.revision)
            id
        }

        newService(dataSource, key, clock).use { restartedRuntime ->
            val ownerView = restartedRuntime.readSession(owner, sessionId)
            val guestView = restartedRuntime.readSession(guest, sessionId)
            assertEquals(3, ownerView.revision)
            assertEquals("9012", ownerView.attempts.single().ownGuess)
            assertEquals(null, guestView.attempts.single().ownGuess)
            assertEquals("player", guestView.currentTurn)

            val replay = restartedRuntime.submitGuess(owner, sessionId, firstGuessCommand, 2, "9012")
            assertEquals(3, replay.revision)
            assertEquals(1, replay.attempts.size)

            val continued = restartedRuntime.submitGuess(
                playerId = guest,
                sessionId = sessionId,
                commandId = UUID.randomUUID().toString(),
                expectedRevision = 3,
                guess = "3456",
            )
            assertEquals(4, continued.revision)
            assertEquals(2, continued.attempts.size)
        }

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT state_iv, state_ciphertext, version FROM duel_sessions WHERE id = ?",
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(4, result.getLong("version"))
                    assertEquals(12, result.getBytes("state_iv").size)
                    val ciphertext = result.getBytes("state_ciphertext")
                    assertTrue(ciphertext.isNotEmpty())
                    assertNotEquals("1234", ciphertext.toString(Charsets.UTF_8))
                }
            }
        }
    }

    @Test
    fun `server bot seed and reasoning history survive runtime restart`() {
        val dataSource = newDataSource()
        JdbcMigrationRunner().migrate(dataSource)
        val key = ByteArray(32) { index -> (index + 11).toByte() }
        val clock = RecoveryClock(Instant.parse("2026-08-04T11:00:00Z"))
        val player = UUID.randomUUID().toString()
        registerPlayers(dataSource, player)

        val sessionId = newService(dataSource, key, clock).use { firstRuntime ->
            val ticket = firstRuntime.createTicket(
                playerId = player,
                commandId = UUID.randomUUID().toString(),
                mode = OnlineMatchMode.CLASSIC,
            )
            clock.advance(Duration.ofSeconds(5))
            val matched = firstRuntime.readTicket(player, ticket.ticketId)
            val id = requireNotNull(matched.sessionId)
            firstRuntime.submitSecret(player, id, UUID.randomUUID().toString(), 0, "1234")
            val turn = firstRuntime.submitGuess(player, id, UUID.randomUUID().toString(), 1, "5678")
            assertEquals(3, turn.revision)
            assertEquals(2, turn.attempts.size)
            id
        }

        newService(dataSource, key, clock).use { restartedRuntime ->
            val recovered = restartedRuntime.readSession(player, sessionId)
            assertEquals(3, recovered.revision)
            assertEquals(2, recovered.attempts.size)
            val continued = restartedRuntime.submitGuess(
                playerId = player,
                sessionId = sessionId,
                commandId = UUID.randomUUID().toString(),
                expectedRevision = 3,
                guess = "9012",
            )
            assertEquals(5, continued.revision)
            assertEquals(4, continued.attempts.size)
        }
    }

    @Test
    fun `waiting ticket and bot fallback survive backend restarts`() {
        val dataSource = newDataSource()
        JdbcMigrationRunner().migrate(dataSource)
        val key = ByteArray(32) { index -> (index + 21).toByte() }
        val clock = RecoveryClock(Instant.parse("2026-08-04T12:00:00Z"))
        val player = UUID.randomUUID().toString()
        val commandId = UUID.randomUUID().toString()
        registerPlayers(dataSource, player)

        val ticketId = newService(dataSource, key, clock).use { firstRuntime ->
            firstRuntime.createTicket(player, commandId, OnlineMatchMode.CLASSIC).ticketId
        }

        clock.advance(Duration.ofSeconds(5))
        val matchedSessionId = newService(dataSource, key, clock).use { restartedRuntime ->
            val replayedCreate = restartedRuntime.createTicket(player, commandId, OnlineMatchMode.CLASSIC)
            assertEquals(ticketId, replayedCreate.ticketId)
            val matched = restartedRuntime.readTicket(player, ticketId)
            assertEquals(MatchmakingStatus.MATCHED, matched.status)
            assertTrue(matched.matchedWithBot)
            requireNotNull(matched.sessionId)
        }

        newService(dataSource, key, clock).use { secondRestart ->
            val recovered = secondRestart.readTicket(player, ticketId)
            assertEquals(matchedSessionId, recovered.sessionId)
            assertTrue(recovered.matchedWithBot)
            assertEquals(0, secondRestart.readSession(player, matchedSessionId).revision)
        }
    }

    @Test
    fun `unaccepted private invite and accept replay survive backend restarts`() {
        val dataSource = newDataSource()
        JdbcMigrationRunner().migrate(dataSource)
        val key = ByteArray(32) { index -> (index + 31).toByte() }
        val clock = RecoveryClock(Instant.parse("2026-08-04T13:00:00Z"))
        val owner = UUID.randomUUID().toString()
        val guest = UUID.randomUUID().toString()
        val createCommandId = UUID.randomUUID().toString()
        val acceptCommandId = UUID.randomUUID().toString()
        registerPlayers(dataSource, owner, guest)

        val inviteCode = newService(dataSource, key, clock).use { firstRuntime ->
            firstRuntime.createPrivateInvite(
                playerId = owner,
                commandId = createCommandId,
                playStyle = OnlineFriendPlayStyle.RACE,
                codeLength = 6,
            ).inviteCode
        }

        val sessionId = newService(
            dataSource,
            key,
            clock,
            privateMatchDuration = Duration.ofMinutes(20),
        ).use { restartedRuntime ->
            val replayedCreate = restartedRuntime.createPrivateInvite(
                playerId = owner,
                commandId = createCommandId,
                playStyle = OnlineFriendPlayStyle.RACE,
                codeLength = 6,
            )
            assertEquals(inviteCode, replayedCreate.inviteCode)
            assertEquals(PrivateInviteStatus.WAITING, replayedCreate.status)
            assertEquals(Duration.ofMinutes(10).seconds, replayedCreate.matchDurationSeconds)
            val accepted = restartedRuntime.acceptPrivateInvite(guest, acceptCommandId, inviteCode)
            assertEquals(PrivateInviteStatus.MATCHED, accepted.status)
            requireNotNull(accepted.sessionId)
        }

        newService(dataSource, key, clock).use { secondRestart ->
            val ownerView = secondRestart.readPrivateInvite(owner, inviteCode)
            val guestView = secondRestart.readPrivateInvite(guest, inviteCode)
            assertEquals(sessionId, ownerView.sessionId)
            assertEquals(sessionId, guestView.sessionId)
            val replayedAccept = secondRestart.acceptPrivateInvite(guest, acceptCommandId, inviteCode)
            assertEquals(sessionId, replayedAccept.sessionId)
            assertEquals(0, secondRestart.readSession(owner, sessionId).revision)
        }
    }

    @Test
    fun `oldest compatible ticket is paired after restart and both players recover one session`() {
        val dataSource = newDataSource()
        JdbcMigrationRunner().migrate(dataSource)
        val key = ByteArray(32) { index -> (index + 41).toByte() }
        val clock = RecoveryClock(Instant.parse("2026-08-04T14:00:00Z"))
        val firstPlayer = UUID.randomUUID().toString()
        val secondPlayer = UUID.randomUUID().toString()
        val firstCommand = UUID.randomUUID().toString()
        val secondCommand = UUID.randomUUID().toString()
        registerPlayers(dataSource, firstPlayer, secondPlayer)

        val firstTicketId = newService(dataSource, key, clock).use { firstRuntime ->
            firstRuntime.createTicket(firstPlayer, firstCommand, OnlineMatchMode.PRO).ticketId
        }

        val secondTicketId = newService(dataSource, key, clock).use { restartedRuntime ->
            val second = restartedRuntime.createTicket(secondPlayer, secondCommand, OnlineMatchMode.PRO)
            assertEquals(MatchmakingStatus.MATCHED, second.status)
            second.ticketId
        }

        newService(dataSource, key, clock).use { secondRestart ->
            val first = secondRestart.readTicket(firstPlayer, firstTicketId)
            val second = secondRestart.readTicket(secondPlayer, secondTicketId)
            assertEquals(MatchmakingStatus.MATCHED, first.status)
            assertEquals(first.sessionId, second.sessionId)
            assertEquals(false, first.matchedWithBot)
            assertEquals(false, second.matchedWithBot)
            val replay = secondRestart.createTicket(secondPlayer, secondCommand, OnlineMatchMode.PRO)
            assertEquals(secondTicketId, replay.ticketId)
            assertThrows(OnlineCommandIdReusedException::class.java) {
                secondRestart.createTicket(secondPlayer, secondCommand, OnlineMatchMode.CLASSIC)
            }
        }
    }

    @Test
    fun `failed lobby link rolls back new session and in memory match decision`() {
        val dataSource = newDataSource()
        JdbcMigrationRunner().migrate(dataSource)
        val key = ByteArray(32) { index -> (index + 51).toByte() }
        val clock = RecoveryClock(Instant.parse("2026-08-04T15:00:00Z"))
        val persistedPlayer = UUID.randomUUID().toString()
        val missingPlayer = UUID.randomUUID().toString()
        registerPlayers(dataSource, persistedPlayer)

        newService(dataSource, key, clock).use { runtime ->
            val waiting = runtime.createTicket(
                persistedPlayer,
                UUID.randomUUID().toString(),
                OnlineMatchMode.CLASSIC,
            )
            assertThrows(Exception::class.java) {
                runtime.createTicket(
                    missingPlayer,
                    UUID.randomUUID().toString(),
                    OnlineMatchMode.CLASSIC,
                )
            }
            assertEquals(MatchmakingStatus.SEARCHING, runtime.readTicket(persistedPlayer, waiting.ticketId).status)
        }

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM duel_sessions").use { result ->
                    assertTrue(result.next())
                    assertEquals(0, result.getInt(1))
                }
                statement.executeQuery("SELECT COUNT(*) FROM matchmaking_tickets").use { result ->
                    assertTrue(result.next())
                    assertEquals(1, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun `startup removes retained lobby links before deleting an expired session`() {
        val dataSource = newDataSource()
        JdbcMigrationRunner().migrate(dataSource)
        val key = ByteArray(32) { index -> (index + 61).toByte() }
        val now = Instant.parse("2026-08-04T16:00:00Z")
        val clock = RecoveryClock(now)
        val owner = UUID.randomUUID().toString()
        val guest = UUID.randomUUID().toString()
        registerPlayers(dataSource, owner, guest)

        val inviteCode = newService(dataSource, key, clock).use { runtime ->
            val invite = runtime.createPrivateInvite(
                owner,
                UUID.randomUUID().toString(),
                OnlineFriendPlayStyle.TURN_BASED,
                4,
            )
            runtime.acceptPrivateInvite(guest, UUID.randomUUID().toString(), invite.inviteCode)
            invite.inviteCode
        }
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE duel_sessions SET expires_at = ?").use { statement ->
                statement.setObject(1, now.minusSeconds(1).atOffset(ZoneOffset.UTC))
                statement.executeUpdate()
            }
        }

        newService(dataSource, key, clock).use { restarted ->
            assertThrows(NoSuchElementException::class.java) {
                restarted.readPrivateInvite(owner, inviteCode)
            }
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM private_duel_invites").use { result ->
                    assertTrue(result.next())
                    assertEquals(0, result.getInt(1))
                }
                statement.executeQuery("SELECT COUNT(*) FROM duel_sessions").use { result ->
                    assertTrue(result.next())
                    assertEquals(0, result.getInt(1))
                }
            }
        }
    }

    private fun newService(
        dataSource: DataSource,
        key: ByteArray,
        clock: Clock,
        privateMatchDuration: Duration = Duration.ofMinutes(10),
    ): AuthoritativeOnlineDuelService =
        JdbcOnlineSessionRepository(dataSource, OnlineStateCipher(key)).let { sessionRepository ->
            AuthoritativeOnlineDuelService(
                clock = clock,
                privateMatchDuration = privateMatchDuration,
                sessionRepository = sessionRepository,
                lobbyRepository = JdbcOnlineLobbyRepository(dataSource, sessionRepository),
            )
        }

    private fun registerPlayers(dataSource: DataSource, vararg playerIds: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO players(id, display_name) VALUES (?, ?)").use { statement ->
                playerIds.forEachIndexed { index, playerId ->
                    statement.setString(1, playerId)
                    statement.setString(2, "Recovery Player ${index + 1}")
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun newDataSource(): DataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:online-restart-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    }
}

private class RecoveryClock(
    private var current: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = RecoveryClock(current, zone)
    override fun instant(): Instant = current
    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
