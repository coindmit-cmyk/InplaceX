package com.mirkori.inplacex.backend.online.persistence

import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource

data class DurableMatchmakingTicket(
    val ticketId: String,
    val ownerPlayerId: String,
    val commandId: String,
    val mode: String,
    val rulesJson: String,
    val status: String,
    val sessionId: String?,
    val matchedWithBot: Boolean,
    val createdAt: Instant,
    val expiresAt: Instant,
)

data class DurablePrivateInvite(
    val inviteCode: String,
    val ownerPlayerId: String,
    val guestPlayerId: String?,
    val createCommandId: String,
    val acceptCommandId: String?,
    val status: String,
    val rulesJson: String,
    val sessionId: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
)

interface OnlineLobbyRepository : AutoCloseable {
    fun deleteLinksToExpiredSessions(now: Instant)
    fun loadTickets(now: Instant): List<DurableMatchmakingTicket>
    fun saveTickets(tickets: Collection<DurableMatchmakingTicket>)
    fun deleteTickets(ticketIds: Collection<String>)
    fun createSessionAndSaveTickets(
        session: DurableOnlineSession,
        tickets: Collection<DurableMatchmakingTicket>,
    )
    fun loadInvites(retainedAfter: Instant): List<DurablePrivateInvite>
    fun saveInvite(invite: DurablePrivateInvite)
    fun createSessionAndSaveInvite(session: DurableOnlineSession, invite: DurablePrivateInvite)
    fun deleteInvites(inviteCodes: Collection<String>)
    override fun close() = Unit
}

