package com.mirkori.platform.sdk

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal class SdkJsonCodec {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun bootstrapRequest(
        config: MirkoriGameSdkConfig,
        installation: InstallationIdentity,
        platform: GameClientPlatform,
        appVersion: String?,
    ): String = buildJsonObject {
        put("gameId", config.gameId)
        put("installationId", installation.installationId)
        put("installationSecret", installation.installationSecret)
        put("platform", platform.wireName)
        appVersion?.let { put("appVersion", it) }
    }.toString()

    fun refreshRequest(refreshToken: String): String = buildJsonObject {
        put("refreshToken", refreshToken)
    }.toString()

    fun gameAuthSessionRequest(
        installationId: String,
        redirectUri: String,
        state: String,
        challenge: String,
    ): String = buildJsonObject {
        put("installationId", installationId)
        put("redirectUri", redirectUri)
        put("state", state)
        put("codeChallenge", challenge)
        put("codeChallengeMethod", "S256")
    }.toString()

    fun exchangeRequest(session: String, verifier: String): String = buildJsonObject {
        put("session", session)
        put("codeVerifier", verifier)
    }.toString()

    fun bootstrapResponse(body: String): BootstrapResponse {
        val root = objectBody(body)
        root.requireExactFields("accountId", "gamePlayerId", "gameId", "installationId", "credentials")
        return BootstrapResponse(
            accountId = root.string("accountId", 64),
            gamePlayerId = root.string("gamePlayerId", 64),
            gameId = root.string("gameId", 64),
            installationId = root.string("installationId", 64),
            credentials = credentials(root["credentials"]?.jsonObject ?: reject()),
        )
    }

    fun credentialsResponse(body: String): PlatformCredentials = credentials(objectBody(body))

    fun gameAuthSessionResponse(body: String): GameAuthSessionResponse {
        val root = objectBody(body)
        root.requireExactFields("session", "connectUrl", "expiresAtEpochMs")
        return GameAuthSessionResponse(
            session = root.string("session", 128),
            connectUrl = root.string("connectUrl", 4096),
            expiresAt = Instant.ofEpochMilli(root.long("expiresAtEpochMs")),
        )
    }

    fun exchangeResponse(body: String): ExchangeResponse {
        val root = objectBody(body)
        root.requireExactFields("accountId", "gamePlayerId", "gameId", "authMode", "credentials")
        return ExchangeResponse(
            accountId = root.string("accountId", 64),
            gamePlayerId = root.string("gamePlayerId", 64),
            gameId = root.string("gameId", 64),
            authMode = PlatformAuthMode.fromWireName(root.string("authMode", 16)) ?: reject(),
            credentials = credentials(root["credentials"]?.jsonObject ?: reject()),
        )
    }

    fun errorCode(body: String): String = runCatching {
        val root = objectBody(body)
        root.requireExactFields("error")
        root.string("error", 128)
    }.getOrDefault("invalid_response")

    private fun credentials(value: JsonObject): PlatformCredentials {
        value.requireExactFields(
            "accessToken",
            "refreshToken",
            "accessExpiresAtEpochMs",
            "refreshExpiresAtEpochMs",
        )
        return PlatformCredentials(
            accessToken = value.credential("accessToken", 8192),
            refreshToken = value.credential("refreshToken", 512),
            accessExpiresAt = Instant.ofEpochMilli(value.long("accessExpiresAtEpochMs")),
            refreshExpiresAt = Instant.ofEpochMilli(value.long("refreshExpiresAtEpochMs")),
        )
    }

    private fun objectBody(body: String): JsonObject {
        require(body.toByteArray(Charsets.UTF_8).size in 2..MaximumResponseBytes)
        return json.parseToJsonElement(body) as? JsonObject ?: reject()
    }

    private fun JsonObject.requireExactFields(vararg names: String) {
        require(keys == names.toSet())
    }

    private fun JsonObject.string(name: String, maximum: Int): String {
        val primitive = get(name) as? JsonPrimitive ?: reject()
        require(primitive.isString)
        return primitive.content.takeIf { it.length in 1..maximum && it.none(Char::isISOControl) } ?: reject()
    }

    private fun JsonObject.credential(name: String, maximum: Int): String =
        string(name, maximum).takeIf { it.none(Char::isWhitespace) } ?: reject()

    private fun JsonObject.long(name: String): Long =
        runCatching { get(name)?.jsonPrimitive?.long }.getOrNull() ?: reject()

    private fun reject(): Nothing = throw IllegalArgumentException("Invalid platform response")

    internal data class BootstrapResponse(
        val accountId: String,
        val gamePlayerId: String,
        val gameId: String,
        val installationId: String,
        val credentials: PlatformCredentials,
    )

    internal data class GameAuthSessionResponse(
        val session: String,
        val connectUrl: String,
        val expiresAt: Instant,
    )

    internal data class ExchangeResponse(
        val accountId: String,
        val gamePlayerId: String,
        val gameId: String,
        val authMode: PlatformAuthMode,
        val credentials: PlatformCredentials,
    )

    private companion object {
        const val MaximumResponseBytes = 64 * 1024
    }
}
