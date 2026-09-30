package com.mirkori.inplacex.testsupport

import com.mirkori.inplacex.logging.LogEvent
import com.mirkori.inplacex.logging.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class ConsoleLogSinkTest {
    @Test
    fun `console sink renders structured log line`() {
        val lines = mutableListOf<String>()
        val sink = ConsoleLogSink(writeLine = lines::add)

        sink.emit(
            LogEvent(
                level = LogLevel.WARN,
                tag = "BotBenchmark",
                message = "report written",
                attributes = mapOf("path" to "build/reports/test.txt"),
                errorClass = "java.lang.IllegalStateException",
                timestampMillis = 123L,
            ),
        )

        assertEquals(
            "[WARN] [BotBenchmark] report written | path=build/reports/test.txt | errorClass=java.lang.IllegalStateException",
            lines.single(),
        )
    }
}
