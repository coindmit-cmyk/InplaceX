package com.mirkori.inplacex.core.match

import org.junit.Assert.assertEquals
import org.junit.Test

class PreMatchConfigTest {
    @Test
    fun defaultsToSixtySecondSecretSelectionTimeout() {
        assertEquals(60, PreMatchConfig().secretSelectionTimeoutSeconds)
    }

    @Test
    fun defaultsToFiveSecondDeveloperBotSecretDelay() {
        assertEquals(5, PreMatchConfig().devBotSecretDelaySeconds)
    }
}
