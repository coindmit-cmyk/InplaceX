package com.mirkori.inplacex.backend.online

import com.mirkori.inplacex.backend.bot.ServerBotPlayer
import com.mirkori.inplacex.backend.domain.duel.DuelCommandRejectedException
import com.mirkori.inplacex.backend.domain.duel.DuelCommandRejection
import com.mirkori.inplacex.backend.domain.duel.DuelMatch
import com.mirkori.inplacex.backend.domain.duel.DuelParticipant
import com.mirkori.inplacex.backend.domain.duel.DuelPhase
import com.mirkori.inplacex.backend.domain.duel.DuelPlayStyle
import com.mirkori.inplacex.backend.domain.duel.DuelSnapshot
import com.mirkori.inplacex.backend.online.persistence.DurableMatchmakingTicket
import com.mirkori.inplacex.backend.online.persistence.DurableOnlineSession
import com.mirkori.inplacex.backend.online.persistence.DurablePrivateInvite
import com.mirkori.inplacex.backend.online.persistence.DurableSessionCoordination
import com.mirkori.inplacex.backend.online.persistence.OnlineLobbyRepository
import com.mirkori.inplacex.backend.online.persistence.OnlineSessionRepository
import com.mirkori.inplacex.backend.online.persistence.OnlineSessionEventSequence
import com.mirkori.inplacex.backend.session.domain.MutableDuelCommand
import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.core.engine.GuessValidator
import com.mirkori.inplacex.core.model.GameConfig
import com.mirkori.inplacex.logging.InplaceXLogger
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

enum class OnlineMatchMode {
    CLASSIC,
    PRO,
    PRO_PLUS,
}

enum class MatchmakingStatus {
    SEARCHING,
    MATCHED,
    CANCELLED,
}

enum class PrivateInviteStatus {
    WAITING,
    MATCHED,
    EXPIRED,
}

enum class OnlineFriendPlayStyle {
    RACE,
    TURN_BASED,
}

data class OnlineMatchRules(
    val playStyle: OnlineFriendPlayStyle,
    val codeLength: Int,
) {
    init {
        require(codeLength in OnlineCodeLengthRange) {
            "codeLength must be in $OnlineCodeLengthRange"
        }
    }
}

data class MatchmakingTicket(
    val ticketId: String,
    val ownerPlayerId: String,
    val status: MatchmakingStatus,
    val sessionId: String?,
    val matchedWithBot: Boolean,
    val createdAt: Instant,
)

data class PrivateDuelInvite(
    val inviteCode: String,
    val status: PrivateInviteStatus,
    val sessionId: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val playStyle: OnlineFriendPlayStyle,
    val codeLength: Int,
    val allowDuplicates: Boolean,
    val maxConsecutiveDuplicateDigits: Int,
    val matchDurationSeconds: Long,
)

data class OnlineDuelAttempt(
    val actor: String,
    val exactMatches: Int,
    val number: Int,
    val ownGuess: String?,
)

data class OnlineDuelParticipant(
    val actor: String,
    val secretConfigured: Boolean,
    val attemptsUsed: Int,
    val attemptsLeft: Int?,
)

data class OnlineDuelSnapshot(
    val sessionId: String,
    val revision: Long,
    val phase: String,
    val currentTurn: String?,
    val winner: String?,
    val finishReason: String?,
    val playStyle: String,
    val codeLength: Int,
    val attemptLimit: Int?,
    val allowDuplicates: Boolean,
    val maxConsecutiveDuplicateDigits: Int?,
    val startedAtEpochMs: Long?,
    val deadlineAtEpochMs: Long?,
    val serverTimeEpochMs: Long,
    val attempts: List<OnlineDuelAttempt>,
    val participants: List<OnlineDuelParticipant>,
)

class OnlineMembershipRejectedException : IllegalStateException("online session membership rejected")
class OnlineRevisionConflictException(val current: OnlineDuelSnapshot) :
    IllegalStateException("online session revision conflict")
class OnlineCommandIdReusedException : IllegalStateException("online command id reused with a different payload")
class OnlineInviteUnavailableException : IllegalStateException("private duel invite is unavailable")

/**
 * Server-authoritative matchmaking and duel runtime.
 *
 * A ticket first waits for another authenticated player in the same mode.
 * Reading it after [botFallbackDelay] promotes it to a server-owned bot match.
 * Session membership and participant roles are created only inside this
 * service; callers can never provide either value.
 */
