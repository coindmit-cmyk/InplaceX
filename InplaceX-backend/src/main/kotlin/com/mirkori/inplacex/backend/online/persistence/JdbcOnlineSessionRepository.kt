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

class OnlineSessionRevisionConflictException(sessionId: String, expectedRevision: Long) :
    IllegalStateException("Online session $sessionId changed from revision $expectedRevision")

interface OnlineSessionRepository : AutoCloseable {
    fun deleteExpired(now: Instant)
    fun loadRecoverable(now: Instant): List<DurableOnlineSession>
    fun loadRecoverable(sessionId: String, now: Instant): DurableOnlineSession?
    fun create(session: DurableOnlineSession)
    fun update(session: DurableOnlineSession, expectedRevision: Long)
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
        val encrypted = encrypt(session)
        try {
            val changed = dataSource.transaction { connection ->
                connection.prepareStatement(
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
            }
            if (changed != 1) throw OnlineSessionRevisionConflictException(session.sessionId, expectedRevision)
        } finally {
            encrypted.wipe()
        }
    }

    override fun delete(sessionId: String) {
        dataSource.transaction { connection ->
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
