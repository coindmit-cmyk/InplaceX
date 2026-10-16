package com.mirkori.inplacex.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mirkori.inplacex.platform.online.OnlineDuelSnapshotState
import com.mirkori.inplacex.platform.online.RemoteFriendPlayStyle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnlineMatchStatisticsInstrumentedTest {
    private val first = "123e4567-e89b-12d3-a456-426614174000"
    private val second = "123e4567-e89b-12d3-a456-426614174001"
    private val third = "123e4567-e89b-12d3-a456-426614174002"

    private fun snapshot(
        id: String = first,
        phase: String = "active",
        winner: String? = null,
        startedAt: Long? = null,
        style: RemoteFriendPlayStyle = RemoteFriendPlayStyle.TURN_BASED,
    ) = OnlineDuelSnapshotState(
        sessionId = id,
        revision = 1,
        phase = phase,
        currentTurn = null,
        winner = winner,
        playStyle = style,
        codeLength = 4,
        attemptLimit = null,
        allowDuplicates = true,
        startedAtEpochMs = startedAt,
        attempts = emptyList(),
    )

    private fun repository(context: Context, config: LocalDatabaseConfig) = GameProgressRepository(context, config)
    private fun fixedNowMs() = 1_725_000_000_000L

    @Test fun repeatedPollsAndRepositoryReconstructionCountOnce() {
        withIsolatedDatabase("online_repeat", ::fixedNowMs) { context, config ->
            val a = repository(context, config)
            val before = a.loadState()
            repeat(3) { a.recordOnlineSnapshot(snapshot()) }
            val b = repository(context, config)
            repeat(3) { b.recordOnlineSnapshot(snapshot()) }
            repeat(3) { b.recordOnlineSnapshot(snapshot(phase = "finished", winner = "player")) }
            val result = a.recordOnlineSnapshot(snapshot(phase = "finished", winner = "player"))
            assertEquals(before.matchesPlayed + 1, result.matchesPlayed)
            assertEquals(before.matchesWon + 1, result.matchesWon)
            assertEquals(before.pvpStats.wins + 1, result.pvpStats.wins)
            assertEquals(before.pvpStats.losses, result.pvpStats.losses)
            assertEquals(before.coins, result.coins)
        }
    }

    @Test fun terminalFirstRestoreAndBothStylesUsePvpWithoutCoins() {
        withIsolatedDatabase("online_terminal", ::fixedNowMs) { context, config ->
            val repo = repository(context, config)
            val before = repo.loadState()
            repo.recordOnlineSnapshot(snapshot(first, "finished", "player", style = RemoteFriendPlayStyle.RACE))
            val after = repo.recordOnlineSnapshot(snapshot(second, "finished", "opponent", style = RemoteFriendPlayStyle.TURN_BASED))
            assertEquals(before.matchesPlayed + 2, after.matchesPlayed)
            assertEquals(before.matchesWon + 1, after.matchesWon)
            assertEquals(before.pvpStats.wins + 1, after.pvpStats.wins)
            assertEquals(before.pvpStats.losses + 1, after.pvpStats.losses)
            assertEquals(before.coins, after.coins)
        }
    }

    @Test fun neutralTimeoutRecordsTerminalButOnlyCountsProvenPlay() {
        withIsolatedDatabase("online_neutral", ::fixedNowMs) { context, config ->
            val repo = repository(context, config)
            val before = repo.loadState()
            repo.recordOnlineSnapshot(snapshot(first, "setup"))
            repo.recordOnlineSnapshot(snapshot(first, "finished"))
            assertEquals(before.matchesPlayed, repo.loadState().matchesPlayed)
            repo.recordOnlineSnapshot(snapshot(first, "finished", "player", startedAt = 100))
            repo.recordOnlineSnapshot(snapshot(first, "active"))
            assertEquals(before.matchesPlayed, repo.loadState().matchesPlayed)
            repo.recordOnlineSnapshot(snapshot(second, "active"))
            repo.recordOnlineSnapshot(snapshot(second, "finished"))
            repo.recordOnlineSnapshot(snapshot(third, "finished", startedAt = 100))
            val after = repo.loadState()
            assertEquals(before.matchesPlayed + 2, after.matchesPlayed)
            assertEquals(before.matchesWon, after.matchesWon)
            assertEquals(before.pvpStats.losses, after.pvpStats.losses)
        }
    }

    @Test fun conflictingAndStaleSnapshotsCannotChangeAcceptedTerminal() {
        withIsolatedDatabase("online_immutable", ::fixedNowMs) { context, config ->
            val repo = repository(context, config)
            repo.recordOnlineSnapshot(snapshot(first, "finished", "opponent"))
            repo.recordOnlineSnapshot(snapshot(first, "active"))
            repo.recordOnlineSnapshot(snapshot(first, "finished", "player"))
            val result = repository(context, config).loadState()
            assertEquals(1, result.matchesPlayed)
            assertEquals(0, result.matchesWon)
            assertEquals(0, result.pvpStats.wins)
            assertEquals(1, result.pvpStats.losses)
        }
    }

    @Test fun malformedFactsAreIgnoredAndSessionsRemainIndependent() {
        withIsolatedDatabase("online_invalid", ::fixedNowMs) { context, config ->
            val repo = repository(context, config)
            repo.recordOnlineSnapshot(snapshot("invalid", "finished", "player"))
            repo.recordOnlineSnapshot(snapshot(first, "unknown", "player"))
            repo.recordOnlineSnapshot(snapshot(first, "finished", "account-id"))
            assertEquals(0, repo.loadState().matchesPlayed)
            repo.recordOnlineSnapshot(snapshot(first))
            repo.recordOnlineSnapshot(snapshot(second))
            assertEquals(2, repo.loadState().matchesPlayed)
        }
    }

    @Test fun concurrentRepositoryInstancesCountSameSessionOnce() {
        withIsolatedDatabase("online_concurrent", ::fixedNowMs) { context, config ->
            val pool = Executors.newFixedThreadPool(4)
            val ready = CountDownLatch(4)
            val release = CountDownLatch(1)
            try {
                val futures = (1..4).map {
                    pool.submit<Unit> {
                        ready.countDown()
                        release.await()
                        repository(context, config).recordOnlineSnapshot(snapshot(phase = "finished", winner = "player"))
                    }
                }
                assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
                release.countDown()
                futures.forEach { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
                val result = repository(context, config).loadState()
                assertEquals(1, result.matchesPlayed)
                assertEquals(1, result.matchesWon)
                assertEquals(1, result.pvpStats.wins)
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
        }
    }

    @Test fun failedCounterWriteRollsBackReceiptAndRetryCountsOnce() {
        withIsolatedDatabase("online_rollback", ::fixedNowMs) { context, config ->
            val db = GameProgressDatabase(context, config).writableDatabase
            db.execSQL("CREATE TRIGGER fail_online_stats BEFORE UPDATE OF matches_played ON game_progress BEGIN SELECT RAISE(FAIL, 'blocked'); END")
            val repo = repository(context, config)
            var failed = false
            try {
                repo.recordOnlineSnapshot(snapshot())
            } catch (_: android.database.SQLException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(0, repo.loadState().matchesPlayed)
            db.query(GameProgressDatabase.TABLE_ONLINE_MATCH_STAT_RECEIPTS, arrayOf("session_id"), null, null, null, null, null).use {
                assertFalse(it.moveToFirst())
            }
            db.execSQL("DROP TRIGGER fail_online_stats")
            repo.recordOnlineSnapshot(snapshot())
            repo.recordOnlineSnapshot(snapshot())
            assertEquals(1, repo.loadState().matchesPlayed)
        }
    }

    @Test fun onlineUpdatePreservesNonStatProfileFields() {
        withIsolatedDatabase("online_preserve", ::fixedNowMs) { context, config ->
            val repo = repository(context, config)
            repo.updatePlayerDisplayName("Keeper")
            repo.activateProduct(MonetizationProductType.REMOVE_ADS)
            val before = repo.loadState()
            val after = repo.recordOnlineSnapshot(snapshot(phase = "finished", winner = "player"))
            assertEquals("Keeper", after.playerDisplayName)
            assertEquals(before.coins, after.coins)
            assertEquals(before.campaignEnergy, after.campaignEnergy)
            assertEquals(before.openPositionHints, after.openPositionHints)
            assertEquals(before.highestUnlockedCampaignLevel, after.highestUnlockedCampaignLevel)
            assertEquals(before.pveStats, after.pveStats)
            assertEquals(before.companyStats, after.companyStats)
            assertTrue(after.adFreePurchased)
            assertEquals(before.proSubscriptionActive, after.proSubscriptionActive)
            assertEquals(before.temporaryProExpiresAtMs, after.temporaryProExpiresAtMs)
        }
    }
}
