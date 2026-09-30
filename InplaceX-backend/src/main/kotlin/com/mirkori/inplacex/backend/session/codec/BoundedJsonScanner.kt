package com.mirkori.inplacex.backend.session.codec

import java.util.ArrayDeque
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

const val MAX_PUBLIC_JSON_DEPTH: Int = 64

/**
 * Проверяет глубину, duplicate keys и лексическую JSON-грамматику до рекурсивного parser.
 */
internal class BoundedJsonScanner(
    private val keyParser: Json,
) {
    private val stack = ArrayDeque<Container>()
    private lateinit var source: String
    private var index: Int = 0
    private var rootStarted: Boolean = false

    fun requireSafeStructure(json: String) {
        source = json
        index = 0
        rootStarted = false
        stack.clear()

        try {
            scan()
        } catch (_: DuplicateKey) {
            throw IllegalArgumentException("Public session JSON contains a duplicate field")
        } catch (_: DepthExceeded) {
            throw IllegalArgumentException(
                "Public session JSON exceeds the maximum depth of $MAX_PUBLIC_JSON_DEPTH",
            )
        } catch (_: InvalidStructure) {
            throw IllegalArgumentException("Invalid public session JSON frame")
        }
    }

    private fun scan() {
        while (true) {
            skipWhitespace()
            val container = stack.peekLast()
            if (container == null) {
                if (rootStarted) {
                    if (index != source.length) invalid()
                    return
                }
                if (index == source.length) invalid()
                rootStarted = true
                consumeValue()
                continue
            }

            when (container) {
                is ObjectContainer -> scanObject(container)
                is ArrayContainer -> scanArray(container)
            }
        }
    }

    private fun scanObject(container: ObjectContainer) {
        when (container.state) {
            ObjectState.KEY_OR_END -> {
                if (consume('}')) {
                    stack.removeLast()
                } else {
                    consumeObjectKey(container)
                }
            }

            ObjectState.KEY -> consumeObjectKey(container)
            ObjectState.COLON -> {
                if (!consume(':')) invalid()
                container.state = ObjectState.VALUE
            }

            ObjectState.VALUE -> {
                container.state = ObjectState.COMMA_OR_END
                consumeValue()
            }

            ObjectState.COMMA_OR_END -> when {
                consume('}') -> stack.removeLast()
                consume(',') -> container.state = ObjectState.KEY
                else -> invalid()
            }
        }
    }

    private fun consumeObjectKey(container: ObjectContainer) {
        val rawKey = parseStringLiteral()
        val decodedKey = try {
            (keyParser.parseToJsonElement(rawKey) as JsonPrimitive).content
        } catch (_: Exception) {
            invalid()
        }
        if (!container.keys.add(decodedKey)) throw DuplicateKey()
        container.state = ObjectState.COLON
    }

    private fun scanArray(container: ArrayContainer) {
        when (container.state) {
            ArrayState.VALUE_OR_END -> {
                if (consume(']')) {
                    stack.removeLast()
                } else {
                    container.state = ArrayState.COMMA_OR_END
                    consumeValue()
                }
            }

            ArrayState.VALUE -> {
                container.state = ArrayState.COMMA_OR_END
                consumeValue()
            }

            ArrayState.COMMA_OR_END -> when {
                consume(']') -> stack.removeLast()
                consume(',') -> container.state = ArrayState.VALUE
                else -> invalid()
            }
        }
    }

    private fun consumeValue() {
        skipWhitespace()
        when (source.getOrNull(index)) {
            '{' -> {
                index++
                push(ObjectContainer())
            }

            '[' -> {
                index++
                push(ArrayContainer())
            }

            '"' -> parseStringLiteral()
            null -> invalid()
            else -> parsePrimitive()
        }
    }

    private fun push(container: Container) {
        if (stack.size >= MAX_PUBLIC_JSON_DEPTH) throw DepthExceeded()
        stack.addLast(container)
    }

    private fun parseStringLiteral(): String {
        val start = index
        if (!consume('"')) invalid()
        while (index < source.length) {
            when (source[index++]) {
                '"' -> return source.substring(start, index)
                '\\' -> {
                    if (index >= source.length) invalid()
                    if (source[index++] == 'u') {
                        if (index + UNICODE_ESCAPE_DIGITS > source.length) invalid()
                        index += UNICODE_ESCAPE_DIGITS
                    }
                }
            }
        }
        invalid()
    }

    private fun parsePrimitive() {
        when (source.getOrNull(index)) {
            't' -> parseLiteral("true")
            'f' -> parseLiteral("false")
            'n' -> parseLiteral("null")
            '-', in '0'..'9' -> parseNumber()
            else -> invalid()
        }
    }

    private fun parseLiteral(literal: String) {
        if (!source.startsWith(literal, index)) invalid()
        index += literal.length
        if (!atPrimitiveEnd()) invalid()
    }

    private fun parseNumber() {
        consume('-')
        when (source.getOrNull(index)) {
            '0' -> index++
            in '1'..'9' -> consumeDigits()
            else -> invalid()
        }

        if (consume('.')) {
            if (source.getOrNull(index) !in '0'..'9') invalid()
            consumeDigits()
        }

        if (source.getOrNull(index) == 'e' || source.getOrNull(index) == 'E') {
            index++
            if (source.getOrNull(index) == '+' || source.getOrNull(index) == '-') index++
            if (source.getOrNull(index) !in '0'..'9') invalid()
            consumeDigits()
        }

        if (!atPrimitiveEnd()) invalid()
    }

    private fun consumeDigits() {
        while (source.getOrNull(index) in '0'..'9') index++
    }

    private fun atPrimitiveEnd(): Boolean =
        index == source.length || source[index] in PRIMITIVE_DELIMITERS

    private fun skipWhitespace() {
        while (source.getOrNull(index) in JSON_WHITESPACE) index++
    }

    private fun consume(expected: Char): Boolean =
        if (source.getOrNull(index) == expected) {
            index++
            true
        } else {
            false
        }

    private fun invalid(): Nothing = throw InvalidStructure()

    private sealed interface Container

    private class ObjectContainer(
        val keys: MutableSet<String> = mutableSetOf(),
        var state: ObjectState = ObjectState.KEY_OR_END,
    ) : Container

    private class ArrayContainer(
        var state: ArrayState = ArrayState.VALUE_OR_END,
    ) : Container

    private enum class ObjectState {
        KEY_OR_END,
        KEY,
        COLON,
        VALUE,
        COMMA_OR_END,
    }

    private enum class ArrayState {
        VALUE_OR_END,
        VALUE,
        COMMA_OR_END,
    }

    private class DuplicateKey : RuntimeException()
    private class DepthExceeded : RuntimeException()
    private class InvalidStructure : RuntimeException()

    private companion object {
        const val UNICODE_ESCAPE_DIGITS: Int = 4
        val JSON_WHITESPACE: Set<Char> = setOf(' ', '\t', '\r', '\n')
        val PRIMITIVE_DELIMITERS: Set<Char> = JSON_WHITESPACE + setOf(',', ']', '}')
    }
}
