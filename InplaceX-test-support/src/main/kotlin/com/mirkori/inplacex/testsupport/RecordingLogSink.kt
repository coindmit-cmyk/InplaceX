package com.mirkori.inplacex.testsupport

import com.mirkori.inplacex.logging.LogEvent
import com.mirkori.inplacex.logging.LogSink

class RecordingLogSink : LogSink {
    val events = mutableListOf<LogEvent>()

    override fun emit(event: LogEvent) {
        events += event
    }
}
