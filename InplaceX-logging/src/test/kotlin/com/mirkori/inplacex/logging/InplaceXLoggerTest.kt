package com.mirkori.inplacex.logging

import com.mirkori.inplacex.testsupport.RecordingLogSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InplaceXLoggerTest {
    @Test
    fun `logger filters events below minimum level`() {
        val sink = RecordingLogSink()
        val logger = InplaceXLogger(
            sink = sink,
            minLevel = LogLevel.WARN,
            clockMillis = { 100L },
        )

        logger.debug(tag = "Test", message = "debug")
        logger.info(tag = "Test", message = "info")
        logger.warn(tag = "Test", message = "warn")

        assertEquals(1, sink.events.size)
        assertEquals(LogLevel.WARN, sink.events.single().level)
        assertEquals("warn", sink.events.single().message)
    }

    @Test
    fun `logger redacts sensitive attribute keys`() {
        val sink = RecordingLogSink()
        val logger = InplaceXLogger(
            sink = sink,
            minLevel = LogLevel.DEBUG,
            clockMillis = { 200L },
        )

        logger.info(
            tag = "Provider",
            message = "provider configured",
            attributes = mapOf(
                "environment" to "sandbox",
                "accessToken" to "abc123",
                "apiKey" to "real-key",
                "cookie" to "session=secret",
                "guess" to "4060",
                "providerPayload" to "provider-private",
                "purchaseToken" to "purchase-private",
            ),
        )

        val event = sink.events.single()
        assertEquals("sandbox", event.attributes["environment"])
        assertEquals(SensitiveKeyLogSanitizer.RedactedValue, event.attributes["accessToken"])
        assertEquals(SensitiveKeyLogSanitizer.RedactedValue, event.attributes["apiKey"])
        assertEquals(SensitiveKeyLogSanitizer.RedactedValue, event.attributes["cookie"])
        assertEquals(SensitiveKeyLogSanitizer.RedactedValue, event.attributes["guess"])
        assertEquals(SensitiveKeyLogSanitizer.RedactedValue, event.attributes["providerPayload"])
        assertEquals(SensitiveKeyLogSanitizer.RedactedValue, event.attributes["purchaseToken"])
    }

    @Test
    fun `logger redacts provider id attribute keys`() {
        val sink = RecordingLogSink()
        val logger = InplaceXLogger(
            sink = sink,
            minLevel = LogLevel.DEBUG,
            clockMillis = { 250L },
        )

        logger.info(
            tag = "Provider",
            message = "provider configured",
            attributes = mapOf(
                "providerId" to "synthetic-provider-id-one",
                "ProviderId" to "synthetic-provider-id-two",
            ),
        )

        val event = sink.events.single()
        assertEquals(SensitiveKeyLogSanitizer.RedactedValue, event.attributes["providerId"])
        assertEquals(SensitiveKeyLogSanitizer.RedactedValue, event.attributes["ProviderId"])
        assertTrue(event.toString().contains("synthetic-provider-id-one").not())
        assertTrue(event.toString().contains("synthetic-provider-id-two").not())
    }

    @Test
    fun `logger preserves public provider and environment attributes`() {
        val sink = RecordingLogSink()
        val logger = InplaceXLogger(
            sink = sink,
            minLevel = LogLevel.DEBUG,
            clockMillis = { 275L },
        )

        logger.info(
            tag = "Provider",
            message = "provider configured",
            attributes = mapOf(
                "providerId" to "synthetic-provider-id-three",
                "providerName" to "Example Provider",
                "environment" to "sandbox",
            ),
        )

        val event = sink.events.single()
        assertEquals(SensitiveKeyLogSanitizer.RedactedValue, event.attributes["providerId"])
        assertEquals("Example Provider", event.attributes["providerName"])
        assertEquals("sandbox", event.attributes["environment"])
    }

    @Test
    fun `logger records throwable class without throwable message`() {
        val sink = RecordingLogSink()
        val logger = InplaceXLogger(
            sink = sink,
            minLevel = LogLevel.DEBUG,
            clockMillis = { 300L },
        )

        logger.error(
            tag = "Auth",
            message = "auth failed",
            throwable = IllegalStateException("password=123"),
        )

        val event = sink.events.single()
        assertEquals(IllegalStateException::class.java.name, event.errorClass)
        assertTrue(event.toString().contains("password=123").not())
    }

    @Test
    fun `logger uses injected clock`() {
        val sink = RecordingLogSink()
        val logger = InplaceXLogger(
            sink = sink,
            minLevel = LogLevel.DEBUG,
            clockMillis = { 400L },
        )

        logger.info(tag = "Clock", message = "time")

        assertEquals(400L, sink.events.single().timestampMillis)
    }
}
