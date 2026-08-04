package com.mirkori.inplacex.backend.online

import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineLobbyRepository
import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineSessionRepository
import com.mirkori.inplacex.backend.online.persistence.OnlineStateCipher
import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
import org.h2.jdbcx.JdbcDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

class OnlineMultiInstanceCoordinationTest {
    @Test
    fun `active instances share one matchmaking decision and lazily recover its session`() {
        val fixture = Fixture()
        val firstPlayer = fixture.player()
        val secondPlayer = fixture.player()
        val firstCommand = UUID.randomUUID().toString()
        val first = fixture.service()
        val second = fixture.service()
        try {
            val waiting = first.createTicket(firstPlayer, firstCommand, OnlineMatchMode.CLASSIC)
            val matched = second.createTicket(
                secondPlayer,
                UUID.randomUUID().toString(),
                OnlineMatchMode.CLASSIC,
            )

            assertEquals(MatchmakingStatus.MATCHED, matched.status)
            val refreshedWaiting = first.readTicket(firstPlayer, waiting.ticketId)
            assertEquals(MatchmakingStatus.MATCHED, refreshedWaiting.status)
            assertEquals(matched.sessionId, refreshedWaiting.sessionId)
            assertNotNull(first.readSession(firstPlayer, requireNotNull(matched.sessionId)))

            val crossInstanceReplay = second.createTicket(
                firstPlayer,
                firstCommand,
                OnlineMatchMode.CLASSIC,
            )
            assertEquals(waiting.ticketId, crossInstanceReplay.ticketId)
            assertEquals(matched.sessionId, crossInstanceReplay.sessionId)
            assertEquals(1, fixture.count("duel_sessions"))
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `concurrent contenders claim one waiting ticket at most once`() {
        val fixture = Fixture()
        val waitingPlayer = fixture.player()
        val contenderPlayers = listOf(fixture.player(), fixture.player())
        val waitingRuntime = fixture.service()
        val contenders = listOf(fixture.service(), fixture.service())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val waiting = waitingRuntime.createTicket(
                waitingPlayer,
                UUID.randomUUID().toString(),
                OnlineMatchMode.PRO,
            )
            val start = CountDownLatch(1)
            val futures = contenders.zip(contenderPlayers).map { (runtime, player) ->
                executor.submit(Callable {
                    start.await()
                    runtime.createTicket(player, UUID.randomUUID().toString(), OnlineMatchMode.PRO)
                })
            }
            start.countDown()
            val contenderTickets = futures.map { it.get() }
            val allTickets = contenderTickets + waitingRuntime.readTicket(waitingPlayer, waiting.ticketId)

            assertEquals(2, allTickets.count { it.status == MatchmakingStatus.MATCHED })
            assertEquals(1, allTickets.count { it.status == MatchmakingStatus.SEARCHING })
            assertEquals(1, allTickets.mapNotNull { it.sessionId }.distinct().size)
            assertEquals(1, fixture.count("duel_sessions"))
        } finally {
            executor.shutdownNow()
            waitingRuntime.close()
            contenders.forEach(AuthoritativeOnlineDuelService::close)
        }
    }

    @Test
    fun `concurrent duplicate command creates one durable ticket`() {
        val fixture = Fixture()
        val player = fixture.player()
        val commandId = UUID.randomUUID().toString()
        val runtimes = listOf(fixture.service(), fixture.service())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val futures = runtimes.map { runtime ->
                executor.submit(Callable {
                    start.await()
                    runtime.createTicket(player, commandId, OnlineMatchMode.CLASSIC)
                })
            }
            start.countDown()
            val tickets = futures.map { it.get() }

            assertEquals(1, fixture.count("matchmaking_tickets"))
            val durableTicketId = fixture.singleTicketId()
            assertEquals(durableTicketId, tickets.first().ticketId)
            assertEquals(durableTicketId, tickets.last().ticketId)
            assertEquals(0, fixture.count("duel_sessions"))
        } finally {
            executor.shutdownNow()
            runtimes.forEach(AuthoritativeOnlineDuelService::close)
        }
    }

