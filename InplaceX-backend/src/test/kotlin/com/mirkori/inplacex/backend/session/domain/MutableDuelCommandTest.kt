package com.mirkori.inplacex.backend.session.domain

import com.mirkori.inplacex.backend.domain.duel.DuelCommandRejectedException
import com.mirkori.inplacex.backend.domain.duel.DuelCommandRejection
import java.io.DataInputStream
import java.lang.reflect.Modifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MutableDuelCommandTest {

    @Test
    fun factoriesTakeOwnershipAndClearCallerBuffers() {
        val secretInput = digits("1234")
        val guessInput = digits("5678")

        val secret = MutableDuelCommand.secret(secretInput)
        val guess = MutableDuelCommand.guess(guessInput)

        assertCleared(secretInput)
        assertCleared(guessInput)
        assertEquals("MutableDuelCommand([redacted])", secret.toString())
        assertEquals("MutableDuelCommand([redacted])", guess.toString())
    }

    @Test
    fun ownedBuffersAreClearedAfterSuccessAndTypedRejection() {
        val successful = MutableDuelCommand.secret(digits("1234"))
        lateinit var successfulOwned: CharArray
        val result = successful.consume { owned ->
            successfulOwned = owned
            assertArrayEquals(digits("1234"), owned)
            42
        }
        assertEquals(42, result)
        assertCleared(successfulOwned)

        val rejected = MutableDuelCommand.guess(digits("5678"))
        lateinit var rejectedOwned: CharArray
        assertThrows(DuelCommandRejectedException::class.java) {
            rejected.consume { owned ->
                rejectedOwned = owned
                throw DuelCommandRejectedException(DuelCommandRejection.INVALID_GUESS)
            }
        }
        assertCleared(rejectedOwned)
    }

    @Test
    fun ownedBufferIsClearedAfterUnexpectedException() {
        val command = MutableDuelCommand.guess(digits("5678"))
        lateinit var ownedBuffer: CharArray

        assertThrows(IllegalArgumentException::class.java) {
            command.consume { owned ->
                ownedBuffer = owned
                throw IllegalArgumentException("synthetic failure")
            }
        }

        assertCleared(ownedBuffer)
    }

    @Test
    fun consumptionIsSingleUseAndCloseClearsUnconsumedOwnedBuffer() {
        val consumed = MutableDuelCommand.secret(digits("1234"))
        consumed.consume { Unit }
        assertThrows(IllegalStateException::class.java) {
            consumed.consume { Unit }
        }

        val closed = MutableDuelCommand.guess(digits("5678"))
        val closedOwned = ownedDigits(closed)
        closed.close()
        closed.close()
        assertCleared(closedOwned)
        assertThrows(IllegalStateException::class.java) {
            closed.consume { Unit }
        }
    }

    @Test
    fun publicShapeHasNoStringOrMutableBufferGetter() {
        val commandTypes = listOf(
            MutableDuelCommand::class.java,
            MutableDuelCommand.Secret::class.java,
            MutableDuelCommand.Secret.Companion::class.java,
            MutableDuelCommand.Guess::class.java,
            MutableDuelCommand.Guess.Companion::class.java,
            MutableDuelCommand.Companion::class.java,
        )

        commandTypes.forEach { type ->
            type.declaredConstructors.forEach { constructor ->
                assertFalse(constructor.parameterTypes.contains(String::class.java))
            }
            type.declaredMethods
                .filterNot { it.name == "toString" }
                .forEach { method ->
                    assertFalse("${type.name}.${method.name} returns String", method.returnType == String::class.java)
                    assertFalse(
                        "${type.name}.${method.name} returns CharArray",
                        method.returnType == CharArray::class.java,
                    )
                    assertFalse(
                        "${type.name}.${method.name} accepts String",
                        method.parameterTypes.contains(String::class.java),
                    )
                }
            type.fields.forEach { field ->
                assertFalse(
                    Modifier.isPublic(field.modifiers) && field.type == CharArray::class.java,
                )
            }
        }
    }

    @Test
    fun commandBytecodeContainsNoMutableDigitToStringConversion() {
        listOf(
            MutableDuelCommand::class.java,
            MutableDuelCommand.Secret::class.java,
            MutableDuelCommand.Secret.Companion::class.java,
            MutableDuelCommand.Guess::class.java,
            MutableDuelCommand.Guess.Companion::class.java,
            MutableDuelCommand.Companion::class.java,
        ).forEach { type ->
            val utf8Entries = classUtf8Entries(type)
            assertFalse("${type.name} references concatToString", "concatToString" in utf8Entries)
            assertFalse(
                "${type.name} constructs String from mutable digits",
                classMethodReferences(type).any { reference ->
                    reference.owner == "java/lang/String" &&
                        reference.name == "<init>" &&
                        reference.descriptor.startsWith("([C")
                },
            )
        }
    }

    private fun ownedDigits(command: MutableDuelCommand): CharArray {
        val field = MutableDuelCommand::class.java.getDeclaredField("digits")
        field.isAccessible = true
        return requireNotNull(field.get(command) as CharArray?)
    }

    private fun assertCleared(value: CharArray) {
        assertTrue(value.all { it == '\u0000' })
    }

    private fun digits(value: String): CharArray = value.toCharArray()

    private fun classUtf8Entries(type: Class<*>): Set<String> {
        return readConstantPool(type).filterIsInstance<String>().toSet()
    }

    private fun classMethodReferences(type: Class<*>): Set<MethodReference> {
        val pool = readConstantPool(type)
        return pool.filterIsInstance<MethodRefInfo>().mapTo(linkedSetOf()) { method ->
            val owner = pool[method.classIndex] as ClassInfo
            val nameAndType = pool[method.nameAndTypeIndex] as NameAndTypeInfo
            MethodReference(
                owner = pool[owner.nameIndex] as String,
                name = pool[nameAndType.nameIndex] as String,
                descriptor = pool[nameAndType.descriptorIndex] as String,
            )
        }
    }

    private fun readConstantPool(type: Class<*>): Array<Any?> {
        val resourceName = "/${type.name.replace('.', '/')}.class"
        val stream = requireNotNull(type.getResourceAsStream(resourceName))
        return DataInputStream(stream.buffered()).use { input ->
            assertEquals(0xCAFEBABE.toInt(), input.readInt())
            input.readUnsignedShort()
            input.readUnsignedShort()
            val constantPoolCount = input.readUnsignedShort()
            val entries = arrayOfNulls<Any>(constantPoolCount)
            var index = 1
            while (index < constantPoolCount) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> entries[index] = input.readUTF()
                    3, 4 -> input.readInt()
                    5, 6 -> {
                        input.readLong()
                        index += 1
                    }
                    7 -> entries[index] = ClassInfo(input.readUnsignedShort())
                    8, 16, 19, 20 -> input.readUnsignedShort()
                    9, 17, 18 -> {
                        input.readUnsignedShort()
                        input.readUnsignedShort()
                    }
                    10, 11 -> entries[index] = MethodRefInfo(
                        classIndex = input.readUnsignedShort(),
                        nameAndTypeIndex = input.readUnsignedShort(),
                    )
                    12 -> entries[index] = NameAndTypeInfo(
                        nameIndex = input.readUnsignedShort(),
                        descriptorIndex = input.readUnsignedShort(),
                    )
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

    private data class ClassInfo(val nameIndex: Int)

    private data class NameAndTypeInfo(
        val nameIndex: Int,
        val descriptorIndex: Int,
    )

    private data class MethodRefInfo(
        val classIndex: Int,
        val nameAndTypeIndex: Int,
    )

    private data class MethodReference(
        val owner: String,
        val name: String,
        val descriptor: String,
    )
}
