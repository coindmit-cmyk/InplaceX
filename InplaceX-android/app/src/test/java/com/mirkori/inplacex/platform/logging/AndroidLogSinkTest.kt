package com.mirkori.inplacex.platform.logging

import android.util.Log
import com.mirkori.inplacex.logging.LogEvent
import com.mirkori.inplacex.logging.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidLogSinkTest {
    @Test
    fun `android sink maps structured event to android writer`() {
        val writes = mutableListOf<Triple<Int, String, String>>()
        val sink = AndroidLogSink { priority, tag, message ->
            writes += Triple(priority, tag, message)
        }

        sink.emit(
            LogEvent(
                level = LogLevel.ERROR,
                tag = "GameFieldScreen",
                message = "match finished",
                attributes = mapOf("won" to "false", "attempts" to "12"),
                errorClass = "java.lang.IllegalStateException",
                timestampMillis = 42L,
            ),
        )

        assertEquals(
            Triple(
                Log.ERROR,
                "GameFieldScreen",
                "match finished | attempts=12, won=false | errorClass=java.lang.IllegalStateException",
            ),
            writes.single(),
        )
    }
}
