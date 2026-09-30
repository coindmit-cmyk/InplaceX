package com.mirkori.inplacex.backend.session.security

import com.mirkori.inplacex.backend.session.contract.PublicParticipantId
import com.mirkori.inplacex.backend.session.contract.PublicSessionId
import com.mirkori.inplacex.logging.SensitiveKeyLogSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeSessionLogAttributesTest {
    @Test
    fun `safe attributes contain only bounded pseudonyms and typed metadata`() {
        val factory = SafeSessionLogAttributeFactory(ByteArray(32) { index -> index.toByte() })
        val safe = factory.sessionRead(
            operation = SessionLogOperation.READ_SNAPSHOT,
            outcome = SessionLogOutcome.ACCEPTED,
            sessionId = PublicSessionId.parse(sessionIdText),
            participantId = PublicParticipantId.parse(participantIdText),
        )
        val attributes = safe.asMap()

        assertEquals(
            setOf("operation", "outcome", "sessionRef", "participantRef"),
            attributes.keys,
        )
        assertTrue(attributes.getValue("sessionRef").matches(Regex("""s_[A-Za-z0-9_-]{16}""")))
        assertTrue(
            attributes.getValue("participantRef").matches(Regex("""p_[A-Za-z0-9_-]{16}""")),
        )
        attributes.values.forEach { value ->
            assertFalse(value.contains(sessionIdText))
            assertFalse(value.contains(participantIdText))
            assertFalse(value.contains(actorIdText))
            assertFalse(value.contains(commandIdText))
            assertFalse(value.contains("Bearer"))
            assertFalse(value.matches(Regex("""^\d{4,20}$""")))
            assertFalse(value.count { it == '.' } >= 2)
        }
        assertFalse(safe.toString().contains(attributes.getValue("sessionRef")))
        assertFalse(factory.toString().contains("0, 1, 2"))
    }

    @Test
    fun `safe log API has no raw actor command token or string parameter`() {
        val unsafeTypes = setOf(
            CharArray::class.java,
            java.util.UUID::class.java,
        )
        val publicMethods = SafeSessionLogAttributeFactory::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .filterNot { it.isSynthetic }

        assertTrue(publicMethods.isNotEmpty())
        assertTrue(
            publicMethods.all { method ->
                method.parameterTypes.none(unsafeTypes::contains)
            },
        )
        assertFalse(publicMethods.any { it.name.contains("actor", ignoreCase = true) })
        assertFalse(publicMethods.any { it.name.contains("command", ignoreCase = true) })
        assertFalse(publicMethods.any { it.name.contains("token", ignoreCase = true) })
    }

    @Test
    fun `shared logger sanitizer preserves already safe attributes`() {
        val attributes = SafeSessionLogAttributeFactory(ByteArray(32) { index ->
            (index xor 8).toByte()
        }).sessionRead(
            SessionLogOperation.REPLAY_EVENT,
            SessionLogOutcome.REJECTED,
            PublicSessionId.parse(sessionIdText),
            PublicParticipantId.parse(participantIdText),
        ).asMap()

        assertEquals(attributes, SensitiveKeyLogSanitizer().sanitizeAttributes(attributes))
    }

    private companion object {
        const val sessionIdText: String = "10000000-0000-4000-8000-000000001234"
        const val participantIdText: String = "20000000-0000-4000-8000-000000001234"
        const val actorIdText: String = "40000000-0000-4000-8000-000000001234"
        const val commandIdText: String = "50000000-0000-4000-8000-000000001234"
    }
}
