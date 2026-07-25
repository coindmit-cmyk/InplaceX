package com.mirkori.inplacex.backend.session.contract

import java.util.UUID

const val PUBLIC_SESSION_SCHEMA_VERSION: String = "1.0"

@JvmInline
value class PublicSessionId private constructor(val value: String) {
    companion object {
        fun parse(value: String): PublicSessionId = PublicSessionId(requireCanonicalUuid(value))
    }
}

@JvmInline
value class PublicParticipantId private constructor(val value: String) {
    companion object {
        fun parse(value: String): PublicParticipantId = PublicParticipantId(requireCanonicalUuid(value))
    }
}

data class PublicGameConfig(
    val codeLength: Int,
    val attemptLimit: Int,
) {
    init {
        require(codeLength in 4..20) { "Public code length must be in 4..20" }
        require(attemptLimit in 1..1_000) { "Public attempt limit must be in 1..1000" }
    }
}

enum class PublicDuelPhase {
    SETUP_WAITING_FOR_PLAYERS,
    SETUP_SECRET_A,
    SETUP_SECRET_B,
    ACTIVE_TURN_A,
    ACTIVE_TURN_B,
    FINISHED,
    ABANDONED,
}

enum class PublicParticipantSlot {
    A,
    B,
}

enum class PublicParticipantType {
    HUMAN,
    BOT,
}

private fun requireCanonicalUuid(value: String): String {
    val parsed = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Public identifier must be a canonical UUID")
    }
    require(value == parsed.toString()) { "Public identifier must be a canonical lowercase UUID" }
    require(parsed.mostSignificantBits != 0L || parsed.leastSignificantBits != 0L) {
        "Public identifier must not be the nil UUID"
    }
    return value
}
