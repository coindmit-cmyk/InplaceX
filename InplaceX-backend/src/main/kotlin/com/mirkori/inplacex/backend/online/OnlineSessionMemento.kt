package com.mirkori.inplacex.backend.online

import com.mirkori.inplacex.backend.bot.ServerBotProfile
import com.mirkori.inplacex.backend.domain.duel.DuelParticipant
import com.mirkori.inplacex.backend.domain.duel.DuelPlayStyle
import com.mirkori.inplacex.core.bot.BotBehaviorModel
import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.core.model.GameConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.Instant

internal data class OnlineSessionMemento(
    val sessionId: String,
    val revision: Long,
    val config: GameConfig,
    val playStyle: DuelPlayStyle,
    val attemptLimit: Int?,
    val memberships: Map<String, DuelParticipant>,
    val bot: DurableBot?,
    val secrets: Map<DuelParticipant, String>,
    val guesses: List<DurableGuess>,
    val commandReplays: List<DurableCommandReplay>,
    val createdAt: Instant,
    val setupDeadlineAt: Instant,
    val matchDurationMillis: Long?,
    val startedAt: Instant?,
    val deadlineAt: Instant?,
    val finishedAt: Instant?,
    val finishedByTimeout: Boolean,
)

internal data class DurableBot(
    val profile: ServerBotProfile,
    val brainSeed: Long,
)

internal data class DurableGuess(
    val participant: DuelParticipant,
    val guess: String,
)

internal data class DurableCommandReplay(
    val playerId: String,
    val commandId: String,
    val fingerprint: String,
    val snapshot: OnlineDuelSnapshot,
)

internal object OnlineSessionMementoCodec {
    fun encode(value: OnlineSessionMemento): String = value.toJson().toString()

    fun decode(raw: String): OnlineSessionMemento = Json.parseToJsonElement(raw).jsonObject.toMemento()

    private fun OnlineSessionMemento.toJson(): JsonObject = buildJsonObject {
        put("schemaVersion", 1)
        put("sessionId", sessionId)
        put("revision", revision)
        put("config", buildJsonObject {
            put("codeLength", config.codeLength)
            put("allowDuplicates", config.allowDuplicates)
            put("attemptLimit", config.attemptLimit)
            putNullable("maxConsecutiveDuplicateDigits", config.maxConsecutiveDuplicateDigits)
        })
        put("playStyle", playStyle.name)
        putNullable("attemptLimit", attemptLimit)
        put("memberships", buildJsonArray {
            memberships.toSortedMap().forEach { (playerId, participant) ->
                add(buildJsonObject {
                    put("playerId", playerId)
                    put("participant", participant.name)
                })
            }
        })
        put("bot", bot?.toJson() ?: JsonNull)
        put("secrets", buildJsonArray {
            secrets.toSortedMap(compareBy(DuelParticipant::ordinal)).forEach { (participant, secret) ->
                add(buildJsonObject {
                    put("participant", participant.name)
                    put("secret", secret)
                })
            }
        })
        put("guesses", buildJsonArray {
            guesses.forEach { guess ->
                add(buildJsonObject {
                    put("participant", guess.participant.name)
                    put("guess", guess.guess)
                })
            }
        })
        put("commandReplays", buildJsonArray { commandReplays.forEach { add(it.toJson()) } })
        put("createdAt", createdAt.toString())
        put("setupDeadlineAt", setupDeadlineAt.toString())
        putNullable("matchDurationMillis", matchDurationMillis)
        putNullable("startedAt", startedAt?.toString())
        putNullable("deadlineAt", deadlineAt?.toString())
        putNullable("finishedAt", finishedAt?.toString())
        put("finishedByTimeout", finishedByTimeout)
    }

    private fun DurableBot.toJson(): JsonObject = buildJsonObject {
        put("botId", profile.botId)
        put("displayName", profile.displayName)
        put("difficulty", profile.difficulty.name)
        put("behavior", profile.behavior.name)
        put("brainSeed", brainSeed)
    }

    private fun DurableCommandReplay.toJson(): JsonObject = buildJsonObject {
        put("playerId", playerId)
        put("commandId", commandId)
        put("fingerprint", fingerprint)
        put("snapshot", snapshot.toJson())
    }

    private fun OnlineDuelSnapshot.toJson(): JsonObject = buildJsonObject {
        put("sessionId", sessionId)
        put("revision", revision)
        put("phase", phase)
        putNullable("currentTurn", currentTurn)
        putNullable("winner", winner)
        putNullable("finishReason", finishReason)
        put("playStyle", playStyle)
        put("codeLength", codeLength)
        putNullable("attemptLimit", attemptLimit)
        put("allowDuplicates", allowDuplicates)
        putNullable("maxConsecutiveDuplicateDigits", maxConsecutiveDuplicateDigits)
        putNullable("startedAtEpochMs", startedAtEpochMs)
        putNullable("deadlineAtEpochMs", deadlineAtEpochMs)
        put("serverTimeEpochMs", serverTimeEpochMs)
        put("attempts", buildJsonArray {
            attempts.forEach { attempt ->
                add(buildJsonObject {
                    put("actor", attempt.actor)
                    put("exactMatches", attempt.exactMatches)
                    put("number", attempt.number)
                    putNullable("ownGuess", attempt.ownGuess)
                })
            }
        })
        put("participants", buildJsonArray {
            participants.forEach { participant ->
                add(buildJsonObject {
                    put("actor", participant.actor)
                    put("secretConfigured", participant.secretConfigured)
                    put("attemptsUsed", participant.attemptsUsed)
                    putNullable("attemptsLeft", participant.attemptsLeft)
                })
            }
        })
    }

