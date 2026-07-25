package com.mirkori.inplacex.backend.persistence.session

import com.mirkori.inplacex.backend.persistence.DatabaseMigrations
import com.mirkori.inplacex.backend.persistence.IdempotencyKeyReusedException
import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
import com.mirkori.inplacex.logging.InplaceXLogger
import com.mirkori.inplacex.testsupport.RecordingLogSink
import org.h2.jdbcx.JdbcDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.sql.DataSource

class JdbcDurableDuelSessionRepositoryTest {
    @Test
    fun duplicateReturnsOriginalImmutableReceiptAfterLaterChangesAndActorScopeIsIndependent() {
        val dataSource = migratedDataSource()
        val logSink = RecordingLogSink()
        val repository = JdbcDurableDuelSessionRepository(
            dataSource = dataSource,
            logger = InplaceXLogger(logSink),
        )
        val initial = initialSnapshot()
        repository.createSession(initial.sessionId, "duel", initial)
        val firstCommand = turnCommand(initial, "command-1", "1234")

        val first = repository.apply(firstCommand)
        val second = repository.apply(turnCommand(first.receipt.snapshot, "command-2", "5678"))
        val replayedAfterLaterChange = repository.apply(firstCommand)
        val sameIdOtherActor = repository.apply(
            turnCommand(
                current = second.receipt.snapshot,
                commandId = "command-1",
                guess = "2468",
                actorId = "player-b",
            ),
        )

        assertFalse(first.replayed)
        assertEquals(first.receipt, replayedAfterLaterChange.receipt)
        assertTrue(replayedAfterLaterChange.replayed)
        assertEquals(1, replayedAfterLaterChange.receipt.revision)
        assertEquals(3, sameIdOtherActor.receipt.revision)
        assertThrows(IdempotencyKeyReusedException::class.java) {
            repository.apply(turnCommand(initial, "command-1", "4321"))
        }
        dataSource.connection.use { connection ->
            assertEquals(3, count(connection, "duel_session_events"))
            assertEquals(3, count(connection, "duel_command_receipts"))
            val persistedPublicFrames = connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT result_json || snapshot_json AS public_frames
                    FROM duel_command_receipts
                    ORDER BY revision
                    """.trimIndent(),
                ).use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.getString("public_frames"))
                    }.joinToString()
                }
            }
            assertFalse(persistedPublicFrames.contains("1234"))
            assertFalse(persistedPublicFrames.contains("5678"))
            assertFalse(persistedPublicFrames.contains("2468"))
        }
        val logs = logSink.events.joinToString { event ->
            event.message + event.attributes.entries.joinToString { "${it.key}=${it.value}" }
        }
        assertFalse(logs.contains("1234"))
        assertFalse(logs.contains("5678"))
        assertFalse(logs.contains("2468"))
        assertTrue(logSink.events.any { it.attributes["outcome"] == "duplicate" })
        assertTrue(logSink.events.any { it.attributes["outcome"] == "idempotency_key_reused" })
    }

    @Test
    fun failureBetweenStateAndEventInsertRollsBackEveryDurableRow() {
        val dataSource = migratedDataSource()
        val initial = initialSnapshot()
        val repository = JdbcDurableDuelSessionRepository(
            dataSource = dataSource,
            eventRetention = 16,
            maximumReconnectEvents = 16,
            logger = InplaceXLogger(),
            testHooks = object : DurableSessionTestHooks {
                override fun beforeEventInsert() {
                    error("injected event insert failure")
                }
            },
        )
        repository.createSession(initial.sessionId, "duel", initial)

        assertThrows(IllegalStateException::class.java) {
            repository.apply(turnCommand(initial, "command-1", "1234"))
        }

        dataSource.connection.use { connection ->
            assertEquals(0, scalarLong(connection, "SELECT version FROM duel_sessions WHERE id = 'session-1'"))
            assertEquals(0, scalarLong(connection, "SELECT revision FROM duel_session_states WHERE session_id = 'session-1'"))
            assertEquals(0, scalarLong(connection, "SELECT event_cursor FROM duel_session_states WHERE session_id = 'session-1'"))
            assertEquals(0, count(connection, "duel_session_events"))
            assertEquals(0, count(connection, "duel_command_receipts"))
            assertEquals(1, count(connection, "duel_session_snapshots"))
        }
    }

    @Test
    fun reconnectReturnsBoundedReplaySnapshotSuffixGapAndSurvivesRepositoryRestart() {
        val dataSource = migratedDataSource()
        val initial = initialSnapshot()
        var snapshot = initial
        JdbcDurableDuelSessionRepository(
            dataSource = dataSource,
            eventRetention = 3,
            maximumReconnectEvents = 2,
        ).apply {
            createSession(initial.sessionId, "duel", initial)
            (1..4).forEach { number ->
                snapshot = apply(
                    turnCommand(snapshot, "command-$number", "$number$number$number$number"),
                ).receipt.snapshot
            }
        }

        val restarted = JdbcDurableDuelSessionRepository(
            dataSource = dataSource,
            eventRetention = 3,
            maximumReconnectEvents = 2,
        )
        val replay = restarted.reconnect("session-1", lastSeenEventSequence = 2)
            as DurableDuelSessionReconnect.ContiguousReplay
        val snapshotAndEvents = restarted.reconnect("session-1", lastSeenEventSequence = 1)
            as DurableDuelSessionReconnect.SnapshotAndEvents
        val gap = restarted.reconnect("session-1", lastSeenEventSequence = 0)
            as DurableDuelSessionReconnect.ReplayGap
        val caughtUp = restarted.reconnect("session-1", lastSeenEventSequence = 4)
            as DurableDuelSessionReconnect.ContiguousReplay

        assertEquals(listOf(3L, 4L), replay.events.map { it.sequence })
        assertEquals(4, replay.upperBoundEventSequence)
        assertEquals(2, replay.firstRetainedEventSequence)
        assertEquals(2, snapshotAndEvents.snapshot.eventSequence)
        assertEquals(listOf(3L, 4L), snapshotAndEvents.events.map { it.sequence })
        assertTrue(snapshotAndEvents.events.all { it.sequence > snapshotAndEvents.snapshot.eventSequence })
        assertEquals(ReplayGapReason.CURSOR_BEFORE_RETENTION, gap.reason)
        assertEquals(4L, gap.snapshot?.eventSequence)
        assertTrue(caughtUp.events.isEmpty())
    }

    @Test
    fun legacyUnvalidatedPayloadsProduceExplicitGapAndNeverBecomePublicSnapshot() {
        val dataSource = newDataSource()
        JdbcMigrationRunner(DatabaseMigrations.all.take(2)).migrate(dataSource)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO duel_sessions(id, mode, status, config_json, version)
                    VALUES ('legacy', 'duel', 'ACTIVE', '{"seed":"must-not-replay"}', 7)
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO duel_events(session_id, event_type, payload_json)
                    VALUES ('legacy', 'TURN', '{"guess":"1234"}')
                    """.trimIndent(),
                )
            }
        }
        JdbcMigrationRunner().migrate(dataSource)

        val reconnect = JdbcDurableDuelSessionRepository(dataSource).reconnect("legacy", 0)
            as DurableDuelSessionReconnect.ReplayGap

        assertEquals(ReplayGapReason.LEGACY_STATE_NOT_DURABLE, reconnect.reason)
        assertNull(reconnect.snapshot)
        dataSource.connection.use { connection ->
            assertNull(
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT snapshot_json FROM duel_session_states WHERE session_id = 'legacy'",
                    ).use { resultSet ->
                        resultSet.next()
                        resultSet.getString(1)
                    }
                },
            )
            assertEquals(1, scalarLong(connection, "SELECT event_seq FROM duel_events WHERE session_id = 'legacy'"))
            assertEquals(0, count(connection, "duel_session_events"))
        }
    }

    private fun migratedDataSource(): DataSource = newDataSource().also { JdbcMigrationRunner().migrate(it) }

    private fun newDataSource(): DataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:durable-session-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000")
        user = "sa"
        password = ""
    }

    private fun count(connection: java.sql.Connection, table: String): Int =
        scalarLong(connection, "SELECT COUNT(*) FROM $table").toInt()

    private fun scalarLong(connection: java.sql.Connection, sql: String): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { resultSet ->
                resultSet.next()
                resultSet.getLong(1)
            }
        }
}
