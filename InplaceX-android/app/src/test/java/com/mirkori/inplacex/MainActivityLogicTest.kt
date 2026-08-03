package com.mirkori.inplacex

import com.mirkori.inplacex.ui.navigation.AppSection
import org.junit.Assert.assertEquals
import org.junit.Test

class MainActivityLogicTest {
    @Test
    fun activeOnlineSessionRestoresSocialSection() {
        assertEquals(
            AppSection.SOCIAL,
            initialSectionForActiveOnlineSession("00000000-0000-0000-0000-000000000001"),
        )
    }

    @Test
    fun absentOnlineSessionKeepsHomeSection() {
        assertEquals(AppSection.HOME, initialSectionForActiveOnlineSession(null))
    }
}
