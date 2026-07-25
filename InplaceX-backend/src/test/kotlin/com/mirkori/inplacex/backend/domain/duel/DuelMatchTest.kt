package com.mirkori.inplacex.backend.domain.duel

import com.mirkori.inplacex.backend.session.domain.MutableDuelCommand
import com.mirkori.inplacex.core.engine.GuessValidationReason
import com.mirkori.inplacex.core.model.GameConfig
import java.io.DataInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DuelMatchTest {

    @Test
    fun setupRequiresOrderedSecretsAndStartsWithFirstParticipant() {
        val match = duel()

        assertEquals(DuelParticipant.FIRST, match.snapshot().awaitingSecretFrom)
        assertRejected(DuelCommandRejection.SECRET_NOT_EXPECTED) {
            setSecret(match, DuelParticipant.SECOND, "5678")
        }

        val waitingForSecond = setSecret(match, DuelParticipant.FIRST, "1234")
        assertEquals(DuelPhase.SETUP, waitingForSecond.phase)
        assertEquals(DuelParticipant.SECOND, waitingForSecond.awaitingSecretFrom)

        val active = setSecret(match, DuelParticipant.SECOND, "5678")
        assertEquals(DuelPhase.ACTIVE, active.phase)
        assertEquals(DuelParticipant.FIRST, active.currentTurn)
        assertNull(active.winner)
    }

    @Test
    fun publicSnapshotContainsOnlyViewerNeutralOutcomeData() {
        val match = readyDuel()

        val snapshot = submitGuess(match, DuelParticipant.FIRST, "5670")

        assertTrue(snapshot.participants.all { it.secretConfigured })
        assertEquals(3, snapshot.attempts.single().exactMatches)
        assertFalse(snapshot.toString().contains("1234"))
        assertFalse(snapshot.toString().contains("5678"))
        assertFalse(snapshot.toString().contains("5670"))
        assertTrue(DuelAttempt::class.java.declaredFields.none { it.name.contains("guess", ignoreCase = true) })
        assertTrue(DuelAttempt::class.java.declaredFields.none { it.type == String::class.java })
        assertTrue(DuelAttempt::class.java.declaredFields.none { it.type == CharArray::class.java })
        assertTrue(DuelSnapshot::class.java.declaredFields.none { it.type == String::class.java })
        assertTrue(DuelSnapshot::class.java.declaredFields.none { it.type == CharArray::class.java })
    }

    @Test
    fun guessesScoreAgainstOpponentAndAlternateTurnsUntilWin() {
        val match = readyDuel()

        val firstTurn = submitGuess(match, DuelParticipant.FIRST, "5670")
        assertEquals(3, firstTurn.attempts.single().exactMatches)
        assertEquals(DuelParticipant.SECOND, firstTurn.currentTurn)

        val winningTurn = submitGuess(match, DuelParticipant.SECOND, "1234")
        assertEquals(DuelPhase.FINISHED, winningTurn.phase)
        assertEquals(DuelParticipant.SECOND, winningTurn.winner)
        assertNull(winningTurn.currentTurn)
    }

    @Test
    fun attemptLimitAwardsWinToOpponent() {
        val match = readyDuel(attemptLimit = 1)

        val finished = submitGuess(match, DuelParticipant.FIRST, "5670")

        assertEquals(DuelPhase.FINISHED, finished.phase)
        assertEquals(DuelParticipant.SECOND, finished.winner)
        assertEquals(0, finished.participants.first { it.participant == DuelParticipant.FIRST }.attemptsLeft)
    }

    @Test
    fun rejectedCommandsAreTypedAtomicAndTheirOwnedBuffersAreCleared() {
        val match = duel()

        val invalidSecret = MutableDuelCommand.secret(digits("1111"))
        val invalidSecretOwned = ownedDigits(invalidSecret)
        val beforeInvalidSecret = match.snapshot()
        assertRejected(
            rejection = DuelCommandRejection.INVALID_SECRET,
            validationReason = GuessValidationReason.ALL_SAME_DIGITS,
        ) {
            match.setSecret(DuelParticipant.FIRST, invalidSecret)
        }
        assertCleared(invalidSecretOwned)
        assertEquals(beforeInvalidSecret, match.snapshot())

        val earlyGuess = MutableDuelCommand.guess(digits("5678"))
        val earlyGuessOwned = ownedDigits(earlyGuess)
        val beforeEarlyGuess = match.snapshot()
        assertRejected(DuelCommandRejection.MATCH_NOT_ACTIVE) {
            match.submitGuess(DuelParticipant.FIRST, earlyGuess)
        }
        assertCleared(earlyGuessOwned)
        assertEquals(beforeEarlyGuess, match.snapshot())

        ready(match)
        val activeSecret = MutableDuelCommand.secret(digits("8765"))
        val activeSecretOwned = ownedDigits(activeSecret)
        val beforeActiveSecret = match.snapshot()
        assertRejected(DuelCommandRejection.SECRET_NOT_EXPECTED) {
            match.setSecret(DuelParticipant.SECOND, activeSecret)
        }
        assertCleared(activeSecretOwned)
        assertEquals(beforeActiveSecret, match.snapshot())

        val wrongTurn = MutableDuelCommand.guess(digits("1234"))
        val wrongTurnOwned = ownedDigits(wrongTurn)
        val beforeWrongTurn = match.snapshot()
        assertRejected(DuelCommandRejection.NOT_CURRENT_TURN) {
            match.submitGuess(DuelParticipant.SECOND, wrongTurn)
        }
        assertCleared(wrongTurnOwned)
        assertEquals(beforeWrongTurn, match.snapshot())

        val invalidGuess = MutableDuelCommand.guess(digits("56a8"))
        val invalidGuessOwned = ownedDigits(invalidGuess)
        val beforeInvalidGuess = match.snapshot()
        assertRejected(
            rejection = DuelCommandRejection.INVALID_GUESS,
            validationReason = GuessValidationReason.NON_DIGIT,
        ) {
            match.submitGuess(DuelParticipant.FIRST, invalidGuess)
        }
        assertCleared(invalidGuessOwned)
        assertEquals(beforeInvalidGuess, match.snapshot())
    }

    @Test
    fun nonAsciiDigitsAreRejectedWithoutChangingState() {
        val match = duel()
        val before = match.snapshot()

        assertRejected(
            rejection = DuelCommandRejection.INVALID_SECRET,
            validationReason = GuessValidationReason.NON_DIGIT,
        ) {
            match.setSecret(
                DuelParticipant.FIRST,
                MutableDuelCommand.secret(charArrayOf('1', '2', '\u0663', '4')),
            )
        }

        assertEquals(before, match.snapshot())
    }

    @Test
    fun callerAndOwnedCommandBuffersAreClearedAfterAcceptedCommands() {
        val match = duel()
        val callerSecret = digits("1234")
        val secretCommand = MutableDuelCommand.secret(callerSecret)
        val ownedSecret = ownedDigits(secretCommand)
        assertCleared(callerSecret)

        match.setSecret(DuelParticipant.FIRST, secretCommand)
        assertCleared(ownedSecret)

        setSecret(match, DuelParticipant.SECOND, "5678")
        val callerGuess = digits("5670")
        val guessCommand = MutableDuelCommand.guess(callerGuess)
        val ownedGuess = ownedDigits(guessCommand)
        assertCleared(callerGuess)

        match.submitGuess(DuelParticipant.FIRST, guessCommand)
        assertCleared(ownedGuess)
    }

    @Test
    fun finishingMatchClearsRetainedSecretsButKeepsReadinessSnapshot() {
        val match = readyDuel()
        val retainedSecrets = retainedSecrets(match).values.toList()

        val finished = submitGuess(match, DuelParticipant.FIRST, "5678")

        assertEquals(DuelPhase.FINISHED, finished.phase)
        assertTrue(finished.participants.all { it.secretConfigured })
        retainedSecrets.forEach(::assertCleared)
        assertTrue(retainedSecrets(match).isEmpty())
    }

    @Test
    fun closeIsIdempotentAndClearsRetainedSecrets() {
        val match = duel()
        setSecret(match, DuelParticipant.FIRST, "1234")
        val retainedSecret = retainedSecrets(match).getValue(DuelParticipant.FIRST)

        match.close()
        match.close()

        assertCleared(retainedSecret)
        assertTrue(retainedSecrets(match).isEmpty())
        assertThrows(IllegalStateException::class.java) {
            match.snapshot()
        }
    }

    @Test
    fun unexpectedFailureClearsAllRetainedAndCommandBuffers() {
        val match = readyDuel()
        val secretMap = retainedSecrets(match)
        secretMap.put(DuelParticipant.SECOND, charArrayOf('5', '6', '7'))?.fill('\u0000')
        val retainedAtFailure = secretMap.values.toList()
        val guess = MutableDuelCommand.guess(digits("5678"))
        val ownedGuess = ownedDigits(guess)

        assertThrows(IllegalArgumentException::class.java) {
            match.submitGuess(DuelParticipant.FIRST, guess)
        }

        retainedAtFailure.forEach(::assertCleared)
        assertCleared(ownedGuess)
        assertTrue(secretMap.isEmpty())
        assertThrows(IllegalStateException::class.java) {
            match.snapshot()
        }
    }

    @Test
    fun duelApiHasNoStringCommandsOrMutableDigitConversion() {
        val commandMethods = DuelMatch::class.java.declaredMethods
            .filter { it.name == "setSecret" || it.name == "submitGuess" }
        assertTrue(commandMethods.isNotEmpty())
        commandMethods.forEach { method ->
            assertFalse(method.parameterTypes.contains(String::class.java))
        }

        val utf8Entries = classUtf8Entries(DuelMatch::class.java)
        assertFalse("concatToString" in utf8Entries)
        assertFalse("([C)V" in utf8Entries)
        assertFalse("([CII)V" in utf8Entries)
    }

    private fun duel(attemptLimit: Int = 12): DuelMatch = DuelMatch.create(
        GameConfig(
            codeLength = 4,
            allowDuplicates = true,
            attemptLimit = attemptLimit,
            forbidAllSameDigitsGuess = true,
        ),
    )

    private fun readyDuel(attemptLimit: Int = 12): DuelMatch = duel(attemptLimit).also(::ready)

    private fun ready(match: DuelMatch) {
        setSecret(match, DuelParticipant.FIRST, "1234")
        setSecret(match, DuelParticipant.SECOND, "5678")
    }

    private fun setSecret(
        match: DuelMatch,
        participant: DuelParticipant,
        value: String,
    ): DuelSnapshot = match.setSecret(participant, MutableDuelCommand.secret(digits(value)))

    private fun submitGuess(
        match: DuelMatch,
        participant: DuelParticipant,
        value: String,
    ): DuelSnapshot = match.submitGuess(participant, MutableDuelCommand.guess(digits(value)))

    private fun ownedDigits(command: MutableDuelCommand): CharArray {
        val field = MutableDuelCommand::class.java.getDeclaredField("digits")
        field.isAccessible = true
        return requireNotNull(field.get(command) as CharArray?)
    }

    @Suppress("UNCHECKED_CAST")
    private fun retainedSecrets(match: DuelMatch): MutableMap<DuelParticipant, CharArray> {
        val field = DuelMatch::class.java.getDeclaredField("secrets")
        field.isAccessible = true
        return field.get(match) as MutableMap<DuelParticipant, CharArray>
    }

    private fun assertCleared(value: CharArray) {
        assertTrue(value.all { it == '\u0000' })
    }

    private fun digits(value: String): CharArray = value.toCharArray()

    private fun assertRejected(
        rejection: DuelCommandRejection,
        validationReason: GuessValidationReason? = null,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (error: DuelCommandRejectedException) {
            assertEquals(rejection, error.rejection)
            assertEquals(validationReason, error.validationReason)
            return
        }
        throw AssertionError("Expected rejection $rejection")
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
