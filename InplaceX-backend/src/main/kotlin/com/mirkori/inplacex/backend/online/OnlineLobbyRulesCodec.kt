package com.mirkori.inplacex.backend.online

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object OnlineLobbyRulesCodec {
    fun encode(rules: OnlineMatchRules): String = buildJsonObject {
        put("schemaVersion", 1)
        put("playStyle", rules.playStyle.name)
        put("codeLength", rules.codeLength)
    }.toString()

    fun decode(raw: String): OnlineMatchRules {
        val value = Json.parseToJsonElement(raw).jsonObject
        require(value.getValue("schemaVersion").jsonPrimitive.int == 1) {
            "Unsupported online lobby rules schema"
        }
        return OnlineMatchRules(
            playStyle = enumValueOf(value.getValue("playStyle").jsonPrimitive.content),
            codeLength = value.getValue("codeLength").jsonPrimitive.int,
        )
    }

    fun encodeInvite(invite: PrivateDuelInvite): String = buildJsonObject {
        put("schemaVersion", 1)
        put("playStyle", invite.playStyle.name)
        put("codeLength", invite.codeLength)
        put("allowDuplicates", invite.allowDuplicates)
        put("maxConsecutiveDuplicateDigits", invite.maxConsecutiveDuplicateDigits)
        put("matchDurationSeconds", invite.matchDurationSeconds)
    }.toString()

    fun decodeInvite(raw: String): DurableInviteRules {
        val value = Json.parseToJsonElement(raw).jsonObject
        require(value.getValue("schemaVersion").jsonPrimitive.int == 1) {
            "Unsupported private invite rules schema"
        }
        return DurableInviteRules(
            playStyle = enumValueOf(value.getValue("playStyle").jsonPrimitive.content),
            codeLength = value.getValue("codeLength").jsonPrimitive.int,
            allowDuplicates = value.getValue("allowDuplicates").jsonPrimitive.content.toBooleanStrict(),
            maxConsecutiveDuplicateDigits = value.getValue("maxConsecutiveDuplicateDigits").jsonPrimitive.int,
            matchDurationSeconds = value.getValue("matchDurationSeconds").jsonPrimitive.content.toLong(),
        )
    }
}

internal data class DurableInviteRules(
    val playStyle: OnlineFriendPlayStyle,
    val codeLength: Int,
    val allowDuplicates: Boolean,
    val maxConsecutiveDuplicateDigits: Int,
    val matchDurationSeconds: Long,
) {
    init {
        OnlineMatchRules(playStyle, codeLength)
        require(allowDuplicates) { "Private invite must allow duplicate digits" }
        require(maxConsecutiveDuplicateDigits == 3) {
            "Private invite duplicate-run limit does not match the v1 contract"
        }
        require(matchDurationSeconds > 0) { "Private invite match duration must be positive" }
    }
}
