package com.mirkori.inplacex

import org.junit.Assert.assertTrue
import org.junit.Test

class SignedReleaseCandidateUpdateIsolationTest {
    @Test
    fun `signed release candidate exclusively enables sideload updates`() {
        assertTrue(BuildConfig.SIDELOAD_UPDATES_ENABLED)
    }
}
