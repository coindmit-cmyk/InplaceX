package com.mirkori.inplacex.platform.online

import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

data class GoogleAuthChallenge(
    val nonce: String,
    val expiresAtEpochMs: Long,
) {
    override fun toString(): String = "GoogleAuthChallenge([redacted])"
}

sealed interface GoogleChallengeResult {
    data class Ready(val challenge: GoogleAuthChallenge) : GoogleChallengeResult
    data object AuthenticationRequired : GoogleChallengeResult
    data object ProviderUnavailable : GoogleChallengeResult
    data object TemporarilyUnavailable : GoogleChallengeResult
    data object Rejected : GoogleChallengeResult
}

class KtorGoogleAuthApi(
    private val transport: TransportBoundary,
    private val gateway: RemotePlatformGateway = ContractRemotePlatformGateway(),
) {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }
    private val authCodec = GuestAuthResponseCodec()

    suspend fun challenge(): GoogleChallengeResult =
        when (val result = transport.execute(gateway.prepareGoogleChallenge())) {
            is RemoteCallResult.Success -> runCatching {
                GoogleChallengeResult.Ready(decodeChallenge(result.response.body))
            }.getOrDefault(GoogleChallengeResult.Rejected)
            is RemoteCallResult.HttpFailure -> when (result.response.statusCode) {
                401 -> GoogleChallengeResult.AuthenticationRequired
                404, 503 -> GoogleChallengeResult.ProviderUnavailable
                in 500..599 -> GoogleChallengeResult.TemporarilyUnavailable
                else -> GoogleChallengeResult.Rejected
            }
            RemoteCallResult.MissingAccessToken -> GoogleChallengeResult.AuthenticationRequired
            RemoteCallResult.Offline,
            RemoteCallResult.AccessTokenTemporarilyUnavailable,
            RemoteCallResult.TimedOut,
            is RemoteCallResult.NetworkFailure,
            -> GoogleChallengeResult.TemporarilyUnavailable
        }

    suspend fun authenticate(
        idToken: String,
        nonce: String,
    ): GuestAuthResult {
        val request = gateway.prepareGoogleAuthentication(
            RemoteGoogleAuthenticationPayload(idToken = idToken, nonce = nonce),
        )
        return when (val result = transport.execute(request)) {
            is RemoteCallResult.Success -> runCatching {
                authCodec.decodeProvider(result.response.body, expectedAccountKind = "google")
            }.getOrDefault(GuestAuthResult.Rejected)
            is RemoteCallResult.HttpFailure -> {
                if (result.response.statusCode in 400..499) {
                    GuestAuthResult.Rejected
                } else {
                    GuestAuthResult.TemporarilyUnavailable
                }
            }
            RemoteCallResult.Offline -> GuestAuthResult.Offline
            RemoteCallResult.AccessTokenTemporarilyUnavailable,
            RemoteCallResult.TimedOut,
            is RemoteCallResult.NetworkFailure,
            -> GuestAuthResult.TemporarilyUnavailable
            RemoteCallResult.MissingAccessToken -> GuestAuthResult.Rejected
        }
    }

    private fun decodeChallenge(source: String): GoogleAuthChallenge {
        require(source.toByteArray(StandardCharsets.UTF_8).size <= MaximumResponseBytes)
        val value = json.parseToJsonElement(source) as? JsonObject
            ?: throw IllegalArgumentException("challenge response must be an object")
        require(value.keys == ChallengeFields)
        val nonce = (value["nonce"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{32,128}")) }
            ?: throw IllegalArgumentException("challenge nonce has an invalid format")
        val expiresAt = (value["expiresAtEpochMs"] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.longOrNull
            ?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("challenge expiry is invalid")
        return GoogleAuthChallenge(nonce = nonce, expiresAtEpochMs = expiresAt)
    }

    private companion object {
        val ChallengeFields = setOf("nonce", "expiresAtEpochMs")
        const val MaximumResponseBytes = 4 * 1024
    }
}
