package com.mirkori.inplacex.platform.logging

import com.mirkori.inplacex.BuildConfig
import com.mirkori.inplacex.logging.InplaceXLogger
import com.mirkori.inplacex.logging.LogLevel

object AppLog {
    private val logger = InplaceXLogger(
        sink = AndroidLogSink(),
        minLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO,
    )

    fun debug(
        tag: String,
        message: String,
        attributes: Map<String, String> = emptyMap(),
    ) = logger.debug(tag, message, attributes)

    fun info(
        tag: String,
        message: String,
        attributes: Map<String, String> = emptyMap(),
    ) = logger.info(tag, message, attributes)

    fun warn(
        tag: String,
        message: String,
        attributes: Map<String, String> = emptyMap(),
        throwable: Throwable? = null,
    ) = logger.warn(tag, message, attributes, throwable)

    fun error(
        tag: String,
        message: String,
        attributes: Map<String, String> = emptyMap(),
        throwable: Throwable? = null,
    ) = logger.error(tag, message, attributes, throwable)
}
