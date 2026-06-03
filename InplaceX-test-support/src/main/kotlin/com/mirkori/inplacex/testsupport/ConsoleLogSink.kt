package com.mirkori.inplacex.testsupport

import com.mirkori.inplacex.logging.LogEvent
import com.mirkori.inplacex.logging.LogSink

class ConsoleLogSink(
    private val writeLine: (String) -> Unit = { message ->
        kotlin.io.print(message + System.lineSeparator())
    },
) : LogSink {
    override fun emit(event: LogEvent) {
        writeLine(event.render())
    }
}

private fun LogEvent.render(): String {
    val attributesBlock = attributes
        .toSortedMap()
        .entries
        .joinToString(separator = ", ") { (key, value) -> "$key=$value" }
        .takeIf(String::isNotEmpty)
        ?.let { " | $it" }
        .orEmpty()
    val errorBlock = errorClass?.let { " | errorClass=$it" }.orEmpty()
    return "[${level.name}] [${tag}] $message$attributesBlock$errorBlock"
}
