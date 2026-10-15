package com.mirkori.inplacex.data.local

import com.mirkori.inplacex.platform.online.OnlineDuelAttemptState
import com.mirkori.inplacex.platform.online.OnlineDuelSnapshotState
import com.mirkori.inplacex.platform.online.RemoteFriendPlayStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineMatchStatisticsTest {
    private val id = "123e4567-e89b-12d3-a456-426614174000"

    private fun snapshot(
        phase: String = "setup",
        winner: String? = null,
        id: String = this.id,
        startedAt: Long? = null,
        currentTurn: String? = null,
        attempts: List<OnlineDuelAttemptState> = emptyList(),
        style: RemoteFriendPlayStyle = RemoteFriendPlayStyle.TURN_BASED,
    ) = OnlineDuelSnapshotState(
        sessionId = id,
        revision = 1,
        phase = phase,
        currentTurn = currentTurn,
        winner = winner,
        playStyle = style,
        codeLength = 4,
        attemptLimit = null,
        allowDuplicates = true,
        startedAtEpochMs = startedAt,
        attempts = attempts,
    )

    @Test fun setupIsNotPlayedAndActiveIsStartedForBothStyles() {
        for (style in RemoteFriendPlayStyle.values()) {
            assertEquals(OnlineMatchStatisticsFact(id, false, null), OnlineMatchStatistics.fromSnapshot(snapshot(style = style)))
            assertEquals(OnlineMatchStatisticsFact(id, true, null), OnlineMatchStatistics.fromSnapshot(snapshot(phase = "active", style = style)))
        }
    }

    @Test fun winnerIsStrictlyCallerRelativeAndFinishedWinnerImpliesStart() {
        assertEquals(OnlineMatchStatisticsFact(id, true, OnlineMatchOutcome.WIN), OnlineMatchStatistics.fromSnapshot(snapshot("finished", "player")))
        assertEquals(OnlineMatchStatisticsFact(id, true, OnlineMatchOutcome.LOSS), OnlineMatchStatistics.fromSnapshot(snapshot("finished", "opponent")))
        assertNull(OnlineMatchStatistics.fromSnapshot(snapshot("finished", "account-123")))
        assertNull(OnlineMatchStatistics.fromSnapshot(snapshot("active", "player")))
    }

    @Test fun neutralFinishNeedsPositiveStartTimestampForIntrinsicStartProof() {
        val absent = OnlineMatchStatistics.fromSnapshot(snapshot("finished"))!!
        assertFalse(absent.started)
        assertEquals(OnlineMatchOutcome.NEUTRAL, absent.terminalOutcome)
        assertFalse(OnlineMatchStatistics.fromSnapshot(snapshot("finished", startedAt = 0))!!.started)
        assertTrue(OnlineMatchStatistics.fromSnapshot(snapshot("finished", startedAt = 1))!!.started)
        assertNull(OnlineMatchStatistics.fromSnapshot(snapshot("finished", startedAt = -1)))
    }

    @Test fun rejectsMalformedIdentityPhaseAndActors() {
        assertNull(OnlineMatchStatistics.fromSnapshot(snapshot(id = "not-a-uuid")))
        assertNull(OnlineMatchStatistics.fromSnapshot(snapshot(id = id.uppercase())))
        assertNull(OnlineMatchStatistics.fromSnapshot(snapshot(id = "1-2-3-4-5")))
        assertNull(OnlineMatchStatistics.fromSnapshot(snapshot(phase = "cancelled")))
        assertNull(OnlineMatchStatistics.fromSnapshot(snapshot(phase = "ACTIVE")))
        assertNull(OnlineMatchStatistics.fromSnapshot(snapshot(currentTurn = "account-123")))
        assertNull(OnlineMatchStatistics.fromSnapshot(snapshot(attempts = listOf(OnlineDuelAttemptState("account-123", 0, 1)))))
    }
}
