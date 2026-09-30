package com.mirkori.inplacex.backend.session.codec

import com.mirkori.inplacex.backend.session.security.PublicJsonSecurityPolicy
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

const val MAX_PUBLIC_SESSION_FRAME_BYTES: Int = 64 * 1024

internal object CanonicalPublicJson {
    private val parser = Json {
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    fun parseFrame(json: String): JsonElement {
        requireFrameSize(json)
        BoundedJsonScanner(parser).requireSafeStructure(json)
        val parsed = try {
            parser.parseToJsonElement(json)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid public session JSON frame")
        }
        PublicJsonSecurityPolicy.requireSafe(parsed)
        return parsed
    }

    fun encodeFrame(element: JsonElement): String {
        PublicJsonSecurityPolicy.requireSafe(element)
        return canonicalize(element).toString().also(::requireFrameSize)
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonicalize(it.value) })
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }

    private fun requireFrameSize(json: String) {
        val encoded = json.toByteArray(StandardCharsets.UTF_8)
        try {
            require(encoded.size <= MAX_PUBLIC_SESSION_FRAME_BYTES) {
                "Public session JSON frame exceeds 64 KiB"
            }
        } finally {
            encoded.fill(0)
        }
    }
}

internal fun publicJsonObject(vararg entries: Pair<String, JsonElement?>): JsonObject = JsonObject(
    entries.mapNotNull { (key, value) -> value?.let { key to it } }.toMap(),
)

internal fun JsonElement.requireObject(): JsonObject =
    this as? JsonObject
        ?: throw IllegalArgumentException("Public session JSON value must be an object")

internal fun JsonElement.requireArray(): JsonArray =
    this as? JsonArray
        ?: throw IllegalArgumentException("Public session JSON value must be an array")

internal fun JsonObject.requireExactFields(required: Set<String>, optional: Set<String> = emptySet()) {
    require(required.all(::containsKey)) { "Public session JSON is missing a required field" }
    require(keys.all { it in required || it in optional }) {
        "Public session JSON contains an unknown field"
    }
}

internal fun JsonObject.requiredString(name: String): String {
    val value = getValue(name) as? JsonPrimitive
        ?: throw IllegalArgumentException("Public session JSON field must be a string")
    return value.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("Public session JSON field must be a string")
}

internal fun JsonObject.optionalString(name: String): String? =
    if (name !in this) null else requiredString(name)

internal fun JsonObject.requiredLong(name: String): Long {
    val value = getValue(name) as? JsonPrimitive
        ?: throw IllegalArgumentException("Public session JSON field must be an integer")
    return value.takeUnless { it.isString }?.longOrNull
        ?: throw IllegalArgumentException("Public session JSON field must be an integer")
}

internal fun JsonObject.requiredInt(name: String): Int {
    val value = getValue(name) as? JsonPrimitive
        ?: throw IllegalArgumentException("Public session JSON field must be an integer")
    return value.takeUnless { it.isString }?.intOrNull
        ?: throw IllegalArgumentException("Public session JSON field must be an integer")
}

internal fun JsonObject.requiredBoolean(name: String): Boolean {
    val value = getValue(name) as? JsonPrimitive
        ?: throw IllegalArgumentException("Public session JSON field must be a boolean")
    return value.takeUnless { it.isString }?.booleanOrNull
        ?: throw IllegalArgumentException("Public session JSON field must be a boolean")
}

internal inline fun <reified T : Enum<T>> enumValue(value: String): T =
    enumValues<T>().firstOrNull { it.name == value }
        ?: throw IllegalArgumentException("Public session JSON contains an unknown enum value")
