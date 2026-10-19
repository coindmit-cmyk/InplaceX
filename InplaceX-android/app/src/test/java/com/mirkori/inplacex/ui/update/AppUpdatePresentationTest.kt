package com.mirkori.inplacex.ui.update

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdatePresentationTest {
    @Test
    fun `artifact size is rounded up without hiding remaining bytes`() {
        assertEquals("0.1 MB", formatMegabytes(1L))
        assertEquals("1.0 MB", formatMegabytes(1024L * 1024L))
        assertEquals("1.1 MB", formatMegabytes(1024L * 1024L + 1L))
        assertEquals("11.8 MB", formatMegabytes(12_345_678L))
    }
}
