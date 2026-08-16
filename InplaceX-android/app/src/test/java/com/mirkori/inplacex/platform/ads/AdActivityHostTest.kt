package com.mirkori.inplacex.platform.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdActivityHostTest {
    @Test
    fun `active host remains usable while modal dialog owns window focus`() {
        assertTrue(
            isAdPresentationHostUsable(
                isFinishing = false,
                isDestroyed = false,
                isResumed = true,
            ),
        )
    }

    @Test
    fun `finishing destroyed or background host is rejected`() {
        assertFalse(
            isAdPresentationHostUsable(
                isFinishing = true,
                isDestroyed = false,
                isResumed = true,
            ),
        )
        assertFalse(
            isAdPresentationHostUsable(
                isFinishing = false,
                isDestroyed = true,
                isResumed = true,
            ),
        )
        assertFalse(
            isAdPresentationHostUsable(
                isFinishing = false,
                isDestroyed = false,
                isResumed = false,
            ),
        )
    }
}
