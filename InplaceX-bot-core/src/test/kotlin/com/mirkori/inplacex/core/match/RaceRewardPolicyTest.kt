package com.mirkori.inplacex.core.match

import org.junit.Assert.assertEquals
import org.junit.Test

class RaceRewardPolicyTest {
    @Test
    fun `only a race victory grants coins`() {
        assertEquals(10, RaceRewardPolicy.coinsFor(won = true))
        assertEquals(0, RaceRewardPolicy.coinsFor(won = false))
    }
}
