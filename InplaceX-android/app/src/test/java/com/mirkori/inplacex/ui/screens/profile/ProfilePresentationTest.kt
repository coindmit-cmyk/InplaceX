package com.mirkori.inplacex.ui.screens.profile

import com.mirkori.inplacex.platform.mirkori.MirkoriAccountStateKind
import com.mirkori.platform.sdk.PlatformAuthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `connections expose Mirkori sign in before linking and sign out after linking`() {
        assertEquals(
            ProfileConnectionAction.SIGN_IN,
            mirkoriConnectionAction(MirkoriAccountStateKind.GUEST),
        )
        assertNull(mirkoriConnectionAction(MirkoriAccountStateKind.INITIALIZING))
        assertEquals(
            ProfileConnectionAction.SIGN_OUT,
            mirkoriConnectionAction(MirkoriAccountStateKind.LINKED),
        )
    }

    @Test
    fun `linked Google account can disconnect and reconnect device credential`() {
        assertTrue(
            googleConnectionIsActive(
                locallySignedIn = true,
                accountKind = MirkoriAccountStateKind.LINKED,
                authMode = PlatformAuthMode.GOOGLE,
            ),
        )
        assertEquals(
            ProfileConnectionAction.SIGN_OUT,
            googleConnectionAction(
                showGooglePlay = true,
                connected = true,
                accountKind = MirkoriAccountStateKind.LINKED,
                authMode = PlatformAuthMode.GOOGLE,
            ),
        )
        assertEquals(
            ProfileConnectionAction.SIGN_IN,
            googleConnectionAction(
                showGooglePlay = true,
                connected = false,
                accountKind = MirkoriAccountStateKind.LINKED,
                authMode = PlatformAuthMode.GOOGLE,
            ),
        )
    }

    @Test
    fun `linked non Google provider can start verified Google connection`() {
        assertFalse(
            googleConnectionIsActive(
                locallySignedIn = true,
                accountKind = MirkoriAccountStateKind.LINKED,
                authMode = PlatformAuthMode.TELEGRAM,
            ),
        )
        assertEquals(
            ProfileConnectionAction.SIGN_IN,
            googleConnectionAction(
                showGooglePlay = true,
                connected = false,
                accountKind = MirkoriAccountStateKind.LINKED,
                authMode = PlatformAuthMode.TELEGRAM,
            ),
        )
    }
}
