package com.mirkori.inplacex.backend.online

import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineLobbyRepository
import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineSessionRepository
import com.mirkori.inplacex.backend.online.persistence.OnlineStateCipher
import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class LegacyOnlineMembershipPostgresIntegrationTest {
    private val now = Instant.parse("2026-08-07T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val key = ByteArray(32) { index -> (index + 37).toByte() }

    @Test
    fun `concurrent exact requests replay one committed PostgreSQL migration`() {
        PostgresFixture(postgres(), clock, key).use { fixture ->
            val legacyPlayerId = fixture.player()
            val opponentPlayerId = fixture.player()
            val platformPlayerId = fixture.player()
            val refreshToken = "duplicate-${"d".repeat(43)}"
            fixture.refreshCredential(legacyPlayerId, refreshToken, now.plusSeconds(600))
            val sessionId = fixture.service("legacy_setup_duplicate").use { service ->
                createActiveSession(service, legacyPlayerId, opponentPlayerId)
            }
            val commandId = UUID.randomUUID().toString()
            val applicationNames = listOf("legacy_duplicate_a", "legacy_duplicate_b")
            val services = applicationNames.map(fixture::service)
            val executor = Executors.newFixedThreadPool(services.size)
            val credentialGate = fixture.lockCredential(refreshToken)
            try {
                val ready = CountDownLatch(services.size)
                val start = CountDownLatch(1)
                val futures = services.map { service ->
                    executor.submit(Callable {
                        ready.countDown()
                        start.await()
                        service.migrateLegacyMembership(
                            platformPlayerId = platformPlayerId,
                            sessionId = sessionId,
                            commandId = commandId,
                            legacyRefreshToken = refreshToken,
                        )
                    })
                }

                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                fixture.awaitBlockedApplications(applicationNames)
                credentialGate.commit()

                val receipts = futures.map { it.get(15, TimeUnit.SECONDS) }
                assertEquals(2, receipts.size)
                assertTrue(receipts.all { it.sessionId == sessionId })
                assertEquals(1, fixture.migrationCount())
                assertEquals(3, fixture.sessionVersion(sessionId))
                assertConsumedAndRevoked(fixture.credentialState(refreshToken))

                fixture.service("legacy_verify_duplicate").use { verifier ->
                    assertEquals(3, verifier.readSession(platformPlayerId, sessionId).revision)
                    assertThrows(OnlineMembershipRejectedException::class.java) {
                        verifier.readSession(legacyPlayerId, sessionId)
                    }
                }
            } finally {
                releaseGate(credentialGate)
                executor.shutdownNow()
                services.forEach(AuthoritativeOnlineDuelService::close)
            }
        }
    }

    @Test
    fun `one refresh proof racing across sessions transfers exactly one membership`() {
        PostgresFixture(postgres(), clock, key).use { fixture ->
            val legacyPlayerId = fixture.player()
            val opponents = listOf(fixture.player(), fixture.player())
            val platformPlayers = listOf(fixture.player(), fixture.player())
            val refreshToken = "cross-session-${"r".repeat(43)}"
            fixture.refreshCredential(legacyPlayerId, refreshToken, now.plusSeconds(600))
            val sessionIds = fixture.service("legacy_setup_cross_session").use { service ->
                opponents.map { opponent -> createActiveSession(service, legacyPlayerId, opponent) }
            }
            val applicationNames = listOf("legacy_cross_session_a", "legacy_cross_session_b")
            val services = applicationNames.map(fixture::service)
            val executor = Executors.newFixedThreadPool(services.size)
            val credentialGate = fixture.lockCredential(refreshToken)
            try {
                val ready = CountDownLatch(services.size)
                val start = CountDownLatch(1)
                val futures = services.indices.map { index ->
                    executor.submit(Callable {
                        ready.countDown()
                        start.await()
                        runCatching {
                            services[index].migrateLegacyMembership(
                                platformPlayerId = platformPlayers[index],
                                sessionId = sessionIds[index],
                                commandId = UUID.randomUUID().toString(),
                                legacyRefreshToken = refreshToken,
                            )
                        }
                    })
                }

                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                fixture.awaitBlockedApplications(applicationNames)
                credentialGate.commit()

                val results = futures.map { it.get(15, TimeUnit.SECONDS) }
                assertEquals(1, results.count(Result<*>::isSuccess))
                assertEquals(
                    1,
                    results.count { it.exceptionOrNull() is LegacyOnlineCredentialRejectedException },
                )
                assertEquals(1, fixture.migrationCount())
                assertConsumedAndRevoked(fixture.credentialState(refreshToken))

                val winnerIndex = results.indexOfFirst(Result<*>::isSuccess)
                val loserIndex = 1 - winnerIndex
                assertEquals(3, fixture.sessionVersion(sessionIds[winnerIndex]))
                assertEquals(2, fixture.sessionVersion(sessionIds[loserIndex]))
                assertEquals(setOf(sessionIds[winnerIndex]), fixture.migratedSessionIds())

                fixture.service("legacy_verify_cross_session").use { verifier ->
                    assertEquals(
                        3,
                        verifier.readSession(platformPlayers[winnerIndex], sessionIds[winnerIndex]).revision,
                    )
                    assertEquals(2, verifier.readSession(legacyPlayerId, sessionIds[loserIndex]).revision)
                    assertThrows(OnlineMembershipRejectedException::class.java) {
                        verifier.readSession(legacyPlayerId, sessionIds[winnerIndex])
                    }
                    assertThrows(OnlineMembershipRejectedException::class.java) {
                        verifier.readSession(platformPlayers[loserIndex], sessionIds[loserIndex])
                    }
                }
            } finally {
                releaseGate(credentialGate)
                executor.shutdownNow()
                services.forEach(AuthoritativeOnlineDuelService::close)
            }
        }
    }

    @Test
    fun `late unique conflict rolls back session token and family before retry`() {
        PostgresFixture(postgres(), clock, key).use { fixture ->
            val legacyPlayerId = fixture.player()
            val opponentPlayerId = fixture.player()
            val platformPlayerId = fixture.player()
            val conflictingPlatformPlayerId = fixture.player()
            val refreshToken = "rollback-${"b".repeat(43)}"
            fixture.refreshCredential(legacyPlayerId, refreshToken, now.plusSeconds(600))
            val sessionId = fixture.service("legacy_setup_rollback").use { service ->
                createActiveSession(service, legacyPlayerId, opponentPlayerId)
            }
            fixture.insertConflictingMigration(
                sessionId = sessionId,
                platformPlayerId = conflictingPlatformPlayerId,
                legacyPlayerId = legacyPlayerId,
                migratedAt = now.minusSeconds(1),
            )

            fixture.service("legacy_rollback_attempt").use { service ->
                assertThrows(SQLException::class.java) {
                    service.migrateLegacyMembership(
                        platformPlayerId = platformPlayerId,
                        sessionId = sessionId,
                        commandId = UUID.randomUUID().toString(),
                        legacyRefreshToken = refreshToken,
                    )
                }
            }

            assertEquals(2, fixture.sessionVersion(sessionId))
            val afterRollback = fixture.credentialState(refreshToken)
            assertNull(afterRollback.consumedAt)
            assertNull(afterRollback.revokedAt)
            fixture.service("legacy_verify_rollback").use { verifier ->
                assertEquals(2, verifier.readSession(legacyPlayerId, sessionId).revision)
                assertThrows(OnlineMembershipRejectedException::class.java) {
                    verifier.readSession(platformPlayerId, sessionId)
                }
            }

            fixture.deleteMigration(sessionId, conflictingPlatformPlayerId)
            fixture.service("legacy_retry_after_rollback").use { service ->
                service.migrateLegacyMembership(
                    platformPlayerId = platformPlayerId,
                    sessionId = sessionId,
                    commandId = UUID.randomUUID().toString(),
                    legacyRefreshToken = refreshToken,
                )
                assertEquals(3, service.readSession(platformPlayerId, sessionId).revision)
            }
            assertEquals(1, fixture.migrationCount())
            assertConsumedAndRevoked(fixture.credentialState(refreshToken))
        }
    }

    private fun createActiveSession(
        service: AuthoritativeOnlineDuelService,
        owner: String,
        guest: String,
    ): String {
        val invite = service.createPrivateInvite(
            playerId = owner,
            commandId = UUID.randomUUID().toString(),
            playStyle = OnlineFriendPlayStyle.TURN_BASED,
            codeLength = 4,
        )
        val sessionId = requireNotNull(
            service.acceptPrivateInvite(
                playerId = guest,
                commandId = UUID.randomUUID().toString(),
                inviteCode = invite.inviteCode,
            ).sessionId,
        )
        service.submitSecret(owner, sessionId, UUID.randomUUID().toString(), 0, "1234")
        service.submitSecret(guest, sessionId, UUID.randomUUID().toString(), 1, "5678")
        return sessionId
    }

    private fun assertConsumedAndRevoked(state: CredentialState) {
        assertNotNull(state.consumedAt)
        assertNotNull(state.revokedAt)
    }

    private fun releaseGate(connection: Connection) {
        runCatching { connection.rollback() }
        runCatching { connection.close() }
    }

    companion object {
        private var postgresContainer: PostgreSQLContainer<Nothing>? = null

        @JvmStatic
        @BeforeClass
        fun startPostgres() {
            val dockerAvailable = runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
            Assume.assumeTrue("Docker is required for PostgreSQL integration tests", dockerAvailable)
            postgresContainer = PostgreSQLContainer<Nothing>(
                DockerImageName.parse("postgres:16.10-alpine"),
            ).apply {
                withDatabaseName("inplacex")
                withUsername("inplacex")
                withPassword("inplacex_test")
                start()
            }
        }

        @JvmStatic
        @AfterClass
        fun stopPostgres() {
            postgresContainer?.stop()
            postgresContainer = null
        }

        private fun postgres(): PostgreSQLContainer<Nothing> =
            requireNotNull(postgresContainer) { "PostgreSQL test container was not started" }
    }
}

private class PostgresFixture(
    private val postgres: PostgreSQLContainer<*>,
    private val clock: Clock,
    private val key: ByteArray,
) : AutoCloseable {
    private val schema = "legacy_migration_${UUID.randomUUID().toString().replace("-", "")}"
    private val adminDataSource = dataSource(schemaName = null, applicationName = "legacy_admin")
    private val dataSource = dataSource(schemaName = schema, applicationName = "legacy_fixture")

    init {
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute("CREATE SCHEMA $schema") }
        }
        JdbcMigrationRunner().migrate(dataSource)
    }

    fun player(): String = UUID.randomUUID().toString().also { playerId ->
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO players(id, display_name) VALUES (?, ?)").use { statement ->
                statement.setString(1, playerId)
                statement.setString(2, "PostgreSQL migration player")
                statement.executeUpdate()
            }
        }
    }

    fun refreshCredential(playerId: String, refreshToken: String, expiresAt: Instant) {
        val familyId = UUID.randomUUID().toString()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO refresh_token_families(id, player_id, expires_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, familyId)
                statement.setString(2, playerId)
                statement.setObject(3, expiresAt.atOffset(ZoneOffset.UTC))
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO refresh_tokens(token_hash, family_id, expires_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, sha256(refreshToken))
                statement.setString(2, familyId)
                statement.setObject(3, expiresAt.atOffset(ZoneOffset.UTC))
                statement.executeUpdate()
            }
        }
    }

    fun service(applicationName: String): AuthoritativeOnlineDuelService {
        val serviceDataSource = dataSource(schemaName = schema, applicationName = applicationName)
        return JdbcOnlineSessionRepository(serviceDataSource, OnlineStateCipher(key)).let { sessions ->
            AuthoritativeOnlineDuelService(
                clock = clock,
                sweepInterval = null,
                sessionRepository = sessions,
                lobbyRepository = JdbcOnlineLobbyRepository(serviceDataSource, sessions),
            )
        }
    }

    fun lockCredential(refreshToken: String): Connection = dataSource(
        schemaName = schema,
        applicationName = "legacy_credential_gate",
    ).connection.apply {
        autoCommit = false
        prepareStatement(
            "SELECT token_hash FROM refresh_tokens WHERE token_hash = ? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, sha256(refreshToken))
            statement.executeQuery().use { results -> assertTrue(results.next()) }
        }
    }

    fun awaitBlockedApplications(applicationNames: List<String>) {
        require(applicationNames.size == 2)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var blocked = 0
        while (System.nanoTime() < deadline) {
            blocked = adminDataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT COUNT(*)
                    FROM pg_stat_activity
                    WHERE application_name IN (?, ?)
                      AND wait_event_type = 'Lock'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, applicationNames[0])
                    statement.setString(2, applicationNames[1])
                    statement.executeQuery().use { results ->
                        assertTrue(results.next())
                        results.getInt(1)
                    }
                }
            }
            if (blocked == applicationNames.size) return
            Thread.sleep(25)
        }
        assertEquals("Both migrations must reach the PostgreSQL row lock", applicationNames.size, blocked)
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

    fun migrationCount(): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM legacy_online_session_migrations").use { results ->
                assertTrue(results.next())
                results.getInt(1)
            }
        }
    }

    fun migratedSessionIds(): Set<String> = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT session_id FROM legacy_online_session_migrations").use { results ->
                buildSet {
                    while (results.next()) add(results.getString(1))
                }
            }
        }
    }

    fun credentialState(refreshToken: String): CredentialState = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT tokens.consumed_at, families.revoked_at
            FROM refresh_tokens tokens
            JOIN refresh_token_families families ON families.id = tokens.family_id
            WHERE tokens.token_hash = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sha256(refreshToken))
            statement.executeQuery().use { results ->
                assertTrue(results.next())
                CredentialState(
                    consumedAt = results.getObject("consumed_at"),
                    revokedAt = results.getObject("revoked_at"),
                )
            }
        }
    }

    fun insertConflictingMigration(
        sessionId: String,
        platformPlayerId: String,
        legacyPlayerId: String,
        migratedAt: Instant,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO legacy_online_session_migrations(
                    session_id, platform_player_id, legacy_player_id, command_id,
                    request_fingerprint, migrated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setString(2, platformPlayerId)
                statement.setString(3, legacyPlayerId)
                statement.setString(4, UUID.randomUUID().toString())
                statement.setString(5, "f".repeat(64))
                statement.setObject(6, migratedAt.atOffset(ZoneOffset.UTC))
                statement.executeUpdate()
            }
        }
    }

    fun deleteMigration(sessionId: String, platformPlayerId: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "DELETE FROM legacy_online_session_migrations WHERE session_id = ? AND platform_player_id = ?",
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setString(2, platformPlayerId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    override fun close() {
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute("DROP SCHEMA $schema CASCADE") }
        }
    }

    private fun dataSource(schemaName: String?, applicationName: String): PGSimpleDataSource =
        PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
            setApplicationName(applicationName)
            if (schemaName != null) setCurrentSchema(schemaName)
        }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private data class CredentialState(
    val consumedAt: Any?,
    val revokedAt: Any?,
)
