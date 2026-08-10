package com.mirkori.inplacex.backend.online.persistence

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
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
    val targetPlayerId: String? = null,
    val guestPlayerId: String?,
    val createCommandId: String,
    val acceptCommandId: String?,
    val status: String,
    val rulesJson: String,
    val sessionId: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
)

data class DurableTicketCoordination(
    val ticket: DurableMatchmakingTicket,
    val matchedPeer: DurableMatchmakingTicket? = null,
    val createdSession: Boolean = false,
)

data class DurableInviteCoordination(
    val invite: DurablePrivateInvite,
    val createdSession: Boolean = false,
)

interface OnlineLobbyRepository : AutoCloseable {
    fun deleteLinksToExpiredSessions(now: Instant)
    fun loadTickets(now: Instant): List<DurableMatchmakingTicket>
    fun loadTicket(ticketId: String): DurableMatchmakingTicket?
    fun loadSearchingTickets(eligibleBefore: Instant, now: Instant): List<DurableMatchmakingTicket>
    fun coordinateTicket(
        candidate: DurableMatchmakingTicket,
        createSession: (DurableMatchmakingTicket) -> DurableOnlineSession,
    ): DurableTicketCoordination
    fun coordinateBotFallback(
        ticketId: String,
        eligibleBefore: Instant,
        createSession: (DurableMatchmakingTicket) -> DurableOnlineSession,
    ): DurableTicketCoordination?
    fun deleteTickets(ticketIds: Collection<String>)
    fun loadInvites(retainedAfter: Instant): List<DurablePrivateInvite>
    fun loadIncomingInvites(targetPlayerId: String, now: Instant): List<DurablePrivateInvite>
    fun loadInvite(inviteCode: String): DurablePrivateInvite?
    fun coordinateInvite(candidate: DurablePrivateInvite): DurablePrivateInvite?
    fun coordinateInviteAcceptance(
        inviteCode: String,
        guestPlayerId: String,
        commandId: String,
        now: Instant,
        createSession: (DurablePrivateInvite) -> DurableOnlineSession,
    ): DurableInviteCoordination?
    fun expireInvite(inviteCode: String, now: Instant): DurablePrivateInvite?
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
                            add(results.ticket())
                        }
                    }
                }
            }
        }

    override fun loadTicket(ticketId: String): DurableMatchmakingTicket? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT id, player_id, command_id, mode, rules_json, status, session_id,
                       matched_with_bot, created_at, expires_at
                FROM matchmaking_tickets
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, ticketId)
                statement.executeQuery().use { results ->
                    if (results.next()) results.ticket() else null
                }
            }
        }

    override fun loadSearchingTickets(
        eligibleBefore: Instant,
        now: Instant,
    ): List<DurableMatchmakingTicket> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT id, player_id, command_id, mode, rules_json, status, session_id,
                   matched_with_bot, created_at, expires_at
            FROM matchmaking_tickets
            WHERE status = 'SEARCHING' AND created_at <= ? AND expires_at > ?
            ORDER BY created_at, id
            """.trimIndent(),
        ).use { statement ->
            statement.setInstant(1, eligibleBefore)
            statement.setInstant(2, now)
            statement.executeQuery().use { results ->
                buildList {
                    while (results.next()) add(results.ticket())
                }
            }
        }
    }

    override fun coordinateTicket(
        candidate: DurableMatchmakingTicket,
        createSession: (DurableMatchmakingTicket) -> DurableOnlineSession,
    ): DurableTicketCoordination {
        require(candidate.status == "SEARCHING" && candidate.sessionId == null)
        return try {
            dataSource.transaction { connection ->
                connection.ticketByCommand(candidate.ownerPlayerId, candidate.commandId, lock = true)?.let {
                    return@transaction DurableTicketCoordination(it)
                }
                val peer = connection.prepareStatement(
                    """
                    SELECT id, player_id, command_id, mode, rules_json, status, session_id,
                           matched_with_bot, created_at, expires_at
                    FROM matchmaking_tickets
                    WHERE status = 'SEARCHING' AND mode = ? AND rules_json = ?
                      AND player_id <> ? AND expires_at > ?
                    ORDER BY created_at, id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, candidate.mode)
                    statement.setString(2, candidate.rulesJson)
                    statement.setString(3, candidate.ownerPlayerId)
                    statement.setInstant(4, candidate.createdAt)
                    statement.executeQuery().use { results ->
                        if (results.next()) results.ticket() else null
                    }
                }
                if (peer == null) {
                    insertTicket(connection, candidate)
                    DurableTicketCoordination(candidate)
                } else {
                    val session = createSession(peer)
                    sessionRepository.create(connection, session)
                    val matchedPeer = peer.matched(session.sessionId, withBot = false)
                    val matchedCandidate = candidate.matched(session.sessionId, withBot = false)
                    updateTicket(connection, matchedPeer)
                    insertTicket(connection, matchedCandidate)
                    DurableTicketCoordination(
                        ticket = matchedCandidate,
                        matchedPeer = matchedPeer,
                        createdSession = true,
                    )
                }
            }
        } catch (error: SQLException) {
            if (error.sqlState == UniqueViolationSqlState) {
                dataSource.connection.use { connection ->
                    connection.ticketByCommand(candidate.ownerPlayerId, candidate.commandId, lock = false)
                }?.let { return DurableTicketCoordination(it) }
            }
            throw error
        }
    }

    override fun coordinateBotFallback(
        ticketId: String,
        eligibleBefore: Instant,
        createSession: (DurableMatchmakingTicket) -> DurableOnlineSession,
    ): DurableTicketCoordination? = dataSource.transaction { connection ->
        val ticket = connection.ticketById(ticketId, lock = true) ?: return@transaction null
        if (ticket.status != "SEARCHING" || ticket.createdAt > eligibleBefore) {
            return@transaction DurableTicketCoordination(ticket)
        }
        val session = createSession(ticket)
        sessionRepository.create(connection, session)
        val matched = ticket.matched(session.sessionId, withBot = true)
        updateTicket(connection, matched)
        DurableTicketCoordination(ticket = matched, createdSession = true)
    }

    private fun updateTicket(connection: Connection, ticket: DurableMatchmakingTicket): Int =
        connection.prepareStatement(
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

    private fun insertTicket(connection: Connection, ticket: DurableMatchmakingTicket) {
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

    override fun deleteTickets(ticketIds: Collection<String>) {
        deleteEach("DELETE FROM matchmaking_tickets WHERE id = ?", ticketIds)
    }

    override fun loadInvites(retainedAfter: Instant): List<DurablePrivateInvite> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT invite_code, owner_player_id, target_player_id, guest_player_id, create_command_id,
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
                                    targetPlayerId = results.getString("target_player_id"),
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

    override fun loadIncomingInvites(
        targetPlayerId: String,
        now: Instant,
    ): List<DurablePrivateInvite> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT invite_code, owner_player_id, target_player_id, guest_player_id,
                   create_command_id, accept_command_id, status, rules_json, session_id,
                   created_at, expires_at
            FROM private_duel_invites
            WHERE target_player_id = ? AND status = 'WAITING' AND expires_at > ?
            ORDER BY created_at DESC, invite_code
            LIMIT 50
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, targetPlayerId)
            statement.setInstant(2, now)
            statement.executeQuery().use { results ->
                buildList { while (results.next()) add(results.invite()) }
            }
        }
    }

    override fun loadInvite(inviteCode: String): DurablePrivateInvite? =
        dataSource.connection.use { connection -> connection.inviteByCode(inviteCode, lock = false) }

    override fun coordinateInvite(candidate: DurablePrivateInvite): DurablePrivateInvite? {
        return try {
            dataSource.transaction { connection ->
                connection.inviteByCreateCommand(
                    candidate.ownerPlayerId,
                    candidate.createCommandId,
                    lock = true,
                ) ?: insertInvite(connection, candidate).let { candidate }
            }
        } catch (error: SQLException) {
            if (error.sqlState == UniqueViolationSqlState) {
                return dataSource.connection.use { connection ->
                    connection.inviteByCreateCommand(
                        candidate.ownerPlayerId,
                        candidate.createCommandId,
                        lock = false,
                    )
                }
            }
            throw error
        }
    }

    override fun coordinateInviteAcceptance(
        inviteCode: String,
        guestPlayerId: String,
        commandId: String,
        now: Instant,
        createSession: (DurablePrivateInvite) -> DurableOnlineSession,
    ): DurableInviteCoordination? {
        return try {
            dataSource.transaction { connection ->
                connection.inviteByAcceptCommand(guestPlayerId, commandId, lock = true)?.let {
                    return@transaction DurableInviteCoordination(it)
                }
                val current = connection.inviteByCode(inviteCode, lock = true)
                    ?: return@transaction null
                if (
                    current.status != "WAITING" ||
                    current.ownerPlayerId == guestPlayerId ||
                    current.targetPlayerId?.let { it != guestPlayerId } == true
                ) {
                    return@transaction DurableInviteCoordination(current)
                }
                if (!now.isBefore(current.expiresAt)) {
                    val expired = current.copy(status = "EXPIRED")
                    updateInvite(connection, expired)
                    return@transaction DurableInviteCoordination(expired)
                }
                val session = createSession(current)
                sessionRepository.create(connection, session)
                val matched = current.copy(
                    guestPlayerId = guestPlayerId,
                    acceptCommandId = commandId,
                    status = "MATCHED",
                    sessionId = session.sessionId,
                )
                updateInvite(connection, matched)
                DurableInviteCoordination(matched, createdSession = true)
            }
        } catch (error: SQLException) {
            if (error.sqlState == UniqueViolationSqlState) {
                dataSource.connection.use { connection ->
                    connection.inviteByAcceptCommand(guestPlayerId, commandId, lock = false)
                }?.let { return DurableInviteCoordination(it) }
            }
            throw error
        }
    }

    override fun expireInvite(inviteCode: String, now: Instant): DurablePrivateInvite? =
        dataSource.transaction { connection ->
            val current = connection.inviteByCode(inviteCode, lock = true)
                ?: return@transaction null
            if (current.status == "WAITING" && !now.isBefore(current.expiresAt)) {
                current.copy(status = "EXPIRED").also { updateInvite(connection, it) }
            } else {
                current
            }
        }

    private fun insertInvite(connection: Connection, invite: DurablePrivateInvite) {
        connection.prepareStatement(
            """
            INSERT INTO private_duel_invites(
                owner_player_id, target_player_id, guest_player_id, create_command_id, accept_command_id,
                status, rules_json, session_id, expires_at, invite_code, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.bindInvite(invite, includeCreatedAt = true)
            statement.executeUpdate()
        }
    }

    private fun updateInvite(connection: Connection, invite: DurablePrivateInvite): Int =
        connection.prepareStatement(
            """
            UPDATE private_duel_invites
            SET owner_player_id = ?, target_player_id = ?, guest_player_id = ?, create_command_id = ?,
                accept_command_id = ?, status = ?, rules_json = ?, session_id = ?,
                expires_at = ?, updated_at = CURRENT_TIMESTAMP
            WHERE invite_code = ?
            """.trimIndent(),
        ).use { statement ->
            statement.bindInvite(invite, includeCreatedAt = false)
            statement.executeUpdate()
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

private fun Connection.ticketByCommand(
    playerId: String,
    commandId: String,
    lock: Boolean,
): DurableMatchmakingTicket? = prepareStatement(
    """
    SELECT id, player_id, command_id, mode, rules_json, status, session_id,
           matched_with_bot, created_at, expires_at
    FROM matchmaking_tickets
    WHERE player_id = ? AND command_id = ?
    ${if (lock) "FOR UPDATE" else ""}
    """.trimIndent(),
).use { statement ->
    statement.setString(1, playerId)
    statement.setString(2, commandId)
    statement.executeQuery().use { results -> if (results.next()) results.ticket() else null }
}

private fun Connection.ticketById(ticketId: String, lock: Boolean): DurableMatchmakingTicket? =
    prepareStatement(
        """
        SELECT id, player_id, command_id, mode, rules_json, status, session_id,
               matched_with_bot, created_at, expires_at
        FROM matchmaking_tickets
        WHERE id = ?
        ${if (lock) "FOR UPDATE" else ""}
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, ticketId)
        statement.executeQuery().use { results -> if (results.next()) results.ticket() else null }
    }

