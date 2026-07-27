package com.mirkori.inplacex.platform.online

import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Blocking adapter for the existing local auth contract.
 *
 * Callers must invoke it from an IO dispatcher. The network transport itself
 * remains suspend-based and owns retries, timeouts, and redacted logging.
 */
class KtorGuestAuthApi(
    private val transport: TransportBoundary,
    private val gateway: RemotePlatformGateway = ContractRemotePlatformGateway(),
) : GuestAuthApi {
    private val codec = GuestAuthResponseCodec()

    override fun bootstrap(installation: GuestInstallation): GuestAuthResult = runBlocking {
        val request = gateway.prepareGuestBootstrap(
            RemoteAuthBootstrapPayload(
                installationId = installation.installationId,
                appVersion = installation.appVersion,
                locale = installation.locale,
            ),
        )
        transport.execute(request).toGuestAuthResult {
            codec.decodeBootstrap(it)
        }
    }

    override fun refresh(playerId: String, refreshToken: String): GuestAuthResult = runBlocking {
        require(playerId.isCanonicalUuid())
        val request = gateway.prepareRefresh(refreshToken)
        transport.execute(request).toGuestAuthResult {
            GuestAuthResult.Authenticated(codec.decodeCredentials(playerId, it))
        }
    }

    private fun RemoteCallResult.toGuestAuthResult(
        decode: (String) -> GuestAuthResult,
    ): GuestAuthResult = when (this) {
        is RemoteCallResult.Success -> runCatching { decode(response.body) }
            .getOrDefault(GuestAuthResult.Rejected)
        is RemoteCallResult.HttpFailure -> {
            if (response.statusCode in 400..499) GuestAuthResult.Rejected
            else GuestAuthResult.TemporarilyUnavailable
        }
        RemoteCallResult.Offline,
        RemoteCallResult.TimedOut,
        is RemoteCallResult.NetworkFailure,
        -> GuestAuthResult.TemporarilyUnavailable
        RemoteCallResult.MissingAccessToken -> GuestAuthResult.Rejected
    }
}

private class GuestAuthResponseCodec {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    fun decodeBootstrap(source: String): GuestAuthResult.Authenticated {
        val value = decodeObject(source, BootstrapFields)
        require(value.string("accountKind", 16) == "guest")
        val playerId = value.string("playerId", 36)
        require(playerId.isCanonicalUuid())
        val credentials = value["credentials"] as? JsonObject
            ?: throw IllegalArgumentException("credentials are required")
        return GuestAuthResult.Authenticated(decodeCredentials(playerId, credentials))
    }

    fun decodeCredentials(playerId: String, source: String): GuestSession =
        decodeCredentials(playerId, decodeObject(source, CredentialFields))

    private fun decodeCredentials(playerId: String, value: JsonObject): GuestSession {
        require(value.keys == CredentialFields)
        return GuestSession(
            playerId = playerId,
            accessToken = value.credential("accessToken", MaximumAccessTokenLength),
            refreshToken = value.credential("refreshToken", MaximumRefreshTokenLength),
            accessExpiresAtEpochMs = value.positiveLong("accessExpiresAtEpochMs"),
            refreshExpiresAtEpochMs = value.positiveLong("refreshExpiresAtEpochMs"),
        )
    }

    private fun decodeObject(source: String, expectedFields: Set<String>): JsonObject {
        require(source.toByteArray(StandardCharsets.UTF_8).size <= MaximumAuthResponseBytes)
        val value = json.parseToJsonElement(source) as? JsonObject
            ?: throw IllegalArgumentException("response must be an object")
        require(value.keys == expectedFields)
        return value
    }

    private fun JsonObject.string(name: String, maximum: Int): String {
        val value = this[name] as? JsonPrimitive ?: throw IllegalArgumentException("$name is required")
        require(value.isString)
        return value.content.takeIf { it.length in 1..maximum && it.none(Char::isISOControl) }
            ?: throw IllegalArgumentException("$name has an invalid format")
    }

    private fun JsonObject.credential(name: String, maximum: Int): String =
        string(name, maximum).takeIf { it.none(Char::isWhitespace) }
            ?: throw IllegalArgumentException("$name has an invalid format")

    private fun JsonObject.positiveLong(name: String): Long =
        (this[name] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.longOrNull
            ?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("$name must be positive")

    private companion object {
        val BootstrapFields = setOf("playerId", "accountKind", "credentials")
        val CredentialFields = setOf(
            "accessToken",
            "refreshToken",
            "accessExpiresAtEpochMs",
            "refreshExpiresAtEpochMs",
        )
        const val MaximumAuthResponseBytes = 16 * 1024
        const val MaximumAccessTokenLength = 4_096
        const val MaximumRefreshTokenLength = 512
    }
}

private fun String.isCanonicalUuid(): Boolean =
    runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)
