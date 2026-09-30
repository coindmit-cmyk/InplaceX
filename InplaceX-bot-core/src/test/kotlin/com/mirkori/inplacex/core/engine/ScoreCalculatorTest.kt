package com.mirkori.inplacex.core.engine

import java.io.DataInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreCalculatorTest {

    @Test
    fun mutablePathMatchesStringScoringWithoutChangingInputs() {
        val cases = listOf(
            "1234" to "1234",
            "1234" to "1030",
            "1234" to "4321",
            "1122" to "1221",
        )

        cases.forEach { (secret, guess) ->
            val mutableSecret = secret.toCharArray()
            val mutableGuess = guess.toCharArray()

            assertEquals(
                ScoreCalculator.countExactMatches(secret, guess),
                ScoreCalculator.countExactMatches(mutableSecret, mutableGuess),
            )
            assertTrue(mutableSecret.contentEquals(secret.toCharArray()))
            assertTrue(mutableGuess.contentEquals(guess.toCharArray()))
        }
    }

    @Test
    fun bothPathsRejectDifferentLengths() {
        assertThrows(IllegalArgumentException::class.java) {
            ScoreCalculator.countExactMatches("1234", "123")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScoreCalculator.countExactMatches(
                charArrayOf('1', '2', '3', '4'),
                charArrayOf('1', '2', '3'),
            )
        }
    }

    @Test
    fun engineBytecodeContainsNoMutableDigitToStringConversion() {
        listOf(GuessValidator::class.java, ScoreCalculator::class.java).forEach { type ->
            val utf8Entries = classUtf8Entries(type)
            assertFalse("${type.name} references concatToString", "concatToString" in utf8Entries)
            assertFalse("${type.name} references String(char[])", "([C)V" in utf8Entries)
            assertFalse("${type.name} references String(char[], int, int)", "([CII)V" in utf8Entries)
        }
    }

    private fun classUtf8Entries(type: Class<*>): Set<String> {
        val resourceName = "/${type.name.replace('.', '/')}.class"
        val stream = requireNotNull(type.getResourceAsStream(resourceName))
        return DataInputStream(stream.buffered()).use { input ->
            assertEquals(0xCAFEBABE.toInt(), input.readInt())
            input.readUnsignedShort()
            input.readUnsignedShort()
            val entries = linkedSetOf<String>()
            val constantPoolCount = input.readUnsignedShort()
            var index = 1
            while (index < constantPoolCount) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> entries += input.readUTF()
                    3, 4 -> input.readInt()
                    5, 6 -> {
                        input.readLong()
                        index += 1
                    }
                    7, 8, 16, 19, 20 -> input.readUnsignedShort()
                    9, 10, 11, 12, 17, 18 -> {
                        input.readUnsignedShort()
                        input.readUnsignedShort()
                    }
                    15 -> {
                        input.readUnsignedByte()
                        input.readUnsignedShort()
                    }
                    else -> throw AssertionError("Unsupported class constant tag $tag")
                }
                index += 1
            }
            entries
        }
    }
}