private fun Connection.inviteByCode(inviteCode: String, lock: Boolean): DurablePrivateInvite? =
    invite(
        where = "invite_code = ?",
        lock = lock,
    ) { statement -> statement.setString(1, inviteCode) }

private fun Connection.inviteByCreateCommand(
    ownerPlayerId: String,
    commandId: String,
    lock: Boolean,
): DurablePrivateInvite? = invite(
    where = "owner_player_id = ? AND create_command_id = ?",
    lock = lock,
) { statement ->
    statement.setString(1, ownerPlayerId)
    statement.setString(2, commandId)
}

private fun Connection.inviteByAcceptCommand(
    guestPlayerId: String,
    commandId: String,
    lock: Boolean,
): DurablePrivateInvite? = invite(
    where = "guest_player_id = ? AND accept_command_id = ?",
    lock = lock,
) { statement ->
    statement.setString(1, guestPlayerId)
    statement.setString(2, commandId)
}

private fun Connection.invite(
    where: String,
    lock: Boolean,
    bind: (PreparedStatement) -> Unit,
): DurablePrivateInvite? = prepareStatement(
    """
    SELECT invite_code, owner_player_id, target_player_id, guest_player_id, create_command_id,
           accept_command_id, status, rules_json, session_id, created_at, expires_at
    FROM private_duel_invites
    WHERE $where
    ${if (lock) "FOR UPDATE" else ""}
    """.trimIndent(),
).use { statement ->
    bind(statement)
    statement.executeQuery().use { results -> if (results.next()) results.invite() else null }
}

