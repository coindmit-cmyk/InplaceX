package com.mirkori.inplacex.backend.persistence.session

import com.mirkori.inplacex.backend.persistence.DatabaseMigrations
import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
import com.mirkori.inplacex.backend.persistence.SqlMigration
import com.mirkori.inplacex.logging.InplaceXLogger
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer

class JdbcDurableDuelSessionPostgresTest {
    @Test
    fun replacementV3BackfillsLegacySequencesWithoutPublishingLegacyPayloads() {
        val dataSource = postgresDataSource()
        JdbcMigrationRunner(DatabaseMigrations.all.take(2)).migrate(dataSource)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO duel_sessions(id, mode, status, config_json, version)
                    VALUES
                        ('legacy-a', 'duel', 'ACTIVE', '{"seed":"private"}', 3),
                        ('legacy-b', 'duel', 'ACTIVE', '{}', 1)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    "INSERT INTO duel_events(session_id, event_type, payload_json) VALUES ('legacy-a', 'TURN', '{\"guess\":\"1111\"}')",
                )
                statement.executeUpdate(
                    "INSERT INTO duel_events(session_id, event_type, payload_json) VALUES ('legacy-b', 'TURN', '{}')",
                )
                statement.executeUpdate(
                    "INSERT INTO duel_events(session_id, event_type, payload_json) VALUES ('legacy-a', 'TURN', '{\"token\":\"private\"}')",
                )
            }
        }

        JdbcMigrationRunner().migrate(dataSource)

        dataSource.connection.use { connection ->
            assertEquals(
                listOf(1L, 3L),
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT event_seq FROM duel_events WHERE session_id = 'legacy-a' ORDER BY event_seq",
                    ).use { resultSet ->
                        buildList {
                            while (resultSet.next()) add(resultSet.getLong(1))
                        }
                    }
                },
            )
            assertEquals(
                4,
                scalarLong(
                    connection,
                    "SELECT first_retained_event_seq FROM duel_session_states WHERE session_id = 'legacy-a'",
                ),
            )
            assertFalse(
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT public_state_available FROM duel_session_states WHERE session_id = 'legacy-a'",
                    ).use { resultSet ->
                        resultSet.next()
                        resultSet.getBoolean(1)
                    }
                },
            )
            assertThrows(Exception::class.java) {
                connection.prepareStatement(
                    """
                    INSERT INTO duel_events(session_id, event_type, payload_json, event_seq)
                    VALUES ('legacy-a', 'TURN', '{}', 3)
                    """.trimIndent(),
                ).use { it.executeUpdate() }
            }
        }
        val reconnect = JdbcDurableDuelSessionRepository(dataSource).reconnect("legacy-a", 0)
            as DurableDuelSessionReconnect.ReplayGap
        assertEquals(ReplayGapReason.LEGACY_STATE_NOT_DURABLE, reconnect.reason)
        assertEquals(null, reconnect.snapshot)
    }

    @Test
    fun postgresMigrationFailureRollsBackDataAndFreshVersionRecord() {
        val dataSource = postgresDataSource()
        JdbcMigrationRunner().migrate(dataSource)
        val failingMigration = SqlMigration(
            version = "4",
            description = "postgres rollback proof",
            sql = "INSERT INTO players(id, display_name) VALUES ('rolled-back', 'Rollback'); INSERT INTO absent_table VALUES (1)",
        )

        assertThrows(IllegalStateException::class.java) {
            JdbcMigrationRunner(DatabaseMigrations.all + failingMigration).migrate(dataSource)
        }

        dataSource.connection.use { connection ->
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM players WHERE id = 'rolled-back'"))
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM inplacex_schema_history WHERE version = '4'"))
        }
    }

    @Test
    fun concurrentPostgresCommitsAtSameRevisionAllowExactlyOneWithoutPartialRows() {
        val dataSource = postgresDataSource()
        JdbcMigrationRunner().migrate(dataSource)
        val initial = initialSnapshot()
        JdbcDurableDuelSessionRepository(dataSource).createSession(initial.sessionId, "duel", initial)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(
                turnCommand(initial, "command-a", "1111"),
                turnCommand(initial, "command-b", "2222"),
            ).map { command ->
                executor.submit(Callable {
                    start.await(10, TimeUnit.SECONDS)
                    runCatching { JdbcDurableDuelSessionRepository(dataSource).apply(command) }
                })
            }
            start.countDown()
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is SessionRevisionConflictException })
            dataSource.connection.use { connection ->
                assertEquals(1, scalarLong(connection, "SELECT version FROM duel_sessions WHERE id = 'session-1'"))
                assertEquals(1, scalarLong(connection, "SELECT revision FROM duel_session_states WHERE session_id = 'session-1'"))
                assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM duel_session_events"))
                assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM duel_command_receipts"))
                assertEquals(2, scalarLong(connection, "SELECT COUNT(*) FROM duel_session_snapshots"))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun reconnectKeepsCapturedUpperBoundWhenConcurrentCommitFinishes() {
        val dataSource = postgresDataSource()
        JdbcMigrationRunner().migrate(dataSource)
        val initial = initialSnapshot()
        val writer = JdbcDurableDuelSessionRepository(dataSource)
        writer.createSession(initial.sessionId, "duel", initial)
        val first = writer.apply(turnCommand(initial, "command-1", "1111"))
        val upperBoundCaptured = CountDownLatch(1)
        val continueReconnect = CountDownLatch(1)
        val reconnecting = JdbcDurableDuelSessionRepository(
            dataSource = dataSource,
            eventRetention = 16,
            maximumReconnectEvents = 16,
            logger = InplaceXLogger(),
            testHooks = object : DurableSessionTestHooks {
                override fun afterReconnectUpperBoundCaptured() {
                    upperBoundCaptured.countDown()
                    check(continueReconnect.await(10, TimeUnit.SECONDS)) {
                        "Timed out waiting for concurrent commit"
                    }
                }
            },
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val reconnectFuture = executor.submit(Callable {
                reconnecting.reconnect("session-1", lastSeenEventSequence = 0)
            })
            assertTrue(upperBoundCaptured.await(10, TimeUnit.SECONDS))
            writer.apply(turnCommand(first.receipt.snapshot, "command-2", "2222"))
            continueReconnect.countDown()

            val reconnect = reconnectFuture.get(20, TimeUnit.SECONDS)
                as DurableDuelSessionReconnect.ContiguousReplay
            assertEquals(1, reconnect.upperBoundEventSequence)
            assertEquals(listOf(1L), reconnect.events.map { it.sequence })
            assertEquals(2, writer.readCurrentSnapshot("session-1").eventSequence)
        } finally {
            continueReconnect.countDown()
            executor.shutdownNow()
        }
    }

    private fun postgresDataSource(): DataSource {
        val schema = "s25_${schemaCounter.incrementAndGet()}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement -> statement.execute("CREATE SCHEMA $schema") }
        }
        val separator = if (postgres.jdbcUrl.contains("?")) "&" else "?"
        return PGSimpleDataSource().apply {
            setURL("${postgres.jdbcUrl}${separator}currentSchema=$schema")
            user = postgres.username
            password = postgres.password
        }
    }

    private fun scalarLong(connection: java.sql.Connection, sql: String): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                resultSet.next()
                resultSet.getLong(1)
            }
        }

    companion object {
        private val schemaCounter = AtomicInteger()
        private lateinit var postgres: KotlinPostgreSQLContainer

        @JvmStatic
        @BeforeClass
        fun startPostgres() {
            postgres = KotlinPostgreSQLContainer("postgres:16-alpine").also { it.start() }
        }

        @JvmStatic
        @AfterClass
        fun stopPostgres() {
            postgres.stop()
        }
    }
}

private class KotlinPostgreSQLContainer(image: String) :
    PostgreSQLContainer<KotlinPostgreSQLContainer>(image)
