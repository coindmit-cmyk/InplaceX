package com.mirkori.inplacex.backend.session.security

import java.text.Normalizer
import java.util.ArrayDeque
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object PublicJsonSecurityPolicy {
    private val allowedSensitiveFieldNames = setOf("secretsubmitted")
    private val forbiddenFieldFragments = setOf(
        "authorization",
        "cookie",
        "credential",
        "fingerprint",
        "hash",
        "hmac",
        "pepper",
        "cipher",
        "seed",
        "keymaterial",
        "privatekey",
        "password",
        "accesstoken",
        "refreshtoken",
        "idtoken",
        "token",
        "jwt",
        "purchase",
        "integrity",
        "providerpayload",
        "providerid",
        "guess",
        "rawpayload",
        "rawrequest",
        "rawbody",
        "requestbody",
        "installationid",
        "deviceidentifier",
        "email",
        "secret",
    )
    private val allowedSensitiveValues = setOf(
        "duel.secretStatusChanged",
        "duel.secretAccepted",
        "SETUP_SECRET_A",
        "SETUP_SECRET_B",
    )
    private val forbiddenValueFragments = setOf(
        "authorization",
        "bearer",
        "cookie",
        "credential",
        "fingerprint",
        "hash",
        "hmac",
        "pepper",
        "cipher",
        "seed",
        "keymaterial",
        "privatekey",
        "password",
        "accesstoken",
        "refreshtoken",
        "idtoken",
        "token",
        "jwt",
        "purchase",
        "integrity",
        "providerpayload",
        "providerid",
        "rawpayload",
        "rawrequest",
        "rawbody",
        "requestbody",
        "installationid",
        "deviceidentifier",
        "email",
        "secret",
        "guess",
    )
    private val jwtShape = Regex("""^[A-Za-z0-9_-]{2,}\.[A-Za-z0-9_-]{2,}\.[A-Za-z0-9_-]{2,}$""")
    private val hexDigestShape = Regex("""^[A-Fa-f0-9]{32,128}$""")
    private val longTokenShape = Regex("""^[A-Za-z0-9_+/=-]{48,}$""")

    fun requireSafe(element: JsonElement) {
        val pending = ArrayDeque<JsonElement>()
        pending.addLast(element)
        while (pending.isNotEmpty()) {
            when (val current = pending.removeLast()) {
                is JsonObject -> current.forEach { (key, value) ->
                    requireSafeFieldName(key)
                    pending.addLast(value)
                }

                is JsonArray -> current.forEach(pending::addLast)
                is JsonPrimitive -> if (current.isString) requireSafeStringValue(current.content)
            }
        }
    }

    private fun requireSafeFieldName(value: String) {
        val normalized = normalize(value)
        if (normalized in allowedSensitiveFieldNames) return
        require(forbiddenFieldFragments.none(normalized::contains)) {
            "Public session JSON contains a forbidden field name"
        }
    }

    private fun requireSafeStringValue(value: String) {
        if (value in allowedSensitiveValues) return
        val normalized = normalize(value)
        require(forbiddenValueFragments.none(normalized::contains)) {
            "Public session JSON contains a forbidden string value"
        }
        require(!isNumericSecretShape(value)) {
            "Public session JSON contains a numeric-secret-shaped value"
        }
        val trimmed = value.trim()
        require(!jwtShape.matches(trimmed) && !hexDigestShape.matches(trimmed)) {
            "Public session JSON contains a token-or-digest-shaped value"
        }
        require(!longTokenShape.matches(trimmed)) {
            "Public session JSON contains a token-shaped value"
        }
        require(!looksLikeEmailOrNestedPayload(trimmed)) {
            "Public session JSON contains a private-or-opaque payload value"
        }
    }

    private fun isNumericSecretShape(value: String): Boolean {
        val compact = value.trim().filterNot { it.isWhitespace() || it == '-' }
        return compact.length in 4..20 && compact.all { it in '0'..'9' }
    }

    private fun looksLikeEmailOrNestedPayload(value: String): Boolean =
        ('@' in value && '.' in value.substringAfter('@')) ||
            value.startsWith('{') ||
            value.startsWith('[')

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase()
            .filter(Char::isLetterOrDigit)
}