private fun ResultSet.ticket(): DurableMatchmakingTicket = DurableMatchmakingTicket(
    ticketId = getString("id"),
    ownerPlayerId = getString("player_id"),
    commandId = requireNotNull(getString("command_id")) {
        "Durable matchmaking ticket is missing command id"
    },
    mode = getString("mode"),
    rulesJson = requireNotNull(getString("rules_json")) {
        "Durable matchmaking ticket is missing rules"
    },
    status = getString("status"),
    sessionId = getString("session_id"),
    matchedWithBot = getBoolean("matched_with_bot"),
    createdAt = instant("created_at"),
    expiresAt = instant("expires_at"),
)

private fun ResultSet.invite(): DurablePrivateInvite = DurablePrivateInvite(
    inviteCode = getString("invite_code"),
    ownerPlayerId = getString("owner_player_id"),
    targetPlayerId = getString("target_player_id"),
    guestPlayerId = getString("guest_player_id"),
    createCommandId = getString("create_command_id"),
    acceptCommandId = getString("accept_command_id"),
    status = getString("status"),
    rulesJson = getString("rules_json"),
    sessionId = getString("session_id"),
    createdAt = instant("created_at"),
    expiresAt = instant("expires_at"),
)

private fun DurableMatchmakingTicket.matched(
    sessionId: String,
    withBot: Boolean,
): DurableMatchmakingTicket = copy(
    status = "MATCHED",
    sessionId = sessionId,
    matchedWithBot = withBot,
)

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
    setString(2, invite.targetPlayerId)
    setString(3, invite.guestPlayerId)
    setString(4, invite.createCommandId)
    setString(5, invite.acceptCommandId)
    setString(6, invite.status)
    setString(7, invite.rulesJson)
    setString(8, invite.sessionId)
    setInstant(9, invite.expiresAt)
    setString(10, invite.inviteCode)
    if (includeCreatedAt) {
        setInstant(11, invite.createdAt)
        setInstant(12, invite.createdAt)
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

private const val UniqueViolationSqlState = "23505"
