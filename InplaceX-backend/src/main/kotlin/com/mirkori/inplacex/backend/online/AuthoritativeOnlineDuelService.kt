package com.mirkori.inplacex.backend.online

import com.mirkori.inplacex.backend.bot.ServerBotPlayer
import com.mirkori.inplacex.backend.domain.duel.DuelCommandRejectedException
import com.mirkori.inplacex.backend.domain.duel.DuelCommandRejection
import com.mirkori.inplacex.backend.domain.duel.DuelMatch
import com.mirkori.inplacex.backend.domain.duel.DuelParticipant
import com.mirkori.inplacex.backend.domain.duel.DuelPhase
import com.mirkori.inplacex.backend.domain.duel.DuelSnapshot
import com.mirkori.inplacex.backend.session.domain.MutableDuelCommand
import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.core.engine.GuessValidator
import com.mirkori.inplacex.core.model.GameConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

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
)

data class OnlineDuelAttempt(
    val actor: String,
    val exactMatches: Int,
    val number: Int,
)

data class OnlineDuelParticipant(
    val actor: String,
    val secretConfigured: Boolean,
    val attemptsUsed: Int,
    val attemptsLeft: Int,
)

data class OnlineDuelSnapshot(
    val sessionId: String,
    val revision: Long,
    val phase: String,
    val currentTurn: String?,
    val winner: String?,
    val codeLength: Int,
    val attemptLimit: Int,
    val allowDuplicates: Boolean,
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
    private val secureRandom: SecureRandom = SecureRandom(),
) : AutoCloseable {
    private val lock = Any()
    private val tickets = mutableMapOf<String, TicketRecord>()
    private val ticketReplays = mutableMapOf<String, TicketReplay>()
    private val privateInvites = mutableMapOf<String, PrivateInviteRecord>()
    private val privateInviteCreateReplays = mutableMapOf<String, PrivateInviteCreateReplay>()
    private val privateInviteAcceptReplays = mutableMapOf<String, PrivateInviteAcceptReplay>()
    private val sessions = mutableMapOf<String, SessionRecord>()

    init {
        require(!botFallbackDelay.isNegative && !botFallbackDelay.isZero) {
            "botFallbackDelay must be positive"
        }
        require(!privateInviteLifetime.isNegative && !privateInviteLifetime.isZero) {
            "privateInviteLifetime must be positive"
        }
    }

    fun createTicket(
        playerId: String,
        commandId: String,
        mode: OnlineMatchMode,
    ): MatchmakingTicket {
        requireCanonicalUuid(playerId, "playerId")
        requireCanonicalUuid(commandId, "commandId")
        synchronized(lock) {
            val replayKey = "$playerId:$commandId"
            ticketReplays[replayKey]?.let { replay ->
                if (replay.mode != mode) throw OnlineCommandIdReusedException()
                return tickets.getValue(replay.ticketId).ticket
            }

            promoteExpiredTickets(clock.instant())
            val waitingPeer = tickets.values
                .asSequence()
                .filter { record ->
                    record.mode == mode &&
                        record.ticket.status == MatchmakingStatus.SEARCHING &&
                        record.ticket.ownerPlayerId != playerId
                }
                .minWithOrNull(compareBy<TicketRecord>({ it.ticket.createdAt }, { it.ticket.ticketId }))

            val ticketId = UUID.randomUUID().toString()
            val createdAt = clock.instant()
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
                    mode = mode,
                    firstPlayerId = waitingPeer.ticket.ownerPlayerId,
                    secondPlayerId = playerId,
                )
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
            tickets[ticketId] = TicketRecord(mode, ticket)
            ticketReplays[replayKey] = TicketReplay(mode, ticketId)
            return ticket
        }
    }

    fun readTicket(playerId: String, ticketId: String): MatchmakingTicket {
        requireCanonicalUuid(playerId, "playerId")
        synchronized(lock) {
            val record = tickets[ticketId] ?: throw NoSuchElementException("matchmaking ticket not found")
            if (record.ticket.ownerPlayerId != playerId) throw OnlineMembershipRejectedException()
            if (record.ticket.status == MatchmakingStatus.SEARCHING) {
                promoteTicketToBotIfExpired(record, clock.instant())
            }
            return record.ticket
        }
    }

    fun createPrivateInvite(
        playerId: String,
        commandId: String,
        mode: OnlineMatchMode,
    ): PrivateDuelInvite {
        requireCanonicalUuid(playerId, "playerId")
        requireCanonicalUuid(commandId, "commandId")
        synchronized(lock) {
            val replayKey = "$playerId:$commandId"
            privateInviteCreateReplays[replayKey]?.let { replay ->
                if (replay.mode != mode) throw OnlineCommandIdReusedException()
                return privateInvites.getValue(replay.inviteCode).invite
            }
            expirePrivateInvites(clock.instant())
            val createdAt = clock.instant()
            val inviteCode = nextInviteCode()
            val invite = PrivateDuelInvite(
                inviteCode = inviteCode,
                status = PrivateInviteStatus.WAITING,
                sessionId = null,
                createdAt = createdAt,
                expiresAt = createdAt.plus(privateInviteLifetime),
            )
            privateInvites[inviteCode] = PrivateInviteRecord(
                mode = mode,
                ownerPlayerId = playerId,
                invite = invite,
            )
            privateInviteCreateReplays[replayKey] = PrivateInviteCreateReplay(mode, inviteCode)
            return invite
        }
    }

    fun readPrivateInvite(playerId: String, inviteCode: String): PrivateDuelInvite {
        requireCanonicalUuid(playerId, "playerId")
        requireInviteCode(inviteCode)
        synchronized(lock) {
            val record = privateInvites[inviteCode] ?: throw NoSuchElementException("private duel invite not found")
            expirePrivateInvite(record, clock.instant())
            if (playerId != record.ownerPlayerId && playerId != record.guestPlayerId) {
                throw OnlineMembershipRejectedException()
            }
            return record.invite
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
                mode = record.mode,
                firstPlayerId = record.ownerPlayerId,
                secondPlayerId = playerId,
            )
            record.guestPlayerId = playerId
            record.invite = record.invite.copy(
                status = PrivateInviteStatus.MATCHED,
                sessionId = session.sessionId,
            )
            privateInviteAcceptReplays[replayKey] = PrivateInviteAcceptReplay(inviteCode)
            return record.invite
        }
    }

    fun readSession(playerId: String, sessionId: String): OnlineDuelSnapshot =
        sessionFor(playerId, sessionId).snapshotFor(playerId)

    fun submitSecret(
        playerId: String,
        sessionId: String,
        commandId: String,
        expectedRevision: Long,
        secret: String,
    ): OnlineDuelSnapshot =
        sessionFor(playerId, sessionId).submitSecret(
            playerId = playerId,
            commandId = commandId,
            expectedRevision = expectedRevision,
            secret = secret,
        )

    fun submitGuess(
        playerId: String,
        sessionId: String,
        commandId: String,
        expectedRevision: Long,
        guess: String,
    ): OnlineDuelSnapshot =
        sessionFor(playerId, sessionId).submitGuess(
            playerId = playerId,
            commandId = commandId,
            expectedRevision = expectedRevision,
            guess = guess,
        )

    private fun promoteExpiredTickets(now: Instant) {
        tickets.values
            .filter { it.ticket.status == MatchmakingStatus.SEARCHING }
            .forEach { promoteTicketToBotIfExpired(it, now) }
    }

    private fun expirePrivateInvites(now: Instant) {
        privateInvites.values.forEach { expirePrivateInvite(it, now) }
    }

    private fun expirePrivateInvite(record: PrivateInviteRecord, now: Instant) {
        if (
            record.invite.status == PrivateInviteStatus.WAITING &&
            !now.isBefore(record.invite.expiresAt)
        ) {
            record.invite = record.invite.copy(status = PrivateInviteStatus.EXPIRED)
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
        val session = createBotSession(record.mode, record.ticket.ownerPlayerId)
        record.ticket = record.ticket.matched(session.sessionId, withBot = true)
    }

    private fun createBotSession(mode: OnlineMatchMode, playerId: String): SessionRecord {
        val sessionId = UUID.randomUUID().toString()
        val config = mode.gameConfig()
        val bot = ServerBotPlayer.create(
            config = config,
            difficulty = mode.botDifficulty(),
            secretSeed = sessionId.hashCode().toLong(),
            brainSeed = playerId.hashCode().toLong() xor sessionId.hashCode().toLong(),
        )
        return SessionRecord(
            sessionId = sessionId,
            match = DuelMatch.create(config),
            memberships = mapOf(playerId to DuelParticipant.FIRST),
            bot = bot,
        ).also { sessions[sessionId] = it }
    }

    private fun createHumanSession(
        mode: OnlineMatchMode,
        firstPlayerId: String,
        secondPlayerId: String,
    ): SessionRecord {
        val sessionId = UUID.randomUUID().toString()
        return SessionRecord(
            sessionId = sessionId,
            match = DuelMatch.create(mode.gameConfig()),
            memberships = mapOf(
                firstPlayerId to DuelParticipant.FIRST,
                secondPlayerId to DuelParticipant.SECOND,
            ),
            bot = null,
        ).also { sessions[sessionId] = it }
    }

    private fun sessionFor(playerId: String, sessionId: String): SessionRecord {
        requireCanonicalUuid(playerId, "playerId")
        requireCanonicalUuid(sessionId, "sessionId")
        synchronized(lock) {
            val record = sessions[sessionId] ?: throw NoSuchElementException("online session not found")
            if (!record.isMember(playerId)) throw OnlineMembershipRejectedException()
            return record
        }
    }

    override fun close() {
        synchronized(lock) {
            sessions.values.forEach(SessionRecord::close)
            sessions.clear()
            tickets.clear()
            ticketReplays.clear()
            privateInvites.clear()
            privateInviteCreateReplays.clear()
            privateInviteAcceptReplays.clear()
        }
    }

    private data class TicketReplay(
        val mode: OnlineMatchMode,
        val ticketId: String,
    )

    private data class TicketRecord(
        val mode: OnlineMatchMode,
        var ticket: MatchmakingTicket,
    )

    private data class PrivateInviteCreateReplay(
        val mode: OnlineMatchMode,
        val inviteCode: String,
    )

    private data class PrivateInviteAcceptReplay(
        val inviteCode: String,
    )

    private data class PrivateInviteRecord(
        val mode: OnlineMatchMode,
        val ownerPlayerId: String,
        var guestPlayerId: String? = null,
        var invite: PrivateDuelInvite,
    )

    private class SessionRecord(
        val sessionId: String,
        val match: DuelMatch,
        private val memberships: Map<String, DuelParticipant>,
        private val bot: ServerBotPlayer?,
    ) : AutoCloseable {
        private var revision: Long = 0
        private val commandReplays = mutableMapOf<String, CommandReplay>()
        private val pendingSecrets = mutableMapOf<DuelParticipant, CharArray>()

        fun isMember(playerId: String): Boolean = memberships.containsKey(playerId)

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
            pendingSecrets[participant] = secret.toCharArray()
            if (bot != null) {
                check(participant == DuelParticipant.FIRST)
                configureSecret(DuelParticipant.FIRST, pendingSecrets.remove(DuelParticipant.FIRST)!!)
                configureSecret(DuelParticipant.SECOND, bot.revealSecret().toCharArray())
            } else if (pendingSecrets.keys.containsAll(DuelParticipant.entries)) {
                configureSecret(DuelParticipant.FIRST, pendingSecrets.remove(DuelParticipant.FIRST)!!)
                configureSecret(DuelParticipant.SECOND, pendingSecrets.remove(DuelParticipant.SECOND)!!)
            }
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
        ) { participant ->
            val afterPlayer = match.submitGuess(
                participant,
                MutableDuelCommand.guess(guess.toCharArray()),
            )
            revision += 1
            if (bot != null && afterPlayer.phase == DuelPhase.ACTIVE) {
                bot.scoreIncomingGuess(guess)
                val turn = bot.nextTurn()
                val afterBot = match.submitGuess(
                    DuelParticipant.SECOND,
                    MutableDuelCommand.guess(turn.guess.toCharArray()),
                )
                bot.registerTurnFeedback(turn.guess, afterBot.attempts.last().exactMatches)
                revision += 1
            }
            snapshotFor(playerId)
        }

        @Synchronized
        fun snapshotFor(playerId: String): OnlineDuelSnapshot {
            val viewer = memberships[playerId] ?: throw OnlineMembershipRejectedException()
            return match.snapshot().toOnlineSnapshot(
                sessionId = sessionId,
                revision = revision,
                viewer = viewer,
                pendingSecrets = pendingSecrets.keys,
            )
        }

        private fun submit(
            playerId: String,
            commandId: String,
            expectedRevision: Long,
            fingerprint: String,
            operation: SessionRecord.(DuelParticipant) -> OnlineDuelSnapshot,
        ): OnlineDuelSnapshot {
            requireCanonicalUuid(commandId, "commandId")
            val participant = memberships[playerId] ?: throw OnlineMembershipRejectedException()
            val replayKey = "$playerId:$commandId"
            commandReplays[replayKey]?.let { replay ->
                if (replay.fingerprint != fingerprint) throw OnlineCommandIdReusedException()
                return replay.snapshot
            }
            if (expectedRevision != revision) throw OnlineRevisionConflictException(snapshotFor(playerId))
            val result = operation(participant)
            commandReplays[replayKey] = CommandReplay(fingerprint, result)
            return result
        }

        private fun configureSecret(participant: DuelParticipant, secret: CharArray) {
            try {
                match.setSecret(participant, MutableDuelCommand.secret(secret))
            } finally {
                secret.fill(CLEARED_DIGIT)
            }
        }

        @Synchronized
        override fun close() {
            pendingSecrets.values.forEach { it.fill(CLEARED_DIGIT) }
            pendingSecrets.clear()
            match.close()
        }
    }

    private data class CommandReplay(
        val fingerprint: String,
        val snapshot: OnlineDuelSnapshot,
    )
}

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
): OnlineDuelSnapshot =
    OnlineDuelSnapshot(
        sessionId = sessionId,
        revision = revision,
        phase = phase.name.lowercase(),
        currentTurn = currentTurn?.publicActorFor(viewer),
        winner = winner?.publicActorFor(viewer),
        codeLength = config.codeLength,
        attemptLimit = config.attemptLimit,
        allowDuplicates = config.allowDuplicates,
        attempts = attempts.map { attempt ->
            OnlineDuelAttempt(
                actor = attempt.attacker.publicActorFor(viewer),
                exactMatches = attempt.exactMatches,
                number = attempt.number,
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

private fun OnlineMatchMode.gameConfig(): GameConfig = when (this) {
    OnlineMatchMode.CLASSIC -> GameConfig(
        codeLength = 4,
        allowDuplicates = false,
        attemptLimit = 9,
    )
    OnlineMatchMode.PRO -> GameConfig(
        codeLength = 6,
        allowDuplicates = false,
        attemptLimit = 11,
        forbidAdjacentDuplicates = true,
    )
    OnlineMatchMode.PRO_PLUS -> GameConfig(
        codeLength = 8,
        allowDuplicates = false,
        attemptLimit = 13,
        forbidAdjacentDuplicates = true,
    )
}

private fun OnlineMatchMode.botDifficulty(): BotDifficulty = when (this) {
    OnlineMatchMode.CLASSIC -> BotDifficulty.MEDIUM
    OnlineMatchMode.PRO -> BotDifficulty.HARD
    OnlineMatchMode.PRO_PLUS -> BotDifficulty.EXPERT
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
