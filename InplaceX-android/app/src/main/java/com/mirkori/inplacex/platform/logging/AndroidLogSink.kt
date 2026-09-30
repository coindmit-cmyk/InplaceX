package com.mirkori.inplacex.platform.logging

import android.util.Log
import com.mirkori.inplacex.logging.LogEvent
import com.mirkori.inplacex.logging.LogLevel
import com.mirkori.inplacex.logging.LogSink

class AndroidLogSink(
    private val writer: (priority: Int, tag: String, message: String) -> Unit = { priority, tag, message ->
        runCatching { Log.println(priority, tag, message) }
    },
) : LogSink {
    override fun emit(event: LogEvent) {
        writer(
            event.level.toAndroidPriority(),
            event.tag,
            event.render(),
        )
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
    return "$message$attributesBlock$errorBlock"
}

private fun LogLevel.toAndroidPriority(): Int = when (this) {
    LogLevel.DEBUG -> Log.DEBUG
    LogLevel.INFO -> Log.INFO
    LogLevel.WARN -> Log.WARN
    LogLevel.ERROR -> Log.ERROR
}