/** Durable PostgreSQL boundary for matchmaking and private-room discovery state. */
class JdbcOnlineLobbyRepository(
    private val dataSource: DataSource,
    private val sessionRepository: JdbcOnlineSessionRepository,
) : OnlineLobbyRepository {
    override fun deleteLinksToExpiredSessions(now: Instant) {
        dataSource.transaction { connection ->
            listOf("matchmaking_tickets", "private_duel_invites")
                .forEach { table ->
                    connection.prepareStatement(
                        """
                        DELETE FROM $table
                        WHERE session_id IN (
                            SELECT id FROM duel_sessions
                            WHERE expires_at IS NOT NULL AND expires_at <= ?
                        )
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setInstant(1, now)
                        statement.executeUpdate()
                    }
                }
        }
    }

    override fun loadTickets(now: Instant): List<DurableMatchmakingTicket> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, player_id, command_id, mode, rules_json, status, session_id,
                       matched_with_bot, created_at, expires_at
                FROM matchmaking_tickets
                WHERE expires_at > ?
                ORDER BY created_at, id
                """.trimIndent(),
            ).use { statement ->
                statement.setInstant(1, now)
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) {
                            add(
                                DurableMatchmakingTicket(
                                    ticketId = results.getString("id"),
                                    ownerPlayerId = results.getString("player_id"),
                                    commandId = requireNotNull(results.getString("command_id")) {
                                        "Durable matchmaking ticket is missing command id"
                                    },
                                    mode = results.getString("mode"),
                                    rulesJson = requireNotNull(results.getString("rules_json")) {
                                        "Durable matchmaking ticket is missing rules"
                                    },
                                    status = results.getString("status"),
                                    sessionId = results.getString("session_id"),
                                    matchedWithBot = results.getBoolean("matched_with_bot"),
                                    createdAt = results.instant("created_at"),
                                    expiresAt = results.instant("expires_at"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    override fun saveTickets(tickets: Collection<DurableMatchmakingTicket>) {
        if (tickets.isEmpty()) return
        dataSource.transaction { connection ->
            tickets.forEach { ticket -> saveTicket(connection, ticket) }
        }
    }

    override fun createSessionAndSaveTickets(
        session: DurableOnlineSession,
        tickets: Collection<DurableMatchmakingTicket>,
    ) {
        require(tickets.isNotEmpty())
        dataSource.transaction { connection ->
            sessionRepository.create(connection, session)
            tickets.forEach { ticket -> saveTicket(connection, ticket) }
        }
    }

    private fun saveTicket(connection: Connection, ticket: DurableMatchmakingTicket) {
        val changed = connection.prepareStatement(
            """
            UPDATE matchmaking_tickets
            SET player_id = ?, command_id = ?, mode = ?, rules_json = ?, status = ?,
                session_id = ?, matched_with_bot = ?, expires_at = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.bindTicket(ticket, includeIdentity = false)
            statement.executeUpdate()
        }
        if (changed == 0) {
            connection.prepareStatement(
                """
                INSERT INTO matchmaking_tickets(
                    player_id, command_id, mode, rules_json, status, session_id,
                    matched_with_bot, expires_at, id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.bindTicket(ticket, includeIdentity = true)
                statement.executeUpdate()
            }
        }
    }

    override fun deleteTickets(ticketIds: Collection<String>) {
        deleteEach("DELETE FROM matchmaking_tickets WHERE id = ?", ticketIds)
    }

    override fun loadInvites(retainedAfter: Instant): List<DurablePrivateInvite> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT invite_code, owner_player_id, guest_player_id, create_command_id,
                       accept_command_id, status, rules_json, session_id, created_at, expires_at
                FROM private_duel_invites
                WHERE expires_at > ?
                ORDER BY created_at, invite_code
                """.trimIndent(),
            ).use { statement ->
                statement.setInstant(1, retainedAfter)
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) {
                            add(
                                DurablePrivateInvite(
                                    inviteCode = results.getString("invite_code"),
                                    ownerPlayerId = results.getString("owner_player_id"),
                                    guestPlayerId = results.getString("guest_player_id"),
                                    createCommandId = results.getString("create_command_id"),
                                    acceptCommandId = results.getString("accept_command_id"),
                                    status = results.getString("status"),
                                    rulesJson = results.getString("rules_json"),
                                    sessionId = results.getString("session_id"),
                                    createdAt = results.instant("created_at"),
                                    expiresAt = results.instant("expires_at"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    override fun saveInvite(invite: DurablePrivateInvite) {
        dataSource.transaction { connection ->
            saveInvite(connection, invite)
        }
    }

    override fun createSessionAndSaveInvite(session: DurableOnlineSession, invite: DurablePrivateInvite) {
        dataSource.transaction { connection ->
            sessionRepository.create(connection, session)
            saveInvite(connection, invite)
        }
    }

    private fun saveInvite(connection: Connection, invite: DurablePrivateInvite) {
        val changed = connection.prepareStatement(
            """
            UPDATE private_duel_invites
            SET owner_player_id = ?, guest_player_id = ?, create_command_id = ?,
                accept_command_id = ?, status = ?, rules_json = ?, session_id = ?,
                expires_at = ?, updated_at = CURRENT_TIMESTAMP
            WHERE invite_code = ?
            """.trimIndent(),
        ).use { statement ->
            statement.bindInvite(invite, includeCreatedAt = false)
            statement.executeUpdate()
        }
        if (changed == 0) {
            connection.prepareStatement(
                """
                INSERT INTO private_duel_invites(
                    owner_player_id, guest_player_id, create_command_id, accept_command_id,
                    status, rules_json, session_id, expires_at, invite_code, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.bindInvite(invite, includeCreatedAt = true)
                statement.executeUpdate()
            }
        }
    }

    override fun deleteInvites(inviteCodes: Collection<String>) {
        deleteEach("DELETE FROM private_duel_invites WHERE invite_code = ?", inviteCodes)
    }

    private fun deleteEach(sql: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        dataSource.transaction { connection ->
            connection.prepareStatement(sql).use { statement ->
                ids.forEach { id ->
                    statement.setString(1, id)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }
}

private fun PreparedStatement.bindTicket(ticket: DurableMatchmakingTicket, includeIdentity: Boolean) {
    setString(1, ticket.ownerPlayerId)
    setString(2, ticket.commandId)
    setString(3, ticket.mode)
    setString(4, ticket.rulesJson)
    setString(5, ticket.status)
    setString(6, ticket.sessionId)
    setBoolean(7, ticket.matchedWithBot)
    setInstant(8, ticket.expiresAt)
    setString(9, ticket.ticketId)
    if (includeIdentity) setInstant(10, ticket.createdAt)
}

private fun PreparedStatement.bindInvite(invite: DurablePrivateInvite, includeCreatedAt: Boolean) {
    setString(1, invite.ownerPlayerId)
    setString(2, invite.guestPlayerId)
    setString(3, invite.createCommandId)
    setString(4, invite.acceptCommandId)
    setString(5, invite.status)
    setString(6, invite.rulesJson)
    setString(7, invite.sessionId)
    setInstant(8, invite.expiresAt)
    setString(9, invite.inviteCode)
    if (includeCreatedAt) {
        setInstant(10, invite.createdAt)
        setInstant(11, invite.createdAt)
    }
}

private fun java.sql.ResultSet.instant(column: String): Instant =
    getObject(column, java.time.OffsetDateTime::class.java).toInstant()

private fun PreparedStatement.setInstant(index: Int, value: Instant) {
    setObject(index, value.atOffset(ZoneOffset.UTC))
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
