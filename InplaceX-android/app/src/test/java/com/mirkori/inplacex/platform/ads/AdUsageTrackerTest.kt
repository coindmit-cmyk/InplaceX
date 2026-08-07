package com.mirkori.inplacex.platform.ads

import org.junit.Assert.assertEquals
import org.junit.Test

class AdUsageTrackerTest {
    @Test
    fun `tracker persists foreground time and completed-match cadence`() {
        val persistence = InMemoryPersistence()
        var now = 1_000L
        val tracker = AdUsageTracker(
            persistence = persistence,
            elapsedRealtimeMillis = { now },
        )

        tracker.ensureCompletedMatchBaseline(20)
        tracker.onForeground()
        now = 6_500L
        val afterMatch = tracker.recordCompletedMatch()

        assertEquals(21, afterMatch.completedMatches)
        assertEquals(21, afterMatch.matchesSinceLastInterstitial)
        assertEquals(5_500L, afterMatch.foregroundUsageMillis)

        tracker.recordInterstitialPresented()
        tracker.onBackground()

        assertEquals(0, persistence.snapshot.matchesSinceLastInterstitial)
        assertEquals(5_500L, persistence.snapshot.foregroundUsageMillis)
    }

    @Test
    fun `foreground starts only once until backgrounded`() {
        val persistence = InMemoryPersistence()
        var now = 10L
        val tracker = AdUsageTracker(persistence) { now }

        tracker.onForeground()
        now = 20L
        tracker.onForeground()
        now = 30L
        tracker.onBackground()

        assertEquals(20L, persistence.snapshot.foregroundUsageMillis)
    }

    private class InMemoryPersistence : AdUsagePersistence {
        var snapshot = AdUsageSnapshot()

        override fun load(): AdUsageSnapshot = snapshot

        override fun save(snapshot: AdUsageSnapshot) {
            this.snapshot = snapshot
        }
    }
}
