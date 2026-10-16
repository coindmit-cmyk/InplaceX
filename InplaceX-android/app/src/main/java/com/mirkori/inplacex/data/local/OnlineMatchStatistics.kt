package com.mirkori.inplacex.data.local

import com.mirkori.inplacex.platform.online.OnlineDuelSnapshotState
import java.util.UUID

internal enum class OnlineMatchOutcome { WIN, LOSS, NEUTRAL }

internal data class OnlineMatchStatisticsFact(
    val sessionId: String,
    val started: Boolean,
    val terminalOutcome: OnlineMatchOutcome?,
)

/** Maps only validated, caller-relative server facts; it never infers a winner. */
internal object OnlineMatchStatistics {
    private val canonicalUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun fromSnapshot(snapshot: OnlineDuelSnapshotState): OnlineMatchStatisticsFact? {
        val id = snapshot.sessionId
        if (!canonicalUuid.matches(id) || UUID.fromString(id).toString() != id) return null
        if (snapshot.phase !in setOf("setup", "active", "finished")) return null
        if (snapshot.currentTurn != null && snapshot.currentTurn !in setOf("player", "opponent")) return null
        if (snapshot.attempts.any { it.actor !in setOf("player", "opponent") }) return null
        if (snapshot.startedAtEpochMs != null && snapshot.startedAtEpochMs < 0L) return null
        if (snapshot.phase != "finished" && snapshot.winner != null) return null
        if (snapshot.winner != null && snapshot.winner !in setOf("player", "opponent")) return null

        val outcome = when (snapshot.phase) {
            "finished" -> when (snapshot.winner) {
                "player" -> OnlineMatchOutcome.WIN
                "opponent" -> OnlineMatchOutcome.LOSS
                else -> OnlineMatchOutcome.NEUTRAL
            }
            else -> null
        }
        val started = snapshot.phase == "active" ||
            outcome == OnlineMatchOutcome.WIN || outcome == OnlineMatchOutcome.LOSS ||
            (snapshot.phase == "finished" && snapshot.startedAtEpochMs != null && snapshot.startedAtEpochMs > 0L)
        return OnlineMatchStatisticsFact(id, started, outcome)
    }
}
