package com.mirkori.inplacex.backend.session.domain

/**
 * Одноразовая команда с буфером цифр, которым владеет backend.
 *
 * Фабрики немедленно копируют и очищают вход вызывающей стороны. После первой
 * попытки выполнения owned-буфер очищается независимо от результата.
 */
sealed class MutableDuelCommand private constructor(
    private var digits: CharArray?,
) : AutoCloseable {

    internal fun <T> consume(block: (CharArray) -> T): T {
        val claimedDigits = synchronized(this) {
            digits?.also { digits = null }
                ?: throw IllegalStateException("Mutable duel command was already consumed or closed")
        }
        return try {
            block(claimedDigits)
        } finally {
            claimedDigits.fill(CLEARED_DIGIT)
        }
    }

    final override fun close() {
        synchronized(this) {
            digits?.fill(CLEARED_DIGIT)
            digits = null
        }
    }

    final override fun toString(): String = "MutableDuelCommand([redacted])"

    class Secret private constructor(digits: CharArray) : MutableDuelCommand(digits) {
        internal companion object {
            fun take(digits: CharArray): Secret = Secret(copyAndClear(digits))
        }
    }

    class Guess private constructor(digits: CharArray) : MutableDuelCommand(digits) {
        internal companion object {
            fun take(digits: CharArray): Guess = Guess(copyAndClear(digits))
        }
    }

    companion object {
        fun secret(digits: CharArray): Secret = Secret.take(digits)

        fun guess(digits: CharArray): Guess = Guess.take(digits)
    }
}

private const val CLEARED_DIGIT: Char = '\u0000'

private fun copyAndClear(source: CharArray): CharArray {
    return try {
        source.copyOf()
    } finally {
        source.fill(CLEARED_DIGIT)
    }
}