    @Test
    fun `bot fallback and human matching cannot create two sessions`() {
        val fixture = Fixture()
        val waitingPlayer = fixture.player()
        val humanPlayer = fixture.player()
        val waitingRuntime = fixture.service()
        val humanRuntime = fixture.service()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val waiting = waitingRuntime.createTicket(
                waitingPlayer,
                UUID.randomUUID().toString(),
                OnlineMatchMode.CLASSIC,
            )
            fixture.clock.advance(Duration.ofSeconds(5))
            val start = CountDownLatch(1)
            val fallback = executor.submit(Callable {
                start.await()
                waitingRuntime.readTicket(waitingPlayer, waiting.ticketId)
            })
            val human = executor.submit(Callable {
                start.await()
                humanRuntime.createTicket(
                    humanPlayer,
                    UUID.randomUUID().toString(),
                    OnlineMatchMode.CLASSIC,
                )
            })
            start.countDown()
            fallback.get()
            human.get()

            val refreshed = waitingRuntime.readTicket(waitingPlayer, waiting.ticketId)
            assertEquals(MatchmakingStatus.MATCHED, refreshed.status)
            assertTrue(refreshed.matchedWithBot)
            assertEquals(1, fixture.count("duel_sessions"))
        } finally {
            executor.shutdownNow()
            waitingRuntime.close()
            humanRuntime.close()
        }
    }

    @Test
    fun `concurrent duplicate invite create command returns one durable invite`() {
        val fixture = Fixture()
        val owner = fixture.player()
        val commandId = UUID.randomUUID().toString()
        val runtimes = listOf(fixture.service(), fixture.service())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val futures = runtimes.map { runtime ->
                executor.submit(Callable {
                    start.await()
                    runtime.createPrivateInvite(
                        owner,
                        commandId,
                        OnlineFriendPlayStyle.TURN_BASED,
                        4,
                    )
                })
            }
            start.countDown()
            val invites = futures.map { it.get() }

            assertEquals(1, fixture.count("private_duel_invites"))
            assertEquals(invites.first().inviteCode, invites.last().inviteCode)
            assertEquals(PrivateInviteStatus.WAITING, invites.first().status)
            assertEquals(0, fixture.count("duel_sessions"))
        } finally {
            executor.shutdownNow()
            runtimes.forEach(AuthoritativeOnlineDuelService::close)
        }
    }

    @Test
    fun `concurrent guests can accept one invite only once`() {
        val fixture = Fixture()
        val owner = fixture.player()
        val guests = listOf(fixture.player(), fixture.player())
        val ownerRuntime = fixture.service()
        val guestRuntimes = listOf(fixture.service(), fixture.service())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val invite = ownerRuntime.createPrivateInvite(
                owner,
                UUID.randomUUID().toString(),
                OnlineFriendPlayStyle.RACE,
                6,
            )
            val start = CountDownLatch(1)
            val futures = guestRuntimes.zip(guests).map { (runtime, guest) ->
                executor.submit(Callable {
                    start.await()
                    runCatching {
                        runtime.acceptPrivateInvite(guest, UUID.randomUUID().toString(), invite.inviteCode)
                    }
                })
            }
            start.countDown()
            val results = futures.map { it.get() }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is OnlineInviteUnavailableException })
            assertEquals(1, fixture.count("duel_sessions"))
            val matched = ownerRuntime.readPrivateInvite(owner, invite.inviteCode)
            assertEquals(PrivateInviteStatus.MATCHED, matched.status)
            assertEquals(results.single { it.isSuccess }.getOrThrow().sessionId, matched.sessionId)
        } finally {
            executor.shutdownNow()
            ownerRuntime.close()
            guestRuntimes.forEach(AuthoritativeOnlineDuelService::close)
        }
    }

    @Test
    fun `duplicate accept command across instances returns one session`() {
        val fixture = Fixture()
        val owner = fixture.player()
        val guest = fixture.player()
        val ownerRuntime = fixture.service()
        val guestRuntimes = listOf(fixture.service(), fixture.service())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val invite = ownerRuntime.createPrivateInvite(
                owner,
                UUID.randomUUID().toString(),
                OnlineFriendPlayStyle.TURN_BASED,
                4,
            )
            val commandId = UUID.randomUUID().toString()
            val start = CountDownLatch(1)
            val futures = guestRuntimes.map { runtime ->
                executor.submit(Callable {
                    start.await()
                    runtime.acceptPrivateInvite(guest, commandId, invite.inviteCode)
                })
            }
            start.countDown()
            val accepted = futures.map { it.get() }

            assertEquals(1, fixture.count("duel_sessions"))
            assertEquals(accepted.first().sessionId, accepted.last().sessionId)
            assertNotNull(guestRuntimes.last().readSession(guest, requireNotNull(accepted.last().sessionId)))
        } finally {
            executor.shutdownNow()
            ownerRuntime.close()
            guestRuntimes.forEach(AuthoritativeOnlineDuelService::close)
        }
    }

    @Test
    fun `stale instance cannot expire an invite matched elsewhere`() {
        val fixture = Fixture()
        val owner = fixture.player()
        val guest = fixture.player()
        val staleRuntime = fixture.service()
        val acceptingRuntime = fixture.service()
        try {
            val createCommandId = UUID.randomUUID().toString()
            val invite = staleRuntime.createPrivateInvite(
                owner,
                createCommandId,
                OnlineFriendPlayStyle.TURN_BASED,
                4,
            )
            val accepted = acceptingRuntime.acceptPrivateInvite(
                guest,
                UUID.randomUUID().toString(),
                invite.inviteCode,
            )
            fixture.clock.advance(Duration.ofMinutes(10))

            val replayedCreate = staleRuntime.createPrivateInvite(
                owner,
                createCommandId,
                OnlineFriendPlayStyle.TURN_BASED,
                4,
            )
            val refreshed = staleRuntime.readPrivateInvite(owner, invite.inviteCode)

            assertEquals(PrivateInviteStatus.MATCHED, replayedCreate.status)
            assertEquals(PrivateInviteStatus.MATCHED, refreshed.status)
            assertEquals(accepted.sessionId, refreshed.sessionId)
            assertEquals(1, fixture.count("duel_sessions"))
        } finally {
            staleRuntime.close()
            acceptingRuntime.close()
        }
    }

    @Test
    fun `concurrent strict revision commands have one durable winner`() {
        val fixture = Fixture()
        val owner = fixture.player()
        val guest = fixture.player()
        val runtimes = listOf(fixture.service(), fixture.service())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val invite = runtimes.first().createPrivateInvite(
                owner,
                UUID.randomUUID().toString(),
                OnlineFriendPlayStyle.TURN_BASED,
                4,
            )
            val sessionId = requireNotNull(
                runtimes.last().acceptPrivateInvite(
                    guest,
                    UUID.randomUUID().toString(),
                    invite.inviteCode,
                ).sessionId,
            )
            val start = CountDownLatch(1)
            val commands = listOf(
                Triple(runtimes.first(), owner, "1234"),
                Triple(runtimes.last(), guest, "5678"),
            )
            val futures = commands.map { (runtime, player, secret) ->
                executor.submit(Callable {
                    start.await()
                    runCatching {
                        runtime.submitSecret(
                            player,
                            sessionId,
                            UUID.randomUUID().toString(),
                            0,
                            secret,
                        )
                    }
                })
            }
            start.countDown()
            val results = futures.map { it.get() }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is OnlineRevisionConflictException })
            val snapshot = runtimes.first().readSession(owner, sessionId)
            assertEquals(1, snapshot.revision)
            assertEquals(1, snapshot.participants.count { it.secretConfigured })
            assertEquals(1, fixture.sessionVersion(sessionId))
        } finally {
            executor.shutdownNow()
            runtimes.forEach(AuthoritativeOnlineDuelService::close)
        }
    }

    @Test
    fun `duplicate duel command across instances advances once`() {
        val fixture = Fixture()
        val owner = fixture.player()
        val guest = fixture.player()
        val runtimes = listOf(fixture.service(), fixture.service())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val invite = runtimes.first().createPrivateInvite(
                owner,
                UUID.randomUUID().toString(),
                OnlineFriendPlayStyle.TURN_BASED,
                4,
            )
            val sessionId = requireNotNull(
                runtimes.last().acceptPrivateInvite(
                    guest,
                    UUID.randomUUID().toString(),
                    invite.inviteCode,
                ).sessionId,
            )
            val commandId = UUID.randomUUID().toString()
            val start = CountDownLatch(1)
            val futures = runtimes.map { runtime ->
                executor.submit(Callable {
                    start.await()
                    runtime.submitSecret(owner, sessionId, commandId, 0, "1234")
                })
            }
            start.countDown()
            val results = futures.map { it.get() }

            assertEquals(results.first(), results.last())
            assertEquals(1, results.first().revision)
            assertEquals(1, fixture.sessionVersion(sessionId))
        } finally {
            executor.shutdownNow()
            runtimes.forEach(AuthoritativeOnlineDuelService::close)
        }
    }

    @Test
    fun `concurrent race guesses are serialized without losing either command`() {
        val fixture = Fixture()
        val owner = fixture.player()
        val guest = fixture.player()
        val runtimes = listOf(fixture.service(), fixture.service())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val invite = runtimes.first().createPrivateInvite(
                owner,
                UUID.randomUUID().toString(),
                OnlineFriendPlayStyle.RACE,
                4,
            )
            val sessionId = requireNotNull(
                runtimes.last().acceptPrivateInvite(
                    guest,
                    UUID.randomUUID().toString(),
                    invite.inviteCode,
                ).sessionId,
            )
            val ownerReady = runtimes.first().submitSecret(
                owner,
                sessionId,
                UUID.randomUUID().toString(),
                0,
                "1234",
            )
            val active = runtimes.last().submitSecret(
                guest,
                sessionId,
                UUID.randomUUID().toString(),
                ownerReady.revision,
                "5678",
            )
            val start = CountDownLatch(1)
            val guesses = listOf(
                Triple(runtimes.first(), owner, "0011"),
                Triple(runtimes.last(), guest, "9900"),
            )
            val futures = guesses.map { (runtime, player, guess) ->
                executor.submit(Callable {
                    start.await()
                    runtime.submitGuess(
                        player,
                        sessionId,
                        UUID.randomUUID().toString(),
                        active.revision,
                        guess,
                    )
                })
            }
            start.countDown()
            futures.forEach { it.get() }

            val snapshot = runtimes.first().readSession(owner, sessionId)
            assertEquals(4, snapshot.revision)
            assertEquals(2, snapshot.attempts.size)
            assertEquals(4, fixture.sessionVersion(sessionId))
        } finally {
            executor.shutdownNow()
            runtimes.forEach(AuthoritativeOnlineDuelService::close)
        }
    }

    private class Fixture {
        val dataSource: DataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:online-multi-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        }
        val clock = MutableCoordinationClock(Instant.parse("2026-08-04T17:00:00Z"))
        private val key = ByteArray(32) { index -> (index + 71).toByte() }

        init {
            JdbcMigrationRunner().migrate(dataSource)
        }

        fun player(): String = UUID.randomUUID().toString().also { playerId ->
            dataSource.connection.use { connection ->
                connection.prepareStatement("INSERT INTO players(id, display_name) VALUES (?, ?)").use { statement ->
                    statement.setString(1, playerId)
                    statement.setString(2, "Coordination Player")
                    statement.executeUpdate()
                }
            }
        }

        fun service(): AuthoritativeOnlineDuelService =
            JdbcOnlineSessionRepository(dataSource, OnlineStateCipher(key)).let { sessions ->
                AuthoritativeOnlineDuelService(
                    clock = clock,
                    botFallbackDelay = Duration.ofSeconds(5),
                    sweepInterval = null,
                    sessionRepository = sessions,
                    lobbyRepository = JdbcOnlineLobbyRepository(dataSource, sessions),
                )
            }

        fun count(table: String): Int {
            require(table in setOf("matchmaking_tickets", "private_duel_invites", "duel_sessions"))
            return dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM $table").use { results ->
                        assertTrue(results.next())
                        results.getInt(1)
                    }
                }
            }
        }

        fun singleTicketId(): String = dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id FROM matchmaking_tickets").use { results ->
                    assertTrue(results.next())
                    results.getString(1)
                }
            }
        }

        fun sessionVersion(sessionId: String): Long = dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT version FROM duel_sessions WHERE id = ?").use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { results ->
                    assertTrue(results.next())
                    results.getLong(1)
                }
            }
        }
    }
}

private class MutableCoordinationClock(
    initial: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    private val current = AtomicReference(initial)
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableCoordinationClock(current.get(), zone)
    override fun instant(): Instant = current.get()
    fun advance(duration: Duration) {
        current.updateAndGet { it.plus(duration) }
    }
}
