package com.mirkori.inplacex.logging

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class LogEvent(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val attributes: Map<String, String> = emptyMap(),
    val errorClass: String? = null,
    val timestampMillis: Long,
)

fun interface LogSink {
    fun emit(event: LogEvent)
}

object NoOpLogSink : LogSink {
    override fun emit(event: LogEvent) = Unit
}

interface LogSanitizer {
    fun sanitizeAttributes(attributes: Map<String, String>): Map<String, String>
}

class SensitiveKeyLogSanitizer(
    private val redactedValue: String = RedactedValue,
    private val sensitiveKeyFragments: Set<String> = DefaultSensitiveKeyFragments,
) : LogSanitizer {
    override fun sanitizeAttributes(attributes: Map<String, String>): Map<String, String> =
        attributes.mapValues { (key, value) ->
            if (key.containsSensitiveFragment()) redactedValue else value
        }

    private fun String.containsSensitiveFragment(): Boolean {
        val normalized = lowercase()
        return sensitiveKeyFragments.any(normalized::contains)
    }

    companion object {
        const val RedactedValue = "[redacted]"

        val DefaultSensitiveKeyFragments = setOf(
            "authorization",
            "cookie",
            "credential",
            "guess",
            "integrity",
            "key",
            "password",
            "private",
            "providerpayload",
            "purchase",
            "rawpayload",
            "secret",
            "token",
        )
    }
}

class InplaceXLogger(
    private val sink: LogSink = NoOpLogSink,
    private val minLevel: LogLevel = LogLevel.INFO,
    private val sanitizer: LogSanitizer = SensitiveKeyLogSanitizer(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    fun debug(
        tag: String,
        message: String,
        attributes: Map<String, String> = emptyMap(),
    ) = log(LogLevel.DEBUG, tag, message, attributes)

    fun info(
        tag: String,
        message: String,
        attributes: Map<String, String> = emptyMap(),
    ) = log(LogLevel.INFO, tag, message, attributes)

    fun warn(
        tag: String,
        message: String,
        attributes: Map<String, String> = emptyMap(),
        throwable: Throwable? = null,
    ) = log(LogLevel.WARN, tag, message, attributes, throwable)

    fun error(
        tag: String,
        message: String,
        attributes: Map<String, String> = emptyMap(),
        throwable: Throwable? = null,
    ) = log(LogLevel.ERROR, tag, message, attributes, throwable)

    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        attributes: Map<String, String> = emptyMap(),
        throwable: Throwable? = null,
    ) {
        if (level.ordinal < minLevel.ordinal) return

        sink.emit(
            LogEvent(
                level = level,
                tag = tag,
                message = message,
                attributes = sanitizer.sanitizeAttributes(attributes),
                errorClass = throwable?.javaClass?.name,
                timestampMillis = clockMillis(),
            ),
        )
    }
}