class AuthoritativeOnlineDuelService(
    private val clock: Clock = Clock.systemUTC(),
    private val botFallbackDelay: Duration = Duration.ofSeconds(5),
    private val privateInviteLifetime: Duration = Duration.ofMinutes(10),
    private val privateMatchDuration: Duration = Duration.ofMinutes(10),
    private val setupTimeout: Duration = Duration.ofMinutes(5),
    private val finishedSessionRetention: Duration = Duration.ofMinutes(15),
    private val ticketRetention: Duration = Duration.ofMinutes(30),
    private val inviteRetention: Duration = Duration.ofMinutes(15),
    sweepInterval: Duration? = null,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val logger: InplaceXLogger = InplaceXLogger(),
    private val sessionRepository: OnlineSessionRepository? = null,
    private val lobbyRepository: OnlineLobbyRepository? = null,
    private val sessionEvents: OnlineSessionEventSequence? = null,
) : AutoCloseable {
    private val lock = Any()
    private val tickets = mutableMapOf<String, TicketRecord>()
    private val ticketReplays = mutableMapOf<String, TicketReplay>()
    private val privateInvites = mutableMapOf<String, PrivateInviteRecord>()
    private val privateInviteCreateReplays = mutableMapOf<String, PrivateInviteCreateReplay>()
    private val privateInviteAcceptReplays = mutableMapOf<String, PrivateInviteAcceptReplay>()
    private val sessions = mutableMapOf<String, SessionRecord>()
    private val sweeper: ScheduledExecutorService?

    init {
        require(!botFallbackDelay.isNegative && !botFallbackDelay.isZero) {
            "botFallbackDelay must be positive"
        }
        require(!privateInviteLifetime.isNegative && !privateInviteLifetime.isZero) {
            "privateInviteLifetime must be positive"
        }
        require(!privateMatchDuration.isNegative && !privateMatchDuration.isZero) {
            "privateMatchDuration must be positive"
        }
        require(!setupTimeout.isNegative && !setupTimeout.isZero) { "setupTimeout must be positive" }
        require(!finishedSessionRetention.isNegative) { "finishedSessionRetention must not be negative" }
        require(!ticketRetention.isNegative && !ticketRetention.isZero) { "ticketRetention must be positive" }
        require(!inviteRetention.isNegative && !inviteRetention.isZero) { "inviteRetention must be positive" }
        require(lobbyRepository == null || sessionRepository != null) {
            "Durable online lobby requires durable session persistence"
        }
        val recoveredAt = clock.instant()
        lobbyRepository?.deleteLinksToExpiredSessions(recoveredAt)
        sessionRepository?.deleteExpired(recoveredAt)
        sessionRepository?.loadRecoverable(recoveredAt)?.forEach(::restoreSession)
        lobbyRepository?.loadTickets(recoveredAt)?.forEach(::cacheTicket)
        lobbyRepository?.loadInvites(recoveredAt.minus(inviteRetention))?.forEach(::cacheInvite)
        expirePrivateInvites(recoveredAt)
        if (lobbyRepository != null) {
            logger.info(
                tag = "OnlineRecovery",
                message = "online lobby state restored",
                attributes = mapOf(
                    "tickets" to tickets.size.toString(),
                    "invites" to privateInvites.size.toString(),
                ),
            )
        }
        sweeper = sweepInterval?.let { interval ->
            require(!interval.isNegative && !interval.isZero) { "sweepInterval must be positive" }
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "inplacex-online-sweeper").apply { isDaemon = true }
            }.also { executor ->
                executor.scheduleWithFixedDelay(
                    {
                        runCatching(::sweepExpiredState).onFailure { failure ->
                            logger.error("OnlineRetention", "online state sweep failed", throwable = failure)
                        }
                    },
                    interval.toMillis(),
                    interval.toMillis(),
                    TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    fun createTicket(
        playerId: String,
        commandId: String,
        mode: OnlineMatchMode,
        rules: OnlineMatchRules = OnlineMatchRules(
            playStyle = OnlineFriendPlayStyle.TURN_BASED,
            codeLength = 4,
        ),
    ): MatchmakingTicket {
        requireCanonicalUuid(playerId, "playerId")
        requireCanonicalUuid(commandId, "commandId")
        synchronized(lock) {
            val replayKey = "$playerId:$commandId"
            ticketReplays[replayKey]?.let { replay ->
                if (replay.mode != mode || replay.rules != rules) throw OnlineCommandIdReusedException()
                return refreshTicket(replay.ticketId)?.ticket
                    ?: throw NoSuchElementException("matchmaking ticket not found")
            }

            promoteExpiredTickets(clock.instant())
            if (lobbyRepository != null) {
                return createCoordinatedTicket(playerId, commandId, mode, rules)
            }
            val waitingPeer = tickets.values
                .asSequence()
                .filter { record ->
                    record.mode == mode &&
                        record.rules == rules &&
                        record.ticket.status == MatchmakingStatus.SEARCHING &&
                        record.ticket.ownerPlayerId != playerId
                }
                .minWithOrNull(compareBy<TicketRecord>({ it.ticket.createdAt }, { it.ticket.ticketId }))

            val ticketId = UUID.randomUUID().toString()
            val createdAt = clock.instant()
            var createdSession: SessionRecord? = null
            val previousWaitingTicket = waitingPeer?.ticket
            val ticket = if (waitingPeer == null) {
                MatchmakingTicket(
                    ticketId = ticketId,
                    ownerPlayerId = playerId,
                    status = MatchmakingStatus.SEARCHING,
                    sessionId = null,
                    matchedWithBot = false,
                    createdAt = createdAt,
                )
            } else {
                val session = createHumanSession(
                    rules = rules,
                    firstPlayerId = waitingPeer.ticket.ownerPlayerId,
                    secondPlayerId = playerId,
                )
                createdSession = session
                waitingPeer.ticket = waitingPeer.ticket.matched(session.sessionId, withBot = false)
                MatchmakingTicket(
                    ticketId = ticketId,
                    ownerPlayerId = playerId,
                    status = MatchmakingStatus.MATCHED,
                    sessionId = session.sessionId,
                    matchedWithBot = false,
                    createdAt = createdAt,
                )
            }
            val record = TicketRecord(mode, rules, commandId, ticket)
            try {
                createdSession?.let { session ->
                    session.persistNew()
                    sessions[session.sessionId] = session
                }
            } catch (failure: Throwable) {
                if (waitingPeer != null && previousWaitingTicket != null) {
                    waitingPeer.ticket = previousWaitingTicket
                }
                createdSession?.close()
                throw failure
            }
            tickets[ticketId] = record
            ticketReplays[replayKey] = TicketReplay(mode, rules, ticketId)
            return ticket
        }
    }

    fun readTicket(playerId: String, ticketId: String): MatchmakingTicket {
        requireCanonicalUuid(playerId, "playerId")
        synchronized(lock) {
            val record = refreshTicket(ticketId)
                ?: throw NoSuchElementException("matchmaking ticket not found")
            if (record.ticket.ownerPlayerId != playerId) throw OnlineMembershipRejectedException()
            if (record.ticket.status == MatchmakingStatus.SEARCHING) {
                promoteTicketToBotIfExpired(record, clock.instant())
            }
            return refreshTicket(ticketId)?.ticket
                ?: throw NoSuchElementException("matchmaking ticket not found")
        }
    }

    private fun createCoordinatedTicket(
        playerId: String,
        commandId: String,
        mode: OnlineMatchMode,
        rules: OnlineMatchRules,
    ): MatchmakingTicket {
        val createdAt = clock.instant()
        val candidate = TicketRecord(
            mode = mode,
            rules = rules,
            commandId = commandId,
            ticket = MatchmakingTicket(
                ticketId = UUID.randomUUID().toString(),
                ownerPlayerId = playerId,
                status = MatchmakingStatus.SEARCHING,
                sessionId = null,
                matchedWithBot = false,
                createdAt = createdAt,
            ),
        )
        var createdSession: SessionRecord? = null
        val result = try {
            requireNotNull(lobbyRepository).coordinateTicket(candidate.toDurable(ticketRetention)) { peer ->
                createHumanSession(
                    rules = rules,
                    firstPlayerId = peer.ownerPlayerId,
                    secondPlayerId = playerId,
                ).also { createdSession = it }.persistenceState()
            }
        } catch (failure: Throwable) {
            createdSession?.close()
            throw failure
        }
        val storedRules = OnlineLobbyRulesCodec.decode(result.ticket.rulesJson)
        if (result.ticket.mode != mode.name || storedRules != rules) {
            createdSession?.close()
            throw OnlineCommandIdReusedException()
        }
        result.matchedPeer?.let(::cacheTicket)
        val record = cacheTicket(result.ticket)
        createdSession?.let { session ->
            if (result.createdSession && result.ticket.sessionId == session.sessionId) {
                sessions[session.sessionId] = session
            } else {
                session.close()
            }
        }
        return record.ticket
    }

    fun createPrivateInvite(
        playerId: String,
        commandId: String,
        playStyle: OnlineFriendPlayStyle,
        codeLength: Int,
    ): PrivateDuelInvite {
        requireCanonicalUuid(playerId, "playerId")
        requireCanonicalUuid(commandId, "commandId")
        val rules = OnlineMatchRules(playStyle, codeLength)
        synchronized(lock) {
            val replayKey = "$playerId:$commandId"
            privateInviteCreateReplays[replayKey]?.let { replay ->
                val replayed = lobbyRepository?.loadInvite(replay.inviteCode)?.let(::cacheInvite)
                    ?: privateInvites.getValue(replay.inviteCode)
                if (
                    replayed.invite.playStyle != playStyle ||
                    replayed.invite.codeLength != codeLength
                ) {
                    throw OnlineCommandIdReusedException()
                }
                return replayed.invite
            }
            val createdAt = clock.instant()
            repeat(MaximumInviteCodeAttempts) {
                val inviteCode = nextInviteCode()
                val invite = PrivateDuelInvite(
                    inviteCode = inviteCode,
                    status = PrivateInviteStatus.WAITING,
                    sessionId = null,
                    createdAt = createdAt,
                    expiresAt = createdAt.plus(privateInviteLifetime),
                    playStyle = playStyle,
                    codeLength = codeLength,
                    allowDuplicates = true,
                    maxConsecutiveDuplicateDigits = OnlineMaximumConsecutiveDuplicateDigits,
                    matchDurationSeconds = privateMatchDuration.seconds,
                )
                val candidate = PrivateInviteRecord(
                    ownerPlayerId = playerId,
                    createCommandId = commandId,
                    invite = invite,
                )
                val stored = lobbyRepository?.coordinateInvite(candidate.toDurable())
                if (lobbyRepository != null && stored == null) return@repeat
                val coordinated = stored?.let(::cacheInvite) ?: candidate.also {
                    privateInvites[inviteCode] = it
                    privateInviteCreateReplays[replayKey] = PrivateInviteCreateReplay(
                        playStyle,
                        codeLength,
                        inviteCode,
                    )
                }
                if (
                    coordinated.invite.playStyle != playStyle ||
                    coordinated.invite.codeLength != codeLength
                ) {
                    throw OnlineCommandIdReusedException()
                }
                return coordinated.invite
            }
            throw IllegalStateException("could not allocate private duel invite code")
        }
    }

    fun readPrivateInvite(playerId: String, inviteCode: String): PrivateDuelInvite {
        requireCanonicalUuid(playerId, "playerId")
        requireInviteCode(inviteCode)
        synchronized(lock) {
            val record = lobbyRepository?.loadInvite(inviteCode)?.let(::cacheInvite)
                ?: privateInvites[inviteCode]
                ?: throw NoSuchElementException("private duel invite not found")
            expirePrivateInvite(record, clock.instant())
            val current = privateInvites.getValue(inviteCode)
            if (playerId != current.ownerPlayerId && playerId != current.guestPlayerId) {
                throw OnlineMembershipRejectedException()
            }
            return current.invite
        }
    }

    fun acceptPrivateInvite(
        playerId: String,
        commandId: String,
        inviteCode: String,
    ): PrivateDuelInvite {
        requireCanonicalUuid(playerId, "playerId")
        requireCanonicalUuid(commandId, "commandId")
        requireInviteCode(inviteCode)
        synchronized(lock) {
            if (lobbyRepository != null) {
                var createdSession: SessionRecord? = null
                val result = try {
                    lobbyRepository.coordinateInviteAcceptance(
                        inviteCode = inviteCode,
                        guestPlayerId = playerId,
                        commandId = commandId,
                        now = clock.instant(),
                    ) { stored ->
                        val record = inviteRecord(stored)
                        createHumanSession(
                            rules = record.invite.onlineRules(),
                            attemptLimit = null,
                            matchDuration = Duration.ofSeconds(record.invite.matchDurationSeconds),
                            firstPlayerId = record.ownerPlayerId,
                            secondPlayerId = playerId,
                        ).also { createdSession = it }.persistenceState()
                    }
                } catch (failure: Throwable) {
                    createdSession?.close()
                    throw failure
                } ?: throw NoSuchElementException("private duel invite not found")
                if (!result.createdSession) createdSession?.close()
                if (result.invite.inviteCode != inviteCode) throw OnlineCommandIdReusedException()
                val record = cacheInvite(result.invite)
                if (
                    record.ownerPlayerId == playerId ||
                    record.guestPlayerId != playerId ||
                    record.invite.status != PrivateInviteStatus.MATCHED
                ) {
                    throw OnlineInviteUnavailableException()
                }
                if (result.createdSession) {
                    val session = requireNotNull(createdSession)
                    sessions[session.sessionId] = session
                    logger.info(
                        tag = "OnlineInvite",
                        message = "private invite matched",
                        attributes = mapOf("sessionId" to session.sessionId),
                    )
                }
                return record.invite
            }
            val replayKey = "$playerId:$commandId"
            privateInviteAcceptReplays[replayKey]?.let { replay ->
                if (replay.inviteCode != inviteCode) throw OnlineCommandIdReusedException()
                return privateInvites.getValue(replay.inviteCode).invite
            }
            val record = privateInvites[inviteCode] ?: throw NoSuchElementException("private duel invite not found")
            expirePrivateInvite(record, clock.instant())
            if (playerId == record.ownerPlayerId) throw OnlineInviteUnavailableException()
            if (record.invite.status != PrivateInviteStatus.WAITING) {
                if (record.guestPlayerId == playerId && record.invite.status == PrivateInviteStatus.MATCHED) {
                    return record.invite
                }
                throw OnlineInviteUnavailableException()
            }
            val session = createHumanSession(
                rules = record.invite.onlineRules(),
                attemptLimit = null,
                matchDuration = Duration.ofSeconds(record.invite.matchDurationSeconds),
                firstPlayerId = record.ownerPlayerId,
                secondPlayerId = playerId,
            )
            val previousGuestPlayerId = record.guestPlayerId
            val previousAcceptCommandId = record.acceptCommandId
            val previousInvite = record.invite
            record.guestPlayerId = playerId
            record.acceptCommandId = commandId
            record.invite = record.invite.copy(
                status = PrivateInviteStatus.MATCHED,
                sessionId = session.sessionId,
            )
            try {
                session.persistNew()
            } catch (failure: Throwable) {
                record.guestPlayerId = previousGuestPlayerId
                record.acceptCommandId = previousAcceptCommandId
                record.invite = previousInvite
                session.close()
                throw failure
            }
            sessions[session.sessionId] = session
            privateInviteAcceptReplays[replayKey] = PrivateInviteAcceptReplay(inviteCode)
            return record.invite
        }
    }

    fun readSession(playerId: String, sessionId: String): OnlineDuelSnapshot =
        withSession(playerId, sessionId) { record -> record.snapshotFor(playerId) }

    fun submitSecret(
        playerId: String,
        sessionId: String,
        commandId: String,
        expectedRevision: Long,
        secret: String,
    ): OnlineDuelSnapshot =
        withSession(playerId, sessionId) { record ->
            record.submitSecret(
                playerId = playerId,
                commandId = commandId,
                expectedRevision = expectedRevision,
                secret = secret,
            )
        }

    fun submitGuess(
        playerId: String,
        sessionId: String,
        commandId: String,
        expectedRevision: Long,
        guess: String,
    ): OnlineDuelSnapshot =
        withSession(playerId, sessionId) { record ->
            record.submitGuess(
                playerId = playerId,
                commandId = commandId,
                expectedRevision = expectedRevision,
                guess = guess,
            )
        }

    private fun promoteExpiredTickets(now: Instant) {
        lobbyRepository
            ?.loadSearchingTickets(now.minus(botFallbackDelay), now)
            ?.forEach(::cacheTicket)
        tickets.values
            .filter { it.ticket.status == MatchmakingStatus.SEARCHING }
            .forEach { promoteTicketToBotIfExpired(it, now) }
    }

    private fun refreshTicket(ticketId: String): TicketRecord? =
        if (lobbyRepository == null) {
            tickets[ticketId]
        } else {
            lobbyRepository.loadTicket(ticketId)?.let(::cacheTicket)
        }

    private fun cacheTicket(stored: DurableMatchmakingTicket): TicketRecord {
        val rules = OnlineLobbyRulesCodec.decode(stored.rulesJson)
        val mode = enumValueOf<OnlineMatchMode>(stored.mode)
        val ticket = MatchmakingTicket(
            ticketId = stored.ticketId,
            ownerPlayerId = stored.ownerPlayerId,
            status = enumValueOf(stored.status),
            sessionId = stored.sessionId,
            matchedWithBot = stored.matchedWithBot,
            createdAt = stored.createdAt,
        )
        val record = tickets[stored.ticketId]?.also { existing ->
            check(existing.mode == mode && existing.rules == rules && existing.commandId == stored.commandId) {
                "Durable matchmaking ticket identity changed"
            }
            existing.ticket = ticket
        } ?: TicketRecord(mode, rules, stored.commandId, ticket).also { created ->
            tickets[stored.ticketId] = created
        }
        ticketReplays["${stored.ownerPlayerId}:${stored.commandId}"] =
            TicketReplay(mode, rules, stored.ticketId)
        return record
    }

    private fun inviteRecord(stored: DurablePrivateInvite): PrivateInviteRecord {
        val rules = OnlineLobbyRulesCodec.decodeInvite(stored.rulesJson)
        return PrivateInviteRecord(
            ownerPlayerId = stored.ownerPlayerId,
            guestPlayerId = stored.guestPlayerId,
            createCommandId = stored.createCommandId,
            acceptCommandId = stored.acceptCommandId,
            invite = PrivateDuelInvite(
                inviteCode = stored.inviteCode,
                status = enumValueOf(stored.status),
                sessionId = stored.sessionId,
                createdAt = stored.createdAt,
                expiresAt = stored.expiresAt,
                playStyle = rules.playStyle,
                codeLength = rules.codeLength,
                allowDuplicates = rules.allowDuplicates,
                maxConsecutiveDuplicateDigits = rules.maxConsecutiveDuplicateDigits,
                matchDurationSeconds = rules.matchDurationSeconds,
            ),
        )
    }

    private fun cacheInvite(stored: DurablePrivateInvite): PrivateInviteRecord =
        inviteRecord(stored).also { record ->
            privateInvites[stored.inviteCode] = record
            privateInviteCreateReplays["${stored.ownerPlayerId}:${stored.createCommandId}"] =
                PrivateInviteCreateReplay(
                    record.invite.playStyle,
                    record.invite.codeLength,
                    stored.inviteCode,
                )
            if (stored.guestPlayerId != null && stored.acceptCommandId != null) {
                privateInviteAcceptReplays["${stored.guestPlayerId}:${stored.acceptCommandId}"] =
                    PrivateInviteAcceptReplay(stored.inviteCode)
            }
        }

    private fun expirePrivateInvites(now: Instant) {
        privateInvites.values.forEach { expirePrivateInvite(it, now) }
    }

    private fun expirePrivateInvite(record: PrivateInviteRecord, now: Instant) {
        if (
            record.invite.status == PrivateInviteStatus.WAITING &&
            !now.isBefore(record.invite.expiresAt)
        ) {
            if (lobbyRepository != null) {
                lobbyRepository.expireInvite(record.invite.inviteCode, now)?.let(::cacheInvite)
            } else {
                record.invite = record.invite.copy(status = PrivateInviteStatus.EXPIRED)
            }
        }
    }

    private fun nextInviteCode(): String {
        repeat(MaximumInviteCodeAttempts) {
            val code = buildString(PrivateInviteCodeLength) {
                repeat(PrivateInviteCodeLength) {
                    append(PrivateInviteAlphabet[secureRandom.nextInt(PrivateInviteAlphabet.length)])
                }
            }
            if (!privateInvites.containsKey(code)) return code
        }
        throw IllegalStateException("could not allocate private duel invite code")
    }

    private fun promoteTicketToBotIfExpired(record: TicketRecord, now: Instant) {
        if (record.ticket.status != MatchmakingStatus.SEARCHING) return
        val deadline = record.ticket.createdAt.plus(botFallbackDelay)
        if (now.isBefore(deadline)) return
        if (lobbyRepository != null) {
            var createdSession: SessionRecord? = null
            val result = try {
                lobbyRepository.coordinateBotFallback(
                    ticketId = record.ticket.ticketId,
                    eligibleBefore = now.minus(botFallbackDelay),
                ) { stored ->
                    createBotSession(
                        mode = enumValueOf(stored.mode),
                        rules = OnlineLobbyRulesCodec.decode(stored.rulesJson),
                        playerId = stored.ownerPlayerId,
                    ).also { createdSession = it }.persistenceState()
                }
            } catch (failure: Throwable) {
                createdSession?.close()
                throw failure
            } ?: return
            cacheTicket(result.ticket)
            createdSession?.let { session ->
                if (result.createdSession && result.ticket.sessionId == session.sessionId) {
                    sessions[session.sessionId] = session
                } else {
                    session.close()
                }
            }
            return
        }
        val session = createBotSession(record.mode, record.rules, record.ticket.ownerPlayerId)
        val previousTicket = record.ticket
        record.ticket = record.ticket.matched(session.sessionId, withBot = true)
        try {
            session.persistNew()
        } catch (failure: Throwable) {
            record.ticket = previousTicket
            session.close()
            throw failure
        }
        sessions[session.sessionId] = session
    }

    private fun createBotSession(
        mode: OnlineMatchMode,
        rules: OnlineMatchRules,
        playerId: String,
    ): SessionRecord {
        val sessionId = UUID.randomUUID().toString()
        val config = rules.gameConfig()
        val bot = ServerBotPlayer.create(
            config = config,
            difficulty = mode.botDifficulty(),
        )
        return SessionRecord(
            sessionId = sessionId,
            match = DuelMatch.create(
                config = config,
                playStyle = rules.playStyle.toDomainPlayStyle(),
                attemptLimit = null,
            ),
            memberships = mapOf(playerId to DuelParticipant.FIRST),
            bot = bot,
            clock = clock,
            matchDuration = privateMatchDuration,
            setupTimeout = setupTimeout,
            createdAt = clock.instant(),
            finishedSessionRetention = finishedSessionRetention,
            repository = sessionRepository,
        )
    }

    private fun createHumanSession(
        rules: OnlineMatchRules,
        firstPlayerId: String,
        secondPlayerId: String,
    ): SessionRecord = createHumanSession(
        rules = rules,
        attemptLimit = null,
        matchDuration = privateMatchDuration,
        firstPlayerId = firstPlayerId,
        secondPlayerId = secondPlayerId,
    )

    private fun createHumanSession(
        rules: OnlineMatchRules,
        attemptLimit: Int?,
        matchDuration: Duration?,
        firstPlayerId: String,
        secondPlayerId: String,
    ): SessionRecord {
        val sessionId = UUID.randomUUID().toString()
        return SessionRecord(
            sessionId = sessionId,
            match = DuelMatch.create(
                config = rules.gameConfig(),
                playStyle = rules.playStyle.toDomainPlayStyle(),
                attemptLimit = attemptLimit,
            ),
            memberships = mapOf(
                firstPlayerId to DuelParticipant.FIRST,
                secondPlayerId to DuelParticipant.SECOND,
            ),
            bot = null,
            clock = clock,
            matchDuration = matchDuration,
            setupTimeout = setupTimeout,
            createdAt = clock.instant(),
            finishedSessionRetention = finishedSessionRetention,
            repository = sessionRepository,
        )
    }

    private fun <T> withSession(
        playerId: String,
        sessionId: String,
        operation: (SessionRecord) -> T,
    ): T {
        requireCanonicalUuid(playerId, "playerId")
        requireCanonicalUuid(sessionId, "sessionId")
        return coordinateSessionRecord(sessionId) { record ->
            if (!record.isMember(playerId)) throw OnlineMembershipRejectedException()
            operation(record)
        }?.value ?: throw NoSuchElementException("online session not found")
    }

    private fun <T> coordinateSessionRecord(
        sessionId: String,
        includeExpired: Boolean = false,
        operation: (SessionRecord) -> T,
    ): SessionOperationResult<T>? {
        val repository = sessionRepository
        if (repository == null) {
            val record = synchronized(lock) { sessions[sessionId] } ?: return null
            val previousRevision = record.currentRevision()
            val result = operation(record)
            val currentRevision = record.currentRevision()
            if (currentRevision > previousRevision) {
                sessionEvents?.sessionChanged(sessionId, currentRevision, clock.instant())
            }
            return SessionOperationResult(result)
        }
        var coordinatedRecord: SessionRecord? = null
        val result = try {
            repository.coordinate(sessionId, clock.instant(), includeExpired) { stored ->
                val record = sessionRecord(stored, repository = null)
                coordinatedRecord = record
                val operationResult = operation(record)
                DurableSessionCoordination(
                    session = record.persistenceState(),
                    result = SessionOperationResult(operationResult),
                )
            }
        } catch (failure: Throwable) {
            coordinatedRecord?.close()
            throw failure
        } ?: return null
        val record = requireNotNull(coordinatedRecord)
        synchronized(lock) {
            val cached = sessions[sessionId]
            if (cached == null || cached.currentRevision() <= record.currentRevision()) {
                sessions.put(sessionId, record)?.takeIf { it !== record }?.close()
            } else {
                record.close()
            }
        }
        return result
    }

    private fun restoreSession(stored: DurableOnlineSession): SessionRecord {
        sessions[stored.sessionId]?.let { return it }
        return sessionRecord(stored, requireNotNull(sessionRepository)).also {
            sessions[stored.sessionId] = it
        }
    }

    private fun sessionRecord(
        stored: DurableOnlineSession,
        repository: OnlineSessionRepository?,
    ): SessionRecord {
        val memento = OnlineSessionMementoCodec.decode(stored.stateJson)
        check(memento.sessionId == stored.sessionId && memento.revision == stored.revision) {
            "Durable online session metadata does not match encrypted state"
        }
        return SessionRecord.restore(
            memento = memento,
            clock = clock,
            finishedSessionRetention = finishedSessionRetention,
            repository = repository,
        )
    }

    internal fun sweepExpiredState() {
        synchronized(lock) {
            val now = clock.instant()
            promoteExpiredTickets(now)
            expirePrivateInvites(now)

            val expiredSessionIds = sessions.keys.toList().mapNotNull { sessionId ->
                val coordinated = coordinateSessionRecord(sessionId, includeExpired = true) { record ->
                    record.expireAndFinishedAt(now)
                }
                if (coordinated == null) {
                    sessions.remove(sessionId)?.close()
                    return@mapNotNull null
                }
                val finishedAt = coordinated.value ?: return@mapNotNull null
                sessionId.takeIf { !now.isBefore(finishedAt.plus(finishedSessionRetention)) }
            }.toSet()

            val expiredTicketIds = tickets.values
                .filter {
                    !now.isBefore(it.ticket.createdAt.plus(ticketRetention)) ||
                        it.ticket.sessionId in expiredSessionIds
                }
                .map { it.ticket.ticketId }
                .toSet()
            lobbyRepository?.deleteTickets(expiredTicketIds)
            expiredTicketIds.forEach(tickets::remove)
            ticketReplays.entries.removeIf { it.value.ticketId in expiredTicketIds }

            val expiredInviteCodes = privateInvites.values
                .filter {
                    !now.isBefore(it.invite.expiresAt.plus(inviteRetention)) ||
                        it.invite.sessionId in expiredSessionIds
                }
                .map { it.invite.inviteCode }
                .toSet()
            lobbyRepository?.deleteInvites(expiredInviteCodes)
            expiredInviteCodes.forEach(privateInvites::remove)
            privateInviteCreateReplays.entries.removeIf { it.value.inviteCode in expiredInviteCodes }
            privateInviteAcceptReplays.entries.removeIf { it.value.inviteCode in expiredInviteCodes }

            expiredSessionIds.forEach { sessionId ->
                sessions.remove(sessionId)?.close()
                sessionRepository?.delete(sessionId)
            }
        }
    }

    override fun close() {
        sweeper?.shutdownNow()
        synchronized(lock) {
            sessions.values.forEach(SessionRecord::close)
            sessions.clear()
            tickets.clear()
            ticketReplays.clear()
            privateInvites.clear()
            privateInviteCreateReplays.clear()
            privateInviteAcceptReplays.clear()
            lobbyRepository?.close()
            sessionRepository?.close()
        }
    }

    private data class TicketReplay(
        val mode: OnlineMatchMode,
        val rules: OnlineMatchRules,
        val ticketId: String,
    )

    private data class SessionOperationResult<T>(val value: T)

    private data class TicketRecord(
        val mode: OnlineMatchMode,
        val rules: OnlineMatchRules,
        val commandId: String,
        var ticket: MatchmakingTicket,
    ) {
        fun toDurable(retention: Duration): DurableMatchmakingTicket = DurableMatchmakingTicket(
            ticketId = ticket.ticketId,
            ownerPlayerId = ticket.ownerPlayerId,
            commandId = commandId,
            mode = mode.name,
            rulesJson = OnlineLobbyRulesCodec.encode(rules),
            status = ticket.status.name,
            sessionId = ticket.sessionId,
            matchedWithBot = ticket.matchedWithBot,
            createdAt = ticket.createdAt,
            expiresAt = ticket.createdAt.plus(retention),
        )
    }

    private data class PrivateInviteCreateReplay(
        val playStyle: OnlineFriendPlayStyle,
        val codeLength: Int,
        val inviteCode: String,
    )

    private data class PrivateInviteAcceptReplay(
        val inviteCode: String,
    )

    private data class PrivateInviteRecord(
        val ownerPlayerId: String,
        var guestPlayerId: String? = null,
        val createCommandId: String,
        var acceptCommandId: String? = null,
        var invite: PrivateDuelInvite,
    ) {
        fun toDurable(): DurablePrivateInvite = DurablePrivateInvite(
            inviteCode = invite.inviteCode,
            ownerPlayerId = ownerPlayerId,
            guestPlayerId = guestPlayerId,
            createCommandId = createCommandId,
            acceptCommandId = acceptCommandId,
            status = invite.status.name,
            rulesJson = OnlineLobbyRulesCodec.encodeInvite(invite),
            sessionId = invite.sessionId,
            createdAt = invite.createdAt,
            expiresAt = invite.expiresAt,
        )
    }

    private class SessionRecord(
        val sessionId: String,
        private val match: DuelMatch,
        private val memberships: Map<String, DuelParticipant>,
        private val bot: ServerBotPlayer?,
        private val clock: Clock,
        private val matchDuration: Duration?,
        setupTimeout: Duration,
        private val createdAt: Instant,
        private val finishedSessionRetention: Duration,
        private val repository: OnlineSessionRepository?,
        restoredSetupDeadlineAt: Instant? = null,
    ) : AutoCloseable {
        private var revision: Long = 0
        private val commandReplays = mutableMapOf<String, CommandReplay>()
        private val pendingSecrets = mutableMapOf<DuelParticipant, CharArray>()
        private val durableSecrets = mutableMapOf<DuelParticipant, CharArray>()
        private val acceptedGuesses = mutableMapOf<Int, AcceptedGuess>()
        private var startedAt: Instant? = null
        private var deadlineAt: Instant? = null
        private val setupDeadlineAt: Instant = restoredSetupDeadlineAt ?: createdAt.plus(setupTimeout)
        private var finishedAt: Instant? = null
        private var finishedByTimeout: Boolean = false

        init {
            if (bot != null) {
                durableSecrets[DuelParticipant.SECOND] = bot.revealSecret().toCharArray()
            }
        }

        fun isMember(playerId: String): Boolean = memberships.containsKey(playerId)

        @Synchronized
        fun currentRevision(): Long = revision

        @Synchronized
        fun submitSecret(
            playerId: String,
            commandId: String,
            expectedRevision: Long,
            secret: String,
        ): OnlineDuelSnapshot = submit(
            playerId = playerId,
            commandId = commandId,
            expectedRevision = expectedRevision,
            fingerprint = fingerprint("secret", secret),
            allowStaleRevision = false,
        ) { participant ->
            if (match.snapshot().phase != DuelPhase.SETUP || participant in pendingSecrets) {
                throw DuelCommandRejectedException(DuelCommandRejection.SECRET_NOT_EXPECTED)
            }
            val secretChars = secret.toCharArray()
            val validationReason = try {
                GuessValidator.validateOrReason(secretChars, match.config)
            } finally {
                secretChars.fill(CLEARED_DIGIT)
            }
            if (validationReason != null) {
                throw DuelCommandRejectedException(DuelCommandRejection.INVALID_SECRET, validationReason)
            }
            durableSecrets[participant]?.fill(CLEARED_DIGIT)
            durableSecrets[participant] = secret.toCharArray()
            pendingSecrets[participant] = secret.toCharArray()
            if (bot != null) {
                check(participant == DuelParticipant.FIRST)
                durableSecrets[DuelParticipant.SECOND] = bot.revealSecret().toCharArray()
                configureSecret(DuelParticipant.FIRST, pendingSecrets.remove(DuelParticipant.FIRST)!!)
                configureSecret(DuelParticipant.SECOND, bot.revealSecret().toCharArray())
            } else if (pendingSecrets.keys.containsAll(DuelParticipant.entries)) {
                configureSecret(DuelParticipant.FIRST, pendingSecrets.remove(DuelParticipant.FIRST)!!)
                configureSecret(DuelParticipant.SECOND, pendingSecrets.remove(DuelParticipant.SECOND)!!)
            }
            startClockIfActive()
            revision += 1
            snapshotFor(playerId)
        }

        @Synchronized
        fun submitGuess(
            playerId: String,
            commandId: String,
            expectedRevision: Long,
            guess: String,
        ): OnlineDuelSnapshot = submit(
            playerId = playerId,
            commandId = commandId,
            expectedRevision = expectedRevision,
            fingerprint = fingerprint("guess", guess),
            allowStaleRevision = match.playStyle == DuelPlayStyle.RACE,
        ) { participant ->
            val afterPlayer = match.submitGuess(
                participant,
                MutableDuelCommand.guess(guess.toCharArray()),
            )
            val acceptedAttempt = afterPlayer.attempts.last()
            check(acceptedAttempt.attacker == participant)
            check(acceptedAttempt.number !in acceptedGuesses)
            acceptedGuesses[acceptedAttempt.number] = AcceptedGuess(participant, guess.toCharArray())
            revision += 1
            if (bot != null && afterPlayer.phase == DuelPhase.ACTIVE) {
                bot.scoreIncomingGuess(guess)
                val turn = bot.nextTurn()
                val afterBot = match.submitGuess(
                    DuelParticipant.SECOND,
                    MutableDuelCommand.guess(turn.guess.toCharArray()),
                )
                val botAttempt = afterBot.attempts.last()
                acceptedGuesses[botAttempt.number] = AcceptedGuess(
                    DuelParticipant.SECOND,
                    turn.guess.toCharArray(),
                )
                bot.registerTurnFeedback(turn.guess, afterBot.attempts.last().exactMatches)
                revision += 1
            }
            markFinishedIfNeeded()
            snapshotFor(playerId)
        }

        @Synchronized
        fun snapshotFor(playerId: String): OnlineDuelSnapshot {
            expireIfNeeded(clock.instant())
            val viewer = memberships[playerId] ?: throw OnlineMembershipRejectedException()
            return match.snapshot().toOnlineSnapshot(
                sessionId = sessionId,
                revision = revision,
                viewer = viewer,
                pendingSecrets = pendingSecrets.keys,
                acceptedGuesses = acceptedGuesses,
                startedAt = startedAt,
                deadlineAt = deadlineAt,
                serverTime = clock.instant(),
            )
        }

        private fun submit(
            playerId: String,
            commandId: String,
            expectedRevision: Long,
            fingerprint: String,
            allowStaleRevision: Boolean,
            operation: SessionRecord.(DuelParticipant) -> OnlineDuelSnapshot,
        ): OnlineDuelSnapshot {
            requireCanonicalUuid(commandId, "commandId")
            val participant = memberships[playerId] ?: throw OnlineMembershipRejectedException()
            expireIfNeeded(clock.instant())
            val replayKey = "$playerId:$commandId"
            commandReplays[replayKey]?.let { replay ->
                if (replay.fingerprint != fingerprint) throw OnlineCommandIdReusedException()
                return replay.snapshot
            }
            val revisionAccepted = if (allowStaleRevision) {
                expectedRevision <= revision
            } else {
                expectedRevision == revision
            }
            if (!revisionAccepted) throw OnlineRevisionConflictException(snapshotFor(playerId))
            val storedRevision = revision
            val result = operation(participant)
            commandReplays[replayKey] = CommandReplay(fingerprint, result)
            try {
                persistUpdate(storedRevision)
            } catch (failure: Throwable) {
                close()
                throw failure
            }
            return result
        }

        private fun configureSecret(participant: DuelParticipant, secret: CharArray) {
            try {
                match.setSecret(participant, MutableDuelCommand.secret(secret))
            } finally {
                secret.fill(CLEARED_DIGIT)
            }
        }

        private fun startClockIfActive() {
            if (startedAt != null || match.snapshot().phase != DuelPhase.ACTIVE) return
            val started = clock.instant()
            startedAt = started
            deadlineAt = matchDuration?.let(started::plus)
        }

        @Synchronized
        fun expireAndFinishedAt(now: Instant): Instant? {
            expireIfNeeded(now)
            return finishedAt
        }

        private fun expireIfNeeded(now: Instant) {
            val snapshot = match.snapshot()
            val expired = when (snapshot.phase) {
                DuelPhase.SETUP -> !now.isBefore(setupDeadlineAt)
                DuelPhase.ACTIVE -> deadlineAt?.let { !now.isBefore(it) } == true
                DuelPhase.FINISHED -> false
            }
            if (expired) {
                val storedRevision = revision
                wipePendingSecrets()
                match.finishDueToTimeout()
                revision += 1
                finishedAt = now
                finishedByTimeout = true
                try {
                    persistUpdate(storedRevision)
                } catch (failure: Throwable) {
                    close()
                    throw failure
                }
            }
        }

        private fun markFinishedIfNeeded() {
            if (finishedAt == null && match.snapshot().phase == DuelPhase.FINISHED) {
                finishedAt = clock.instant()
            }
        }

        private fun wipePendingSecrets() {
            pendingSecrets.values.forEach { it.fill(CLEARED_DIGIT) }
            pendingSecrets.clear()
        }

        fun persistNew() {
            repository?.create(persistenceState())
        }

        private fun persistUpdate(expectedRevision: Long) {
            repository?.update(persistenceState(), expectedRevision)
        }

        fun persistenceState(): DurableOnlineSession {
            val snapshot = match.snapshot()
            val memento = OnlineSessionMemento(
                sessionId = sessionId,
                revision = revision,
                config = match.config,
                playStyle = match.playStyle,
                attemptLimit = match.attemptLimit,
                memberships = memberships,
                bot = bot?.let { DurableBot(it.profile, it.brainSeed) },
                secrets = durableSecrets.mapValues { (_, secret) -> secret.concatToString() },
                guesses = acceptedGuesses.toSortedMap().values.map { guess ->
                    DurableGuess(guess.participant, guess.guess.concatToString())
                },
                commandReplays = commandReplays.map { (key, replay) ->
                    val separator = key.lastIndexOf(':')
                    DurableCommandReplay(
                        playerId = key.substring(0, separator),
                        commandId = key.substring(separator + 1),
                        fingerprint = replay.fingerprint,
                        snapshot = replay.snapshot,
                    )
                },
                createdAt = createdAt,
                setupDeadlineAt = setupDeadlineAt,
                matchDurationMillis = matchDuration?.toMillis(),
                startedAt = startedAt,
                deadlineAt = deadlineAt,
                finishedAt = finishedAt,
                finishedByTimeout = finishedByTimeout,
            )
            return DurableOnlineSession(
                sessionId = sessionId,
                revision = revision,
                status = snapshot.phase.name,
                stateJson = OnlineSessionMementoCodec.encode(memento),
                createdAt = createdAt,
                startedAt = startedAt,
                finishedAt = finishedAt,
                expiresAt = finishedAt?.plus(finishedSessionRetention),
            )
        }

        @Synchronized
        override fun close() {
            wipePendingSecrets()
            acceptedGuesses.values.forEach { it.guess.fill(CLEARED_DIGIT) }
            acceptedGuesses.clear()
            durableSecrets.values.forEach { it.fill(CLEARED_DIGIT) }
            durableSecrets.clear()
            commandReplays.clear()
            match.close()
        }

        companion object {
            fun restore(
                memento: OnlineSessionMemento,
                clock: Clock,
                finishedSessionRetention: Duration,
                repository: OnlineSessionRepository?,
            ): SessionRecord {
                val match = DuelMatch.create(
                    config = memento.config,
                    playStyle = memento.playStyle,
                    attemptLimit = memento.attemptLimit,
                )
                val bot = memento.bot?.let { durableBot ->
                    val secret = requireNotNull(memento.secrets[DuelParticipant.SECOND]) {
                        "Recovered bot match is missing the encrypted bot secret"
                    }
                    ServerBotPlayer.restore(
                        profile = durableBot.profile,
                        config = memento.config,
                        hiddenSecret = secret,
                        brainSeed = durableBot.brainSeed,
                    )
                }
                val record = SessionRecord(
                    sessionId = memento.sessionId,
                    match = match,
                    memberships = memento.memberships,
                    bot = bot,
                    clock = clock,
                    matchDuration = memento.matchDurationMillis?.let(Duration::ofMillis),
                    setupTimeout = Duration.ofSeconds(1),
                    createdAt = memento.createdAt,
                    finishedSessionRetention = finishedSessionRetention,
                    repository = repository,
                    restoredSetupDeadlineAt = memento.setupDeadlineAt,
                )
                memento.secrets.forEach { (participant, secret) ->
                    record.durableSecrets[participant]?.fill(CLEARED_DIGIT)
                    record.durableSecrets[participant] = secret.toCharArray()
                }
                if (memento.secrets.keys.containsAll(DuelParticipant.entries)) {
                    DuelParticipant.entries.forEach { participant ->
                        record.configureSecret(participant, memento.secrets.getValue(participant).toCharArray())
                    }
                } else {
                    memento.secrets
                        .filterKeys { participant -> bot == null || participant == DuelParticipant.FIRST }
                        .forEach { (participant, secret) ->
                        record.pendingSecrets[participant] = secret.toCharArray()
                    }
                }
                memento.guesses.forEachIndexed { index, durableGuess ->
                    if (bot != null && durableGuess.participant == DuelParticipant.FIRST) {
                        bot.scoreIncomingGuess(durableGuess.guess)
                    }
                    if (bot != null && durableGuess.participant == DuelParticipant.SECOND) {
                        val turn = bot.nextTurn()
                        check(turn.guess == durableGuess.guess) {
                            "Recovered server bot history does not match its durable seed"
                        }
                    }
                    val after = match.submitGuess(
                        durableGuess.participant,
                        MutableDuelCommand.guess(durableGuess.guess.toCharArray()),
                    )
                    val attempt = after.attempts.last()
                    check(attempt.number == index + 1)
                    record.acceptedGuesses[attempt.number] = AcceptedGuess(
                        durableGuess.participant,
                        durableGuess.guess.toCharArray(),
                    )
                    if (bot != null && durableGuess.participant == DuelParticipant.SECOND) {
                        bot.registerTurnFeedback(durableGuess.guess, attempt.exactMatches)
                    }
                }
                if (memento.finishedByTimeout && match.snapshot().phase != DuelPhase.FINISHED) {
                    match.finishDueToTimeout()
                }
                record.revision = memento.revision
                record.startedAt = memento.startedAt
                record.deadlineAt = memento.deadlineAt
                record.finishedAt = memento.finishedAt
                record.finishedByTimeout = memento.finishedByTimeout
                memento.commandReplays.forEach { replay ->
                    record.commandReplays["${replay.playerId}:${replay.commandId}"] =
                        CommandReplay(replay.fingerprint, replay.snapshot)
                }
                return record
            }
        }
    }

    private data class CommandReplay(
        val fingerprint: String,
        val snapshot: OnlineDuelSnapshot,
    )
}

private class AcceptedGuess(
    val participant: DuelParticipant,
    val guess: CharArray,
)

private fun MatchmakingTicket.matched(sessionId: String, withBot: Boolean): MatchmakingTicket =
    copy(
        status = MatchmakingStatus.MATCHED,
        sessionId = sessionId,
        matchedWithBot = withBot,
    )

private fun DuelSnapshot.toOnlineSnapshot(
    sessionId: String,
    revision: Long,
    viewer: DuelParticipant,
    pendingSecrets: Set<DuelParticipant>,
    acceptedGuesses: Map<Int, AcceptedGuess>,
    startedAt: Instant?,
    deadlineAt: Instant?,
    serverTime: Instant,
): OnlineDuelSnapshot =
    OnlineDuelSnapshot(
        sessionId = sessionId,
        revision = revision,
        phase = phase.name.lowercase(),
        currentTurn = currentTurn?.publicActorFor(viewer),
        winner = winner?.publicActorFor(viewer),
        finishReason = finishReason?.name?.lowercase(),
        playStyle = playStyle.name.lowercase(),
        codeLength = config.codeLength,
        attemptLimit = attemptLimit,
        allowDuplicates = config.allowDuplicates,
        maxConsecutiveDuplicateDigits = config.maxConsecutiveDuplicateDigits,
        startedAtEpochMs = startedAt?.toEpochMilli(),
        deadlineAtEpochMs = deadlineAt?.toEpochMilli(),
        serverTimeEpochMs = serverTime.toEpochMilli(),
        attempts = attempts.map { attempt ->
            OnlineDuelAttempt(
                actor = attempt.attacker.publicActorFor(viewer),
                exactMatches = attempt.exactMatches,
                number = attempt.number,
                ownGuess = acceptedGuesses[attempt.number]
                    ?.takeIf { it.participant == viewer && attempt.attacker == viewer }
                    ?.guess
                    ?.concatToString(),
            )
        },
        participants = participants.map { participant ->
            OnlineDuelParticipant(
                actor = participant.participant.publicActorFor(viewer),
                secretConfigured = participant.secretConfigured || participant.participant in pendingSecrets,
                attemptsUsed = participant.attemptsUsed,
                attemptsLeft = participant.attemptsLeft,
            )
        },
    )

private fun DuelParticipant.publicActorFor(viewer: DuelParticipant): String =
    if (this == viewer) "player" else "opponent"

private fun OnlineMatchRules.gameConfig(): GameConfig = GameConfig(
    codeLength = codeLength,
    allowDuplicates = true,
    attemptLimit = OnlineInternalAttemptCapacity,
    maxConsecutiveDuplicateDigits = OnlineMaximumConsecutiveDuplicateDigits,
)

private fun OnlineMatchMode.botDifficulty(): BotDifficulty = when (this) {
    OnlineMatchMode.CLASSIC -> BotDifficulty.MEDIUM
    OnlineMatchMode.PRO -> BotDifficulty.HARD
    OnlineMatchMode.PRO_PLUS -> BotDifficulty.EXPERT
}

private fun PrivateDuelInvite.onlineRules(): OnlineMatchRules =
    OnlineMatchRules(playStyle, codeLength)

private fun OnlineFriendPlayStyle.toDomainPlayStyle(): DuelPlayStyle = when (this) {
    OnlineFriendPlayStyle.RACE -> DuelPlayStyle.RACE
    OnlineFriendPlayStyle.TURN_BASED -> DuelPlayStyle.TURN_BASED
}

private fun fingerprint(type: String, payload: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$type:$payload".toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun requireCanonicalUuid(value: String, name: String) {
    require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
        "$name must be a canonical UUID"
    }
}

private fun requireInviteCode(value: String) {
    require(value.matches(Regex("[$PrivateInviteAlphabet]{$PrivateInviteCodeLength}"))) {
        "inviteCode has an invalid format"
    }
}

private const val CLEARED_DIGIT: Char = '\u0000'
private const val PrivateInviteAlphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
private const val PrivateInviteCodeLength = 8
private const val MaximumInviteCodeAttempts = 32
private val OnlineCodeLengthRange = 4..10
private const val OnlineMaximumConsecutiveDuplicateDigits = 3
private const val OnlineInternalAttemptCapacity = 1_000
