package com.mirkori.inplacex.backend.online

import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineLobbyRepository
import com.mirkori.inplacex.backend.online.persistence.JdbcOnlineSessionRepository
import com.mirkori.inplacex.backend.online.persistence.LegacyMembershipMigrationConflictException
import com.mirkori.inplacex.backend.online.persistence.OnlineStateCipher
import com.mirkori.inplacex.backend.persistence.JdbcMigrationRunner
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import org.h2.jdbcx.JdbcDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyOnlineMembershipMigrationTest {
    private val now = Instant.parse("2026-08-07T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val key = ByteArray(32) { index -> (index + 9).toByte() }

    @Test
    fun `membership proof transfers once survives response loss and revokes legacy family`() {
        val fixture = fixture()
        val commandId = UUID.randomUUID().toString()
        val guessCommand = UUID.randomUUID().toString()
        val sessionId = fixture.service.use { service ->
            val sessionId = createActiveSession(service, fixture.legacyPlayerId, fixture.opponentPlayerId)
            val afterGuess = service.submitGuess(
                playerId = fixture.legacyPlayerId,
                sessionId = sessionId,
                commandId = guessCommand,
                expectedRevision = 2,
                guess = "9012",
            )
            assertEquals(3, afterGuess.revision)

            assertEquals(
                sessionId,
                service.migrateLegacyMembership(
                    platformPlayerId = fixture.platformPlayerId,
                    sessionId = sessionId,
                    commandId = commandId,
                    legacyRefreshToken = fixture.validRefreshToken,
                ).sessionId,
            )
            val migrated = service.readSession(fixture.platformPlayerId, sessionId)
            assertEquals(4, migrated.revision)
            assertThrows(OnlineMembershipRejectedException::class.java) {
                service.readSession(fixture.legacyPlayerId, sessionId)
            }

            val movedReplay = service.submitGuess(
                playerId = fixture.platformPlayerId,
                sessionId = sessionId,
                commandId = guessCommand,
                expectedRevision = 2,
                guess = "9012",
            )
            assertEquals(3, movedReplay.revision)

            // Exact retry after a lost response is durable even though the proof is now revoked.
            service.migrateLegacyMembership(
                platformPlayerId = fixture.platformPlayerId,
                sessionId = sessionId,
                commandId = commandId,
                legacyRefreshToken = fixture.validRefreshToken,
            )
            assertEquals(4, service.readSession(fixture.platformPlayerId, sessionId).revision)
            assertThrows(LegacyMembershipMigrationConflictException::class.java) {
                service.migrateLegacyMembership(
                    platformPlayerId = fixture.platformPlayerId,
                    sessionId = sessionId,
                    commandId = UUID.randomUUID().toString(),
                    legacyRefreshToken = fixture.validRefreshToken,
                )
            }
            sessionId
        }

        fixture.dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT tokens.consumed_at, families.revoked_at
                FROM refresh_tokens tokens
                JOIN refresh_token_families families ON families.id = tokens.family_id
                WHERE tokens.token_hash = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sha256(fixture.validRefreshToken))
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertNotNull(result.getObject("consumed_at"))
                    assertNotNull(result.getObject("revoked_at"))
                }
            }
        }

        service(fixture.dataSource).use { restarted ->
            assertEquals(4, restarted.readSession(fixture.platformPlayerId, sessionId).revision)
            assertThrows(OnlineMembershipRejectedException::class.java) {
                restarted.readSession(fixture.legacyPlayerId, sessionId)
            }
        }
    }

    @Test
    fun `wrong expired and consumed proofs fail closed before a valid transfer`() {
        val fixture = fixture()
        fixture.service.use { service ->
            val sessionId = createActiveSession(service, fixture.legacyPlayerId, fixture.opponentPlayerId)
            val expired = "expired-${"e".repeat(43)}"
            val consumed = "consumed-${"c".repeat(43)}"
            val outsider = "outsider-${"o".repeat(43)}"
            val outsiderPlayerId = UUID.randomUUID().toString()
            registerPlayers(fixture.dataSource, outsiderPlayerId)
            insertRefreshCredential(
                fixture.dataSource,
                fixture.legacyPlayerId,
                expired,
                now.minusSeconds(1),
            )
            insertRefreshCredential(
                fixture.dataSource,
                fixture.legacyPlayerId,
                consumed,
                now.plusSeconds(600),
                consumedAt = now.minusSeconds(1),
            )
            insertRefreshCredential(
                fixture.dataSource,
                outsiderPlayerId,
                outsider,
                now.plusSeconds(600),
            )

            listOf(
                "unknown-${"x".repeat(43)}" to null,
                expired to true,
                consumed to true,
                outsider to false,
            ).forEach { (rejected, expectedFamilyRevoked) ->
                assertThrows(LegacyOnlineCredentialRejectedException::class.java) {
                    service.migrateLegacyMembership(
                        platformPlayerId = fixture.platformPlayerId,
                        sessionId = sessionId,
                        commandId = UUID.randomUUID().toString(),
                        legacyRefreshToken = rejected,
                    )
                }
                assertEquals(2, service.readSession(fixture.legacyPlayerId, sessionId).revision)
                if (expectedFamilyRevoked != null) {
                    assertEquals(
                        expectedFamilyRevoked,
                        credentialFamilyRevoked(fixture.dataSource, rejected),
                    )
                }
            }

            service.migrateLegacyMembership(
                platformPlayerId = fixture.platformPlayerId,
                sessionId = sessionId,
                commandId = UUID.randomUUID().toString(),
                legacyRefreshToken = fixture.validRefreshToken,
            )
            assertEquals(3, service.readSession(fixture.platformPlayerId, sessionId).revision)
        }
    }

    private fun fixture(): MigrationFixture {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:legacy-online-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
        }
        JdbcMigrationRunner().migrate(dataSource)
        val legacyPlayerId = UUID.randomUUID().toString()
        val opponentPlayerId = UUID.randomUUID().toString()
        val platformPlayerId = UUID.randomUUID().toString()
        registerPlayers(dataSource, legacyPlayerId, opponentPlayerId, platformPlayerId)
        val validRefreshToken = "valid-${"v".repeat(43)}"
        insertRefreshCredential(
            dataSource,
            legacyPlayerId,
            validRefreshToken,
            now.plusSeconds(600),
        )
        return MigrationFixture(
            dataSource = dataSource,
            service = service(dataSource),
            legacyPlayerId = legacyPlayerId,
            opponentPlayerId = opponentPlayerId,
            platformPlayerId = platformPlayerId,
            validRefreshToken = validRefreshToken,
        )
    }

    private fun service(dataSource: DataSource): AuthoritativeOnlineDuelService =
        JdbcOnlineSessionRepository(dataSource, OnlineStateCipher(key)).let { sessions ->
            AuthoritativeOnlineDuelService(
                clock = clock,
                sessionRepository = sessions,
                lobbyRepository = JdbcOnlineLobbyRepository(dataSource, sessions),
            )
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

    private fun registerPlayers(dataSource: DataSource, vararg playerIds: String) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO players(id, display_name) VALUES (?, ?)").use { statement ->
                playerIds.forEachIndexed { index, playerId ->
                    statement.setString(1, playerId)
                    statement.setString(2, "Migration player ${index + 1}")
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun insertRefreshCredential(
        dataSource: DataSource,
        playerId: String,
        refreshToken: String,
        expiresAt: Instant,
        consumedAt: Instant? = null,
    ) {
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
                "INSERT INTO refresh_tokens(token_hash, family_id, expires_at, consumed_at) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, sha256(refreshToken))
                statement.setString(2, familyId)
                statement.setObject(3, expiresAt.atOffset(ZoneOffset.UTC))
                statement.setObject(4, consumedAt?.atOffset(ZoneOffset.UTC))
                statement.executeUpdate()
            }
        }
    }

    private fun credentialFamilyRevoked(dataSource: DataSource, refreshToken: String): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT families.revoked_at
                FROM refresh_tokens tokens
                JOIN refresh_token_families families ON families.id = tokens.family_id
                WHERE tokens.token_hash = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sha256(refreshToken))
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getObject("revoked_at") != null
                }
            }
        }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private data class MigrationFixture(
    val dataSource: DataSource,
    val service: AuthoritativeOnlineDuelService,
    val legacyPlayerId: String,
    val opponentPlayerId: String,
    val platformPlayerId: String,
    val validRefreshToken: String,
)
