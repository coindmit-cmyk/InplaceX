package com.mirkori.inplacex.backend.online

import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineSessionRepository
import com.mirkori.inplacex.backend.online.persistence.OnlineStateCipher
import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
import org.h2.jdbcx.JdbcDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    private fun newService(dataSource: DataSource, key: ByteArray, clock: Clock): AuthoritativeOnlineDuelService =
        AuthoritativeOnlineDuelService(
            clock = clock,
            sessionRepository = JdbcOnlineSessionRepository(dataSource, OnlineStateCipher(key)),
        )

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