    private fun JsonObject.toMemento(): OnlineSessionMemento {
        require(requiredInt("schemaVersion") == 1) { "Unsupported online session memento schema" }
        val configJson = requiredObject("config")
        return OnlineSessionMemento(
            sessionId = requiredString("sessionId"),
            revision = requiredLong("revision"),
            config = GameConfig(
                codeLength = configJson.requiredInt("codeLength"),
                allowDuplicates = configJson.requiredBoolean("allowDuplicates"),
                attemptLimit = configJson.requiredInt("attemptLimit"),
                maxConsecutiveDuplicateDigits = configJson.optionalInt("maxConsecutiveDuplicateDigits"),
            ),
            playStyle = enumValueOf(requiredString("playStyle")),
            attemptLimit = optionalInt("attemptLimit"),
            memberships = requiredArray("memberships").associate { element ->
                val item = element.jsonObject
                item.requiredString("playerId") to enumValueOf(item.requiredString("participant"))
            },
            bot = optionalObject("bot")?.let { item ->
                DurableBot(
                    profile = ServerBotProfile(
                        botId = item.requiredString("botId"),
                        displayName = item.requiredString("displayName"),
                        difficulty = enumValueOf<BotDifficulty>(item.requiredString("difficulty")),
                        behavior = enumValueOf<BotBehaviorModel>(item.requiredString("behavior")),
                    ),
                    brainSeed = item.requiredLong("brainSeed"),
                )
            },
            secrets = requiredArray("secrets").associate { element ->
                val item = element.jsonObject
                enumValueOf<DuelParticipant>(item.requiredString("participant")) to item.requiredString("secret")
            },
            guesses = requiredArray("guesses").map { element ->
                val item = element.jsonObject
                DurableGuess(
                    participant = enumValueOf(item.requiredString("participant")),
                    guess = item.requiredString("guess"),
                )
            },
            commandReplays = requiredArray("commandReplays").map { element -> element.jsonObject.toReplay() },
            createdAt = Instant.parse(requiredString("createdAt")),
            setupDeadlineAt = Instant.parse(requiredString("setupDeadlineAt")),
            matchDurationMillis = optionalLong("matchDurationMillis"),
            startedAt = optionalString("startedAt")?.let(Instant::parse),
            deadlineAt = optionalString("deadlineAt")?.let(Instant::parse),
            finishedAt = optionalString("finishedAt")?.let(Instant::parse),
            finishedByTimeout = requiredBoolean("finishedByTimeout"),
        )
    }

    private fun JsonObject.toReplay(): DurableCommandReplay = DurableCommandReplay(
        playerId = requiredString("playerId"),
        commandId = requiredString("commandId"),
        fingerprint = requiredString("fingerprint"),
        snapshot = requiredObject("snapshot").toSnapshot(),
    )

    private fun JsonObject.toSnapshot(): OnlineDuelSnapshot = OnlineDuelSnapshot(
        sessionId = requiredString("sessionId"),
        revision = requiredLong("revision"),
        phase = requiredString("phase"),
        currentTurn = optionalString("currentTurn"),
        winner = optionalString("winner"),
        finishReason = optionalString("finishReason"),
        playStyle = requiredString("playStyle"),
        codeLength = requiredInt("codeLength"),
        attemptLimit = optionalInt("attemptLimit"),
        allowDuplicates = requiredBoolean("allowDuplicates"),
        maxConsecutiveDuplicateDigits = optionalInt("maxConsecutiveDuplicateDigits"),
        startedAtEpochMs = optionalLong("startedAtEpochMs"),
        deadlineAtEpochMs = optionalLong("deadlineAtEpochMs"),
        serverTimeEpochMs = requiredLong("serverTimeEpochMs"),
        attempts = requiredArray("attempts").map { element ->
            val item = element.jsonObject
            OnlineDuelAttempt(
                actor = item.requiredString("actor"),
                exactMatches = item.requiredInt("exactMatches"),
                number = item.requiredInt("number"),
                ownGuess = item.optionalString("ownGuess"),
            )
        },
        participants = requiredArray("participants").map { element ->
            val item = element.jsonObject
            OnlineDuelParticipant(
                actor = item.requiredString("actor"),
                secretConfigured = item.requiredBoolean("secretConfigured"),
                attemptsUsed = item.requiredInt("attemptsUsed"),
                attemptsLeft = item.optionalInt("attemptsLeft"),
            )
        },
    )
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: String?) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: Int?) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: Long?) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObject.requiredString(name: String): String = getValue(name).jsonPrimitive.content
private fun JsonObject.requiredInt(name: String): Int = getValue(name).jsonPrimitive.int
private fun JsonObject.requiredLong(name: String): Long = getValue(name).jsonPrimitive.long
private fun JsonObject.requiredBoolean(name: String): Boolean = getValue(name).jsonPrimitive.boolean
private fun JsonObject.requiredArray(name: String): JsonArray = getValue(name).jsonArray
private fun JsonObject.requiredObject(name: String): JsonObject = getValue(name).jsonObject
private fun JsonObject.optionalString(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull
private fun JsonObject.optionalInt(name: String): Int? = optionalString(name)?.toInt()
private fun JsonObject.optionalLong(name: String): Long? = optionalString(name)?.toLong()
private fun JsonObject.optionalObject(name: String): JsonObject? = get(name)?.takeUnless { it is JsonNull }?.jsonObject
