package com.mirkori.inplacex.backend.online

import com.mirkori.inplacex.backend.bot.ServerBotPlayer
import com.mirkori.inplacex.backend.domain.duel.DuelMatch
import com.mirkori.inplacex.backend.domain.duel.DuelParticipant
import com.mirkori.inplacex.backend.domain.duel.DuelPhase
import com.mirkori.inplacex.backend.domain.duel.DuelSnapshot
import com.mirkori.inplacex.backend.session.domain.MutableDuelCommand
import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.core.model.GameConfig
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class OnlineMatchMode {
    CLASSIC,
    PRO,
    PRO_PLUS,
}

enum class MatchmakingStatus {
    MATCHED,
    CANCELLED,
}

data class MatchmakingTicket(
    val ticketId: String,
    val ownerPlayerId: String,
    val status: MatchmakingStatus,
    val sessionId: String?,
    val matchedWithBot: Boolean,
    val createdAt: Instant,
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

/**
 * First server-authoritative online vertical slice.
 *
 * Membership is created and resolved only from the registry-owned session
 * record. The caller never supplies a membership resolver or participant role.
 * The initial opponent is a server bot fallback; replacing it with a human
 * matchmaking peer does not change the session command contract.
 */
class AuthoritativeOnlineDuelService(
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val tickets = ConcurrentHashMap<String, MatchmakingTicket>()
    private val ticketReplays = ConcurrentHashMap<String, TicketReplay>()
    private val sessions = ConcurrentHashMap<String, SessionRecord>()

    fun createTicket(
        playerId: String,
        commandId: String,
        mode: OnlineMatchMode,
    ): MatchmakingTicket {
        requireCanonicalUuid(playerId, "playerId")
        requireCanonicalUuid(commandId, "commandId")
        val replayKey = "$playerId:$commandId"
        ticketReplays[replayKey]?.let { replay ->
            if (replay.mode != mode) throw OnlineCommandIdReusedException()
            return replay.ticket
        }

        val sessionId = UUID.randomUUID().toString()
        val config = mode.gameConfig()
        val bot = ServerBotPlayer.create(
            config = config,
            difficulty = mode.botDifficulty(),
            secretSeed = sessionId.hashCode().toLong(),
            brainSeed = playerId.hashCode().toLong() xor sessionId.hashCode().toLong(),
        )
        val record = SessionRecord(
            sessionId = sessionId,
            ownerPlayerId = playerId,
            match = DuelMatch.create(config),
            bot = bot,
        )
        sessions[sessionId] = record
        val ticket = MatchmakingTicket(
            ticketId = UUID.randomUUID().toString(),
            ownerPlayerId = playerId,
            status = MatchmakingStatus.MATCHED,
            sessionId = sessionId,
            matchedWithBot = true,
            createdAt = clock.instant(),
        )
        tickets[ticket.ticketId] = ticket
        val prior = ticketReplays.putIfAbsent(replayKey, TicketReplay(mode, ticket))
        if (prior != null) {
            sessions.remove(sessionId)?.close()
            tickets.remove(ticket.ticketId)
            if (prior.mode != mode) throw OnlineCommandIdReusedException()
            return prior.ticket
        }
        return ticket
    }

    fun readTicket(playerId: String, ticketId: String): MatchmakingTicket {
        val ticket = tickets[ticketId] ?: throw NoSuchElementException("matchmaking ticket not found")
        if (ticket.ownerPlayerId != playerId) throw OnlineMembershipRejectedException()
        return ticket
    }

    fun readSession(playerId: String, sessionId: String): OnlineDuelSnapshot =
        sessionFor(playerId, sessionId).snapshot()

    fun submitSecret(
        playerId: String,
        sessionId: String,
        commandId: String,
        expectedRevision: Long,
        secret: String,
    ): OnlineDuelSnapshot = sessionFor(playerId, sessionId).submit(
        commandId = commandId,
        expectedRevision = expectedRevision,
        fingerprint = fingerprint("secret", secret),
    ) {
        match.setSecret(
            DuelParticipant.FIRST,
            MutableDuelCommand.secret(secret.toCharArray()),
        )
        match.setSecret(
            DuelParticipant.SECOND,
            MutableDuelCommand.secret(bot.revealSecret().toCharArray()),
        )
        revision += 1
        snapshot()
    }

    fun submitGuess(
        playerId: String,
        sessionId: String,
        commandId: String,
        expectedRevision: Long,
        guess: String,
    ): OnlineDuelSnapshot = sessionFor(playerId, sessionId).submit(
        commandId = commandId,
        expectedRevision = expectedRevision,
        fingerprint = fingerprint("guess", guess),
    ) {
        val afterPlayer = match.submitGuess(
            DuelParticipant.FIRST,
            MutableDuelCommand.guess(guess.toCharArray()),
        )
        bot.scoreIncomingGuess(guess)
        revision += 1
        if (afterPlayer.phase == DuelPhase.ACTIVE) {
            val turn = bot.nextTurn()
            val afterBot = match.submitGuess(
                DuelParticipant.SECOND,
                MutableDuelCommand.guess(turn.guess.toCharArray()),
            )
            val exactMatches = afterBot.attempts.last().exactMatches
            bot.registerTurnFeedback(turn.guess, exactMatches)
            revision += 1
        }
        snapshot()
    }

    private fun sessionFor(playerId: String, sessionId: String): SessionRecord {
        val record = sessions[sessionId] ?: throw NoSuchElementException("online session not found")
        if (record.ownerPlayerId != playerId) throw OnlineMembershipRejectedException()
        return record
    }

    override fun close() {
        sessions.values.forEach(SessionRecord::close)
        sessions.clear()
        tickets.clear()
        ticketReplays.clear()
    }

    private data class TicketReplay(
        val mode: OnlineMatchMode,
        val ticket: MatchmakingTicket,
    )

    private class SessionRecord(
        val sessionId: String,
        val ownerPlayerId: String,
        val match: DuelMatch,
        val bot: ServerBotPlayer,
    ) : AutoCloseable {
        var revision: Long = 0
        private val commandReplays = mutableMapOf<String, CommandReplay>()

        @Synchronized
        fun submit(
            commandId: String,
            expectedRevision: Long,
            fingerprint: String,
            operation: SessionRecord.() -> OnlineDuelSnapshot,
        ): OnlineDuelSnapshot {
            requireCanonicalUuid(commandId, "commandId")
            commandReplays[commandId]?.let { replay ->
                if (replay.fingerprint != fingerprint) throw OnlineCommandIdReusedException()
                return replay.snapshot
            }
            if (expectedRevision != revision) throw OnlineRevisionConflictException(snapshot())
            val result = operation()
            commandReplays[commandId] = CommandReplay(fingerprint, result)
            return result
        }

        @Synchronized
        fun snapshot(): OnlineDuelSnapshot = match.snapshot().toOnlineSnapshot(sessionId, revision)

        override fun close() = match.close()
    }

    private data class CommandReplay(
        val fingerprint: String,
        val snapshot: OnlineDuelSnapshot,
    )
}

private fun DuelSnapshot.toOnlineSnapshot(sessionId: String, revision: Long): OnlineDuelSnapshot =
    OnlineDuelSnapshot(
        sessionId = sessionId,
        revision = revision,
        phase = phase.name.lowercase(),
        currentTurn = currentTurn?.publicActor(),
        winner = winner?.publicActor(),
        codeLength = config.codeLength,
        attemptLimit = config.attemptLimit,
        allowDuplicates = config.allowDuplicates,
        attempts = attempts.map { attempt ->
            OnlineDuelAttempt(
                actor = attempt.attacker.publicActor(),
                exactMatches = attempt.exactMatches,
                number = attempt.number,
            )
        },
        participants = participants.map { participant ->
            OnlineDuelParticipant(
                actor = participant.participant.publicActor(),
                secretConfigured = participant.secretConfigured,
                attemptsUsed = participant.attemptsUsed,
                attemptsLeft = participant.attemptsLeft,
            )
        },
    )

private fun DuelParticipant.publicActor(): String =
    if (this == DuelParticipant.FIRST) "player" else "opponent"

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
