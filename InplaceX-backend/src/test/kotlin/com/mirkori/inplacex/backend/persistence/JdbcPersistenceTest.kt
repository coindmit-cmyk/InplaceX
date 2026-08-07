package com.mirkori.inplacex.backend.persistence

import org.h2.jdbcx.JdbcDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

class JdbcPersistenceTest {
    @Test
    fun migrationsCreateAllRequiredStorageAndRecordVersion() {
        val dataSource = newDataSource()

        JdbcMigrationRunner().migrate(dataSource)
        JdbcMigrationRunner().migrate(dataSource)

        dataSource.connection.use { connection ->
            assertEquals(
                setOf(
                    "PLAYERS",
                    "SAVE_HEADS",
                    "SAVE_REVISIONS",
                    "MATCHMAKING_TICKETS",
                    "DUEL_SESSIONS",
                    "DUEL_COMMANDS",
                    "DUEL_EVENTS",
                    "PLAYER_IDENTITIES",
                    "GOOGLE_AUTH_CHALLENGES",
                    "AUTH_IDEMPOTENCY_RESULTS",
                    "DUEL_PARTICIPANTS",
                    "DUEL_SECRETS",
                    "DUEL_TURNS",
                    "PRIVATE_DUEL_INVITES",
                    "ONLINE_COMMAND_RESULTS",
                ),
                connection.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { resultSet ->
                    buildSet {
                        while (resultSet.next()) add(resultSet.getString("TABLE_NAME"))
                    }.intersect(
                        setOf(
                            "PLAYERS", "SAVE_HEADS", "SAVE_REVISIONS", "MATCHMAKING_TICKETS",
                            "DUEL_SESSIONS", "DUEL_COMMANDS", "DUEL_EVENTS",
                            "PLAYER_IDENTITIES", "GOOGLE_AUTH_CHALLENGES",
                            "AUTH_IDEMPOTENCY_RESULTS",
                            "DUEL_PARTICIPANTS", "DUEL_SECRETS", "DUEL_TURNS",
                            "PRIVATE_DUEL_INVITES", "ONLINE_COMMAND_RESULTS",
                        ),
                    )
                },
            )
            assertEquals(8, connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM inplacex_schema_history").use { resultSet ->
                    resultSet.next()
                    resultSet.getInt(1)
                }
            })
        }
    }

    @Test
    fun failedMigrationRollsBackItsDataAndDoesNotRecordVersion() {
        val dataSource = newDataSource()
        JdbcMigrationRunner().migrate(dataSource)
        val failingMigration = SqlMigration(
            version = "999",
            description = "rollback test",
            sql = "INSERT INTO players(id, display_name) VALUES ('rolled-back', 'Rollback'); INSERT INTO absent_table VALUES (1)",
        )

        assertThrows(IllegalStateException::class.java) {
            JdbcMigrationRunner(DatabaseMigrations.all + failingMigration).migrate(dataSource)
        }

        dataSource.connection.use { connection ->
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM players WHERE id = 'rolled-back'"))
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM inplacex_schema_history WHERE version = '999'"))
        }
    }

    @Test
    fun repositoriesEnforceForeignKeysAndUseOptimisticSaveRevisions() {
        val dataSource = migratedDataSource()
        val players = JdbcPlayerRepository(dataSource)
        val saves = JdbcSaveRepository(dataSource)
        val tickets = JdbcTicketRepository(dataSource)

        players.create("player-1", "One")
        assertEquals(1, saves.write("player-1", 0, "{\"coins\":1}", 1).revision)
        assertThrows(RevisionConflictException::class.java) {
            saves.write("player-1", 0, "{\"coins\":2}", 1)
        }
        tickets.create(MatchmakingTicket("ticket-1", "player-1", "ranked", Instant.parse("2026-07-26T00:00:00Z")))

        assertThrows(Exception::class.java) {
            tickets.create(MatchmakingTicket("ticket-2", "missing", "ranked", Instant.parse("2026-07-26T00:00:00Z")))
        }
    }

    @Test
    fun platformPlayerProjectionIsIdempotentAndDoesNotCreateASecondIdentity() {
        val dataSource = migratedDataSource()
        val players = JdbcPlayerRepository(dataSource)
        val playerId = "00000000-0000-4000-8000-000000000701"

        players.create(playerId, "Existing player")
        players.ensurePlatformPlayer(playerId)
        players.ensurePlatformPlayer(playerId)

        dataSource.connection.use { connection ->
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM players WHERE id = '$playerId'"))
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM player_identities WHERE player_id = '$playerId'"))
            val displayName = connection.prepareStatement(
                "SELECT display_name FROM players WHERE id = ?",
            ).use { statement ->
                statement.setString(1, playerId)
                statement.executeQuery().use { result ->
                    require(result.next())
                    result.getString(1)
                }
            }
            assertEquals("Existing player", displayName)
        }
    }

    @Test
    fun concurrentSaveAtSameRevisionAllowsOnlyOneWriter() {
        val dataSource = migratedDataSource()
        JdbcPlayerRepository(dataSource).create("player-1", "One")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { number ->
                executor.submit(Callable {
                    start.await(5, TimeUnit.SECONDS)
                    runCatching { JdbcSaveRepository(dataSource).write("player-1", 0, "{\"writer\":$number}", 1) }
                })
            }
            start.countDown()
            val completed = results.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, completed.count { it.isSuccess })
            assertEquals(1, completed.count { it.exceptionOrNull() is RevisionConflictException })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun sessionCommandsAreIdempotentAndEmitOneEvent() {
        val dataSource = migratedDataSource()
        val sessions = JdbcSessionRepository(dataSource)
        sessions.createSession("session-1", "duel", "{\"length\":4}")

        val accepted = sessions.appendCommand("session-1", "command-1", 0, "SET_SECRET", "{}")
        val replayed = sessions.appendCommand("session-1", "command-1", 0, "SET_SECRET", "{}")

        assertFalse(accepted.replayed)
        assertEquals(1, accepted.version)
        assertEquals(accepted.copy(replayed = true), replayed)
        dataSource.connection.use { connection ->
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM duel_commands"))
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM duel_events"))
        }
    }

    private fun migratedDataSource(): DataSource = newDataSource().also { JdbcMigrationRunner().migrate(it) }

    private fun newDataSource(): DataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:persistence-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000")
        user = "sa"
        password = ""
    }

    private fun count(connection: java.sql.Connection, sql: String): Int = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { resultSet ->
            resultSet.next()
            resultSet.getInt(1)
        }
    }
}
