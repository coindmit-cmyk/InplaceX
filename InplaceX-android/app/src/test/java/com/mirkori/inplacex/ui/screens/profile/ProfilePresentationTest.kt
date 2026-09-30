package com.mirkori.inplacex.ui.screens.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfilePresentationTest {
    @Test
    fun `initials are stable for empty single and compound names`() {
        assertEquals("IX", playerInitials(""))
        assertEquals("PL", playerInitials("Player"))
        assertEquals("P7", playerInitials("Player_7065"))
        assertEquals("АП", playerInitials("Анна Петрова"))
    }
}
