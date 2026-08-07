package com.mirkori.inplacex.platform.ads

import com.mirkori.inplacex.data.local.ModeStats

/** Counts authoritative results only; match starts include abandoned games and are not eligible. */
internal fun completedMatchCountForAds(
    pve: ModeStats,
    pvp: ModeStats,
    company: ModeStats,
): Int = sequenceOf(pve, pvp, company)
    .sumOf { stats ->
        stats.wins.toLong().coerceAtLeast(0L) +
            stats.losses.toLong().coerceAtLeast(0L)
    }
    .coerceAtMost(Int.MAX_VALUE.toLong())
    .toInt()
