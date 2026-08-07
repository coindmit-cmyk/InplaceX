package com.mirkori.inplacex.backend.online.persistence

import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

data class DurableOnlineSession(
    val sessionId: String,
    val revision: Long,
    val status: String,
    val stateJson: String,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val expiresAt: Instant?,
)

data class DurableSessionCoordination<T>(
    val session: DurableOnlineSession,
    val result: T,
)

class OnlineSessionRevisionConflictException(sessionId: String, expectedRevision: Long) :
    IllegalStateException("Online session $sessionId changed from revision $expectedRevision")

class LegacyMembershipMigrationConflictException :
    IllegalStateException("Legacy online membership migration idempotency key was reused")

enum class LegacyMembershipTransferPersistenceResult {
    TRANSFERRED,
    REPLAYED,
    REJECTED,
    UNAVAILABLE,
}

interface OnlineSessionRepository : AutoCloseable {
    fun deleteExpired(now: Instant)
    fun loadRecoverable(now: Instant): List<DurableOnlineSession>
    fun loadRecoverable(sessionId: String, now: Instant): DurableOnlineSession?
    fun <T> coordinate(
        sessionId: String,
        now: Instant,
        includeExpired: Boolean = false,
        operation: (DurableOnlineSession) -> DurableSessionCoordination<T>,
    ): T?
    fun create(session: DurableOnlineSession)
    fun update(session: DurableOnlineSession, expectedRevision: Long)
    fun transferLegacyMembership(
        sessionId: String,
        platformPlayerId: String,
        presentedTokenHash: String,
        commandId: String,
        requestFingerprint: String,
        now: Instant,
        transfer: (DurableOnlineSession, legacyPlayerId: String) -> DurableOnlineSession,
    ): LegacyMembershipTransferPersistenceResult = LegacyMembershipTransferPersistenceResult.UNAVAILABLE
    fun delete(sessionId: String)
    override fun close() = Unit
}

