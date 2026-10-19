package com.mirkori.inplacex.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderIdentityPrivacyTest {
    @Test
    fun googleSubjectIsPreservedButRedactedInDiagnostics() {
        val subject = "12345678901234567890"
        val identity = ProviderIdentity(AuthProvider.GOOGLE, subject)

        assertEquals(AuthProvider.GOOGLE, identity.provider)
        assertEquals(subject, identity.subject)

        val diagnostics = identity.toString()
        assertTrue(diagnostics.contains("[redacted]"))
        assertFalse(diagnostics.contains(subject))
        assertTrue(diagnostics.contains("google"))
    }

    @Test
    fun emailAndTelegramSubjectsArePreservedButRedactedInDiagnostics() {
        val emailSubject = "email_fixture_subject_123456789"
        val emailIdentity = ProviderIdentity(AuthProvider.EMAIL, emailSubject)

        assertEquals(AuthProvider.EMAIL, emailIdentity.provider)
        assertEquals(emailSubject, emailIdentity.subject)
        val emailDiagnostics = emailIdentity.toString()
        assertTrue(emailDiagnostics.contains("[redacted]"))
        assertFalse(emailDiagnostics.contains(emailSubject))
        assertTrue(emailDiagnostics.contains("email"))

        val telegramSubject = "telegram_fixture_subject_123456789"
        val telegramIdentity = ProviderIdentity(AuthProvider.TELEGRAM, telegramSubject)

        assertEquals(AuthProvider.TELEGRAM, telegramIdentity.provider)
        assertEquals(telegramSubject, telegramIdentity.subject)
        val telegramDiagnostics = telegramIdentity.toString()
        assertTrue(telegramDiagnostics.contains("[redacted]"))
        assertFalse(telegramDiagnostics.contains(telegramSubject))
        assertTrue(telegramDiagnostics.contains("telegram"))
    }

    @Test
    fun verifiedTelegramLoginPreservesDataButRedactsDiagnostics() {
        val login = VerifiedTelegramLogin(
            externalUserId = "424242424242",
            displayName = "Fixture Player",
            authenticatedAt = java.time.Instant.parse("2026-10-14T00:00:00Z"),
        )

        assertEquals("424242424242", login.externalUserId)
        assertEquals("Fixture Player", login.displayName)
        assertEquals(java.time.Instant.parse("2026-10-14T00:00:00Z"), login.authenticatedAt)

        val diagnostics = login.toString()
        assertTrue(diagnostics.contains("[redacted]"))
        assertTrue(diagnostics.contains("VerifiedTelegramLogin"))
        assertFalse(diagnostics.contains("424242424242"))
        assertFalse(diagnostics.contains("Fixture Player"))
    }
}
