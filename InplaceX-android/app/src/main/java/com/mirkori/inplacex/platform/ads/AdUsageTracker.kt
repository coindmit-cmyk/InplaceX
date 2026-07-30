package com.mirkori.inplacex.platform.ads

import android.content.Context
import android.os.SystemClock

data class AdUsageSnapshot(
    val completedMatches: Int = 0,
    val matchesSinceLastInterstitial: Int = 0,
    val foregroundUsageMillis: Long = 0,
) {
    init {
        require(completedMatches >= 0)
        require(matchesSinceLastInterstitial >= 0)
        require(foregroundUsageMillis >= 0)
    }
}

interface AdUsagePersistence {
    fun load(): AdUsageSnapshot

    fun save(snapshot: AdUsageSnapshot)
}

class AdUsageTracker(
    private val persistence: AdUsagePersistence,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private var foregroundStartedAtMillis: Long? = null

    @Synchronized
    fun onForeground() {
        if (foregroundStartedAtMillis == null) {
            foregroundStartedAtMillis = elapsedRealtimeMillis()
        }
    }

    @Synchronized
    fun onBackground() {
        val startedAt = foregroundStartedAtMillis ?: return
        val elapsed = (elapsedRealtimeMillis() - startedAt).coerceAtLeast(0)
        val current = persistence.load()
        persistence.save(
            current.copy(
                foregroundUsageMillis = current.foregroundUsageMillis + elapsed,
            ),
        )
        foregroundStartedAtMillis = null
    }

    @Synchronized
    fun ensureCompletedMatchBaseline(matchesPlayed: Int) {
        require(matchesPlayed >= 0)
        val current = persistence.load()
        if (current.completedMatches == 0 && matchesPlayed > 0) {
            persistence.save(
                current.copy(
                    completedMatches = matchesPlayed,
                    matchesSinceLastInterstitial = matchesPlayed,
                ),
            )
        }
    }

    @Synchronized
    fun recordCompletedMatch(): AdUsageSnapshot {
        val current = persistence.load()
        current.copy(
            completedMatches = current.completedMatches + 1,
            matchesSinceLastInterstitial = current.matchesSinceLastInterstitial + 1,
        ).also(persistence::save)
        return snapshot()
    }

    @Synchronized
    fun recordInterstitialPresented() {
        persistence.save(
            persistence.load().copy(matchesSinceLastInterstitial = 0),
        )
    }

    @Synchronized
    fun snapshot(): AdUsageSnapshot {
        val persisted = persistence.load()
        val activeElapsed = foregroundStartedAtMillis?.let { startedAt ->
            (elapsedRealtimeMillis() - startedAt).coerceAtLeast(0)
        } ?: 0
        return persisted.copy(
            foregroundUsageMillis = persisted.foregroundUsageMillis + activeElapsed,
        )
    }

    companion object {
        fun create(context: Context): AdUsageTracker =
            AdUsageTracker(SharedPreferencesAdUsagePersistence(context))
    }
}

private class SharedPreferencesAdUsagePersistence(
    context: Context,
) : AdUsagePersistence {
    private val preferences = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )

    override fun load(): AdUsageSnapshot =
        AdUsageSnapshot(
            completedMatches = preferences.getInt(CompletedMatchesKey, 0).coerceAtLeast(0),
            matchesSinceLastInterstitial = preferences
                .getInt(MatchesSinceInterstitialKey, 0)
                .coerceAtLeast(0),
            foregroundUsageMillis = preferences
                .getLong(ForegroundUsageMillisKey, 0)
                .coerceAtLeast(0),
        )

    override fun save(snapshot: AdUsageSnapshot) {
        preferences.edit()
            .putInt(CompletedMatchesKey, snapshot.completedMatches)
            .putInt(MatchesSinceInterstitialKey, snapshot.matchesSinceLastInterstitial)
            .putLong(ForegroundUsageMillisKey, snapshot.foregroundUsageMillis)
            .apply()
    }

    private companion object {
        const val PreferencesName = "inplacex_ad_usage"
        const val CompletedMatchesKey = "completed_matches"
        const val MatchesSinceInterstitialKey = "matches_since_last_interstitial"
        const val ForegroundUsageMillisKey = "foreground_usage_millis"
    }
}
