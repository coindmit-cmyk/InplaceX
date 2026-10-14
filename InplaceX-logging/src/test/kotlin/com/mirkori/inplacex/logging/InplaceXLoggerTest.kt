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

    @Test
    fun `logger redacts email attribute keys and preserves their keys`() {
        val sink = RecordingLogSink()
        val logger = InplaceXLogger(
            sink = sink,
            minLevel = LogLevel.DEBUG,
            clockMillis = { 250L },
        )
        val addresses = listOf("player@example.invalid", "friend@example.invalid", "account@example.invalid")

        logger.info(
            tag = "Profile",
            message = "profile updated",
            attributes = mapOf(
                "email" to addresses[0],
                "Email" to addresses[1],
                "accountEmail" to addresses[2],
            ),
        )

        val event = sink.events.single()
        assertEquals(setOf("email", "Email", "accountEmail"), event.attributes.keys)
        assertEquals("[redacted]", event.attributes["email"])
        assertEquals("[redacted]", event.attributes["Email"])
        assertEquals("[redacted]", event.attributes["accountEmail"])
        assertTrue(addresses.none(event.toString()::contains))
    }

    @Test
    fun `logger redacts email while preserving public provider attributes`() {
        val sink = RecordingLogSink()
        val logger = InplaceXLogger(
            sink = sink,
            minLevel = LogLevel.DEBUG,
            clockMillis = { 275L },
        )

        logger.info(
            tag = "Provider",
            message = "provider request denied",
            attributes = mapOf(
                "email" to "player@example.invalid",
                "providerName" to "EMAIL",
                "environment" to "sandbox",
                "result" to "denied",
            ),
        )

        val event = sink.events.single()
        assertEquals("Provider", event.tag)
        assertEquals("provider request denied", event.message)
        assertEquals("[redacted]", event.attributes["email"])
        assertEquals("EMAIL", event.attributes["providerName"])
        assertEquals("sandbox", event.attributes["environment"])
        assertEquals("denied", event.attributes["result"])
    }

    @Test
    fun `logger redacts verification code attributes and preserves their keys`() {
        val sink = RecordingLogSink()
        val logger = InplaceXLogger(
            sink = sink,
            minLevel = LogLevel.DEBUG,
            clockMillis = { 325L },
        )

        logger.info(
            tag = "Verification",
            message = "verification requested",
            attributes = mapOf(
                "verificationCode" to "654321",
                "VerificationCode" to "234567",
            ),
        )

        val event = sink.events.single()
        assertEquals(setOf("verificationCode", "VerificationCode"), event.attributes.keys)
        assertEquals("[redacted]", event.attributes["verificationCode"])
        assertEquals("[redacted]", event.attributes["VerificationCode"])
        assertTrue(event.toString().contains("654321").not())
        assertTrue(event.toString().contains("234567").not())
    }

    @Test
    fun `logger redacts verification code while preserving public attributes`() {
        val sink = RecordingLogSink()
        val logger = InplaceXLogger(
            sink = sink,
            minLevel = LogLevel.DEBUG,
            clockMillis = { 350L },
        )

        logger.info(
            tag = "Verification",
            message = "verification result",
            attributes = mapOf(
                "verificationCode" to "765432",
                "statusCode" to "403",
                "eventCode" to "AUTH_VERIFY",
                "providerName" to "EMAIL",
            ),
        )

        val event = sink.events.single()
        assertEquals("Verification", event.tag)
        assertEquals("verification result", event.message)
        assertEquals("[redacted]", event.attributes["verificationCode"])
        assertEquals("403", event.attributes["statusCode"])
        assertEquals("AUTH_VERIFY", event.attributes["eventCode"])
        assertEquals("EMAIL", event.attributes["providerName"])
        assertTrue(event.toString().contains("765432").not())
    }

    @Test
    fun `warn excludes nested throwable messages from diagnostics`() {
        val sink = RecordingLogSink()
        val logger = InplaceXLogger(
            sink = sink,
            minLevel = LogLevel.WARN,
            clockMillis = { 500L },
        )

        logger.warn(
            tag = "Auth",
            message = "auth request failed",
            attributes = mapOf("environment" to "sandbox"),
            throwable = IllegalStateException(
                "accessToken=fixture-private-token",
                IllegalArgumentException("password=fixture-private-password"),
            ),
        )

        val event = sink.events.single()
        assertEquals(LogLevel.WARN, event.level)
        assertEquals("Auth", event.tag)
        assertEquals("auth request failed", event.message)
        assertEquals("sandbox", event.attributes["environment"])
        assertEquals(500L, event.timestampMillis)
        assertEquals(IllegalStateException::class.java.name, event.errorClass)
        val diagnostic = event.toString()
        assertTrue(diagnostic.contains("accessToken=fixture-private-token").not())
        assertTrue(diagnostic.contains("fixture-private-token").not())
        assertTrue(diagnostic.contains("password=fixture-private-password").not())
        assertTrue(diagnostic.contains("fixture-private-password").not())
    }
}
