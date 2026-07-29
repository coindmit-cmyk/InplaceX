package com.mirkori.inplacex.core.match

object RaceRewardPolicy {
    const val WIN_COINS = 10

    fun coinsFor(won: Boolean): Int = if (won) WIN_COINS else 0
}