/** PostgreSQL boundary for encrypted, restart-recoverable online duel aggregates. */
class JdbcOnlineSessionRepository(
    private val dataSource: DataSource,
    private val cipher: OnlineStateCipher,
) : OnlineSessionRepository {
    override fun deleteExpired(now: Instant) {
        dataSource.transaction { connection ->
            connection.prepareStatement(
                """
                DELETE FROM duel_events
                WHERE session_id IN (
                    SELECT id FROM duel_sessions
                    WHERE expires_at IS NOT NULL AND expires_at <= ?
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setInstant(1, now)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "DELETE FROM duel_sessions WHERE expires_at IS NOT NULL AND expires_at <= ?",
            ).use { statement ->
                statement.setInstant(1, now)
                statement.executeUpdate()
            }
        }
    }

    override fun loadRecoverable(now: Instant): List<DurableOnlineSession> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, version, status, state_iv, state_ciphertext, created_at,
                       started_at, finished_at, expires_at
                FROM duel_sessions
                WHERE state_iv IS NOT NULL
                  AND state_ciphertext IS NOT NULL
                  AND (expires_at IS NULL OR expires_at > ?)
                ORDER BY created_at, id
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, now.atOffset(ZoneOffset.UTC))
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) {
                            val sessionId = results.getString("id")
                            val plaintext = cipher.decrypt(
                                sessionId,
                                EncryptedOnlineState(
                                    iv = results.getBytes("state_iv"),
                                    ciphertext = results.getBytes("state_ciphertext"),
                                ),
                            )
                            try {
                                add(
                                    DurableOnlineSession(
                                        sessionId = sessionId,
                                        revision = results.getLong("version"),
                                        status = results.getString("status"),
                                        stateJson = plaintext.toString(Charsets.UTF_8),
                                        createdAt = results.instant("created_at")!!,
                                        startedAt = results.instant("started_at"),
                                        finishedAt = results.instant("finished_at"),
                                        expiresAt = results.instant("expires_at"),
                                    ),
                                )
                            } finally {
                                plaintext.fill(0)
                            }
                        }
                    }
                }
            }
        }

    override fun loadRecoverable(sessionId: String, now: Instant): DurableOnlineSession? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, version, status, state_iv, state_ciphertext, created_at,
                       started_at, finished_at, expires_at
                FROM duel_sessions
                WHERE id = ?
                  AND state_iv IS NOT NULL
                  AND state_ciphertext IS NOT NULL
                  AND (expires_at IS NULL OR expires_at > ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setInstant(2, now)
                statement.executeQuery().use { results ->
                    if (results.next()) {
                        val plaintext = cipher.decrypt(
                            sessionId,
                            EncryptedOnlineState(
                                iv = results.getBytes("state_iv"),
                                ciphertext = results.getBytes("state_ciphertext"),
                            ),
                        )
                        try {
                            DurableOnlineSession(
                                sessionId = sessionId,
                                revision = results.getLong("version"),
                                status = results.getString("status"),
                                stateJson = plaintext.toString(Charsets.UTF_8),
                                createdAt = results.instant("created_at")!!,
                                startedAt = results.instant("started_at"),
                                finishedAt = results.instant("finished_at"),
                                expiresAt = results.instant("expires_at"),
                            )
                        } finally {
                            plaintext.fill(0)
                        }
                    } else {
                        null
                    }
                }
            }
        }

    override fun <T> coordinate(
        sessionId: String,
        now: Instant,
        includeExpired: Boolean,
        operation: (DurableOnlineSession) -> DurableSessionCoordination<T>,
    ): T? = dataSource.transaction { connection ->
        val current = connection.prepareStatement(
            """
            SELECT id, version, status, state_iv, state_ciphertext, created_at,
                   started_at, finished_at, expires_at
            FROM duel_sessions
            WHERE id = ?
              AND state_iv IS NOT NULL
              AND state_ciphertext IS NOT NULL
              AND (? OR expires_at IS NULL OR expires_at > ?)
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setBoolean(2, includeExpired)
            statement.setInstant(3, now)
            statement.executeQuery().use { results ->
                if (results.next()) decrypt(results) else null
            }
        } ?: return@transaction null
        val coordinated = operation(current)
        require(coordinated.session.sessionId == current.sessionId) {
            "Coordinated online session identity must not change"
        }
        require(coordinated.session.revision >= current.revision) {
            "Coordinated online session revision must not move backwards"
        }
        if (coordinated.session.revision > current.revision) {
            update(connection, coordinated.session, current.revision)
        }
        coordinated.result
    }

    override fun create(session: DurableOnlineSession) {
        require(session.revision >= 0) { "Initial online session revision must not be negative" }
        dataSource.transaction { connection -> create(connection, session) }
    }

    internal fun create(connection: Connection, session: DurableOnlineSession) {
        require(session.revision >= 0) { "Initial online session revision must not be negative" }
        val encrypted = encrypt(session)
        try {
            connection.prepareStatement(
                """
                INSERT INTO duel_sessions(
                    id, mode, status, config_json, version, created_at, started_at,
                    finished_at, state_iv, state_ciphertext, expires_at
                ) VALUES (?, 'ONLINE_DUEL', ?, '{}', ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, session.sessionId)
                statement.setString(2, session.status)
                statement.setLong(3, session.revision)
                statement.setInstant(4, session.createdAt)
                statement.setInstantOrNull(5, session.startedAt)
                statement.setInstantOrNull(6, session.finishedAt)
                statement.setBytes(7, encrypted.iv)
                statement.setBytes(8, encrypted.ciphertext)
                statement.setInstantOrNull(9, session.expiresAt)
                statement.executeUpdate()
            }
        } finally {
            encrypted.wipe()
        }
    }

    override fun update(session: DurableOnlineSession, expectedRevision: Long) {
        require(session.revision > expectedRevision) { "Online session revision must advance" }
        dataSource.transaction { connection -> update(connection, session, expectedRevision) }
    }

    override fun transferLegacyMembership(
        sessionId: String,
        platformPlayerId: String,
        presentedTokenHash: String,
        commandId: String,
        requestFingerprint: String,
        now: Instant,
        transfer: (DurableOnlineSession, legacyPlayerId: String) -> DurableOnlineSession,
    ): LegacyMembershipTransferPersistenceResult = dataSource.transaction { connection ->
        existingLegacyMigration(connection, sessionId, platformPlayerId)?.let { existing ->
            existing.requireReplay(commandId, requestFingerprint)
            return@transaction LegacyMembershipTransferPersistenceResult.REPLAYED
        }

        val token = legacyRefreshToken(connection, presentedTokenHash)
            ?: return@transaction LegacyMembershipTransferPersistenceResult.REJECTED

        // A concurrent first request can commit while this request waits on the token-family lock.
        existingLegacyMigration(connection, sessionId, platformPlayerId)?.let { existing ->
            existing.requireReplay(commandId, requestFingerprint)
            return@transaction LegacyMembershipTransferPersistenceResult.REPLAYED
        }
        if (
            token.consumedAt != null ||
            token.revokedAt != null ||
            token.tokenExpiresAt <= now ||
            token.familyExpiresAt <= now
        ) {
            if (token.revokedAt == null) {
                connection.prepareStatement(
                    "UPDATE refresh_token_families SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
                ).use { statement ->
                    statement.setInstant(1, now)
                    statement.setString(2, token.familyId)
                    statement.executeUpdate()
                }
            }
            return@transaction LegacyMembershipTransferPersistenceResult.REJECTED
        }

        val current = lockedSession(connection, sessionId, now)
            ?: return@transaction LegacyMembershipTransferPersistenceResult.REJECTED
        val updated = transfer(current, token.playerId)
        require(updated.sessionId == current.sessionId) {
            "Legacy membership migration must not change the session identity"
        }
        require(updated.revision == current.revision + 1L) {
            "Legacy membership migration must advance the session revision exactly once"
        }
        update(connection, updated, current.revision)

        val consumed = connection.prepareStatement(
            "UPDATE refresh_tokens SET consumed_at = ? WHERE token_hash = ? AND consumed_at IS NULL",
        ).use { statement ->
            statement.setInstant(1, now)
            statement.setString(2, presentedTokenHash)
            statement.executeUpdate()
        }
        check(consumed == 1) { "Legacy refresh credential changed during locked migration" }
        connection.prepareStatement(
            "UPDATE refresh_token_families SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
        ).use { statement ->
            statement.setInstant(1, now)
            statement.setString(2, token.familyId)
            check(statement.executeUpdate() == 1) {
                "Legacy refresh family changed during locked migration"
            }
        }
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
            statement.setString(3, token.playerId)
            statement.setString(4, commandId)
            statement.setString(5, requestFingerprint)
            statement.setInstant(6, now)
            statement.executeUpdate()
        }
        LegacyMembershipTransferPersistenceResult.TRANSFERRED
    }

    private fun update(connection: Connection, session: DurableOnlineSession, expectedRevision: Long) {
        val encrypted = encrypt(session)
        try {
            val changed = connection.prepareStatement(
                """
                UPDATE duel_sessions
                SET status = ?, version = ?, started_at = ?, finished_at = ?,
                    state_iv = ?, state_ciphertext = ?, expires_at = ?
                WHERE id = ? AND version = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, session.status)
                statement.setLong(2, session.revision)
                statement.setInstantOrNull(3, session.startedAt)
                statement.setInstantOrNull(4, session.finishedAt)
                statement.setBytes(5, encrypted.iv)
                statement.setBytes(6, encrypted.ciphertext)
                statement.setInstantOrNull(7, session.expiresAt)
                statement.setString(8, session.sessionId)
                statement.setLong(9, expectedRevision)
                statement.executeUpdate()
            }
            if (changed != 1) throw OnlineSessionRevisionConflictException(session.sessionId, expectedRevision)
            insertOnlineSessionEvent(
                connection = connection,
                sessionId = session.sessionId,
                eventType = SessionChangedEventType,
                sessionRevision = session.revision,
                createdAt = Instant.now(),
            )
        } finally {
            encrypted.wipe()
        }
    }

    private fun lockedSession(
        connection: Connection,
        sessionId: String,
        now: Instant,
    ): DurableOnlineSession? = connection.prepareStatement(
        """
        SELECT id, version, status, state_iv, state_ciphertext, created_at,
               started_at, finished_at, expires_at
        FROM duel_sessions
        WHERE id = ?
          AND state_iv IS NOT NULL
          AND state_ciphertext IS NOT NULL
          AND (expires_at IS NULL OR expires_at > ?)
        FOR UPDATE
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, sessionId)
        statement.setInstant(2, now)
        statement.executeQuery().use { results -> if (results.next()) decrypt(results) else null }
    }

    private fun legacyRefreshToken(
        connection: Connection,
        tokenHash: String,
    ): LegacyRefreshTokenRecord? = connection.prepareStatement(
        """
        SELECT families.id, families.player_id, families.expires_at AS family_expires_at,
               families.revoked_at, tokens.expires_at AS token_expires_at, tokens.consumed_at
        FROM refresh_tokens tokens
        JOIN refresh_token_families families ON families.id = tokens.family_id
        WHERE tokens.token_hash = ?
        FOR UPDATE
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, tokenHash)
        statement.executeQuery().use { results ->
            if (!results.next()) return@use null
            LegacyRefreshTokenRecord(
                familyId = results.getString("id"),
                playerId = results.getString("player_id"),
                familyExpiresAt = requireNotNull(results.instant("family_expires_at")),
                tokenExpiresAt = requireNotNull(results.instant("token_expires_at")),
                revokedAt = results.instant("revoked_at"),
                consumedAt = results.instant("consumed_at"),
            )
        }
    }

    private fun existingLegacyMigration(
        connection: Connection,
        sessionId: String,
        platformPlayerId: String,
    ): LegacyMigrationRecord? = connection.prepareStatement(
        """
        SELECT command_id, request_fingerprint
        FROM legacy_online_session_migrations
        WHERE session_id = ? AND platform_player_id = ?
        FOR UPDATE
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, sessionId)
        statement.setString(2, platformPlayerId)
        statement.executeQuery().use { results ->
            if (results.next()) {
                LegacyMigrationRecord(
                    commandId = results.getString("command_id"),
                    requestFingerprint = results.getString("request_fingerprint"),
                )
            } else {
                null
            }
        }
    }

    override fun delete(sessionId: String) {
        dataSource.transaction { connection ->
            connection.prepareStatement("DELETE FROM duel_events WHERE session_id = ?").use { statement ->
                statement.setString(1, sessionId)
                statement.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM duel_sessions WHERE id = ?").use { statement ->
                statement.setString(1, sessionId)
                statement.executeUpdate()
            }
        }
    }

    override fun close() {
        cipher.close()
    }

    private fun encrypt(session: DurableOnlineSession): EncryptedOnlineState {
        val plaintext = session.stateJson.toByteArray(Charsets.UTF_8)
        return try {
            cipher.encrypt(session.sessionId, plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun decrypt(results: java.sql.ResultSet): DurableOnlineSession {
        val sessionId = results.getString("id")
        val plaintext = cipher.decrypt(
            sessionId,
            EncryptedOnlineState(
                iv = results.getBytes("state_iv"),
                ciphertext = results.getBytes("state_ciphertext"),
            ),
        )
        return try {
            DurableOnlineSession(
                sessionId = sessionId,
                revision = results.getLong("version"),
                status = results.getString("status"),
                stateJson = plaintext.toString(Charsets.UTF_8),
                createdAt = results.instant("created_at")!!,
                startedAt = results.instant("started_at"),
                finishedAt = results.instant("finished_at"),
                expiresAt = results.instant("expires_at"),
            )
        } finally {
            plaintext.fill(0)
        }
    }
}

private data class LegacyRefreshTokenRecord(
    val familyId: String,
    val playerId: String,
    val familyExpiresAt: Instant,
    val tokenExpiresAt: Instant,
    val revokedAt: Instant?,
    val consumedAt: Instant?,
)

private data class LegacyMigrationRecord(
    val commandId: String,
    val requestFingerprint: String,
) {
    fun requireReplay(expectedCommandId: String, expectedFingerprint: String) {
        if (commandId != expectedCommandId || requestFingerprint != expectedFingerprint) {
            throw LegacyMembershipMigrationConflictException()
        }
    }
}

private fun EncryptedOnlineState.wipe() {
    iv.fill(0)
    ciphertext.fill(0)
}

private fun java.sql.ResultSet.instant(column: String): Instant? =
    getObject(column, java.time.OffsetDateTime::class.java)?.toInstant()

private fun java.sql.PreparedStatement.setInstant(index: Int, value: Instant) {
    setObject(index, value.atOffset(ZoneOffset.UTC))
}

private fun java.sql.PreparedStatement.setInstantOrNull(index: Int, value: Instant?) {
    if (value == null) setObject(index, null) else setInstant(index, value)
}

private inline fun <T> DataSource.transaction(block: (Connection) -> T): T = connection.use { connection ->
    val previousAutoCommit = connection.autoCommit
    connection.autoCommit = false
    try {
        block(connection).also { connection.commit() }
    } catch (error: Exception) {
        connection.rollback()
        throw error
    } finally {
        connection.autoCommit = previousAutoCommit
    }
}
