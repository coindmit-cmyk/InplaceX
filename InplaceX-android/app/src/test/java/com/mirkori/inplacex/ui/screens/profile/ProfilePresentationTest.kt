package com.mirkori.inplacex.ui.screens.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfilePresentationTest {
    @Test
    fun `initials are stable for empty single and compound names`() {
        assertEquals("IX", playerInitials(""))
        assertEquals("PL", playerInitials("Player"))
        assertEquals("P7", playerInitials("Player_7065"))
        assertEquals("АП", playerInitials("Анна Петрова"))
    }

    @Test
    fun `public handle conflicts stay attached to the handle field`() {
        assertEquals(
            "profile.mirkori.handle.taken",
            publicHandleFieldErrorKey("profile.mirkori.handle.taken", null),
        )
        assertEquals(
            "profile.mirkori.handle.invalid",
            publicHandleFieldErrorKey(null, "profile.mirkori.handle.invalid"),
        )
        assertNull(publicHandleFieldErrorKey("profile.mirkori.handle.unavailable", null))
    }
}
