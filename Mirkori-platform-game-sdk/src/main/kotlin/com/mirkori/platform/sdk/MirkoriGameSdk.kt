package com.mirkori.platform.sdk

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

class MirkoriGameSdk(
    val config: MirkoriGameSdkConfig,
    private val transport: PlatformTransport,
    private val entropy: SecureEntropy = SecureEntropy.system(),
) {
    private val codec = SdkJsonCodec()
    private val baseUri = validateBaseUrl(config.platformBaseUrl, config.allowCleartextLoopback)
    private val callbackUri = validateCallbackUri(config.redirectUri)

    init {
        require(config.gameId.matches(GameIdPattern))
    }

    fun newInstallation(): InstallationIdentity = InstallationIdentity(
        installationId = UUID.randomUUID().toString(),
        installationSecret = entropy.token(32),
    )

    fun newIdempotencyKey(): PlatformIdempotencyKey = PlatformIdempotencyKey(entropy.token(32))

    suspend fun bootstrapGuest(
        installation: InstallationIdentity,
        platform: GameClientPlatform,
        appVersion: String? = null,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): GameIdentitySession {
        require(installation.installationId.isCanonicalUuid())
        require(installation.installationSecret.matches(HighEntropyTokenPattern))
        appVersion?.let { require(it.length in 1..64 && it.none(Char::isISOControl)) }
        val response = post(
            path = "/api/v1/auth/guest/bootstrap",
            body = codec.bootstrapRequest(config, installation, platform, appVersion),
            idempotencyKey = idempotencyKey,
        )
        val result = codec.bootstrapResponse(response)
        require(result.accountId.isCanonicalUuid())
        require(result.gamePlayerId.isCanonicalUuid())
        require(result.gameId == config.gameId)
        require(result.installationId == installation.installationId)
        return GameIdentitySession(
            accountId = result.accountId,
            gamePlayerId = result.gamePlayerId,
            gameId = result.gameId,
            installationId = result.installationId,
            authMode = PlatformAuthMode.GUEST,
            credentials = result.credentials,
        )
    }

    suspend fun refresh(
        refreshToken: String,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): PlatformCredentials {
        require(refreshToken.matches(CredentialPattern))
        return codec.credentialsResponse(
            post(
                path = "/api/v1/auth/refresh",
                body = codec.refreshRequest(refreshToken),
                idempotencyKey = idempotencyKey,
            ),
        )
    }

    suspend fun beginAccountLogin(
        profileAccessToken: String,
        installationId: String,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): PendingGameLogin {
        require(profileAccessToken.matches(CredentialPattern))
        require(installationId.isCanonicalUuid())
        val verifier = entropy.token(32)
        val state = entropy.token(32)
        val response = post(
            path = "/api/v1/game-auth/sessions",
            body = codec.gameAuthSessionRequest(
                installationId = installationId,
                redirectUri = callbackUri.toASCIIString(),
                state = state,
                challenge = pkceChallenge(verifier),
            ),
            idempotencyKey = idempotencyKey,
            bearerToken = profileAccessToken,
        )
        val result = codec.gameAuthSessionResponse(response)
        require(result.session.matches(SessionPattern))
        validateConnectUrl(result.connectUrl, result.session)
        return PendingGameLogin(
            session = result.session,
            state = state,
            codeVerifier = verifier,
            connectUrl = result.connectUrl,
            expiresAt = result.expiresAt,
        )
    }

    suspend fun completeAccountLogin(
        callbackUrl: String,
        pending: PendingGameLogin,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): GameIdentitySession {
        require(pending.session.matches(SessionPattern))
        require(pending.state.matches(PkceValuePattern))
        require(pending.codeVerifier.matches(PkceValuePattern))
        val callback = parseCallback(callbackUrl)
        if (!constantTimeEqual(callback.session, pending.session) || !constantTimeEqual(callback.state, pending.state)) {
            throw PlatformCallbackRejectedException()
        }
        if (callback.error != null) {
            if (callback.error == "profile_conflict") throw PlatformProfileConflictException()
            throw PlatformCallbackRejectedException()
        }
        val result = codec.exchangeResponse(
            post(
                path = "/api/v1/game-auth/exchange",
                body = codec.exchangeRequest(pending.session, pending.codeVerifier),
                idempotencyKey = idempotencyKey,
            ),
        )
        require(result.accountId.isCanonicalUuid())
        require(result.gamePlayerId.isCanonicalUuid())
        require(result.gameId == config.gameId)
        require(result.authMode != PlatformAuthMode.GUEST)
        return GameIdentitySession(
            accountId = result.accountId,
            gamePlayerId = result.gamePlayerId,
            gameId = result.gameId,
            installationId = null,
            authMode = result.authMode,
            credentials = result.credentials,
        )
    }

    private suspend fun post(
        path: String,
        body: String,
        idempotencyKey: PlatformIdempotencyKey,
        bearerToken: String? = null,
    ): String {
        val headers = linkedMapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Idempotency-Key" to idempotencyKey.value,
        )
        bearerToken?.let { headers["Authorization"] = "Bearer $it" }
        val response = transport.execute(
            PlatformHttpRequest(
                method = PlatformHttpMethod.POST,
                url = endpoint(path),
                headers = headers,
                body = body,
            ),
        )
        if (response.status !in 200..299) {
            throw PlatformApiException(response.status, codec.errorCode(response.body))
        }
        return response.body
    }

    private fun endpoint(path: String): String {
        require(path.startsWith('/') && !path.startsWith("//"))
        return baseUri.toASCIIString().removeSuffix("/") + path
    }

    private fun validateConnectUrl(value: String, session: String) {
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Invalid connect URL") }
        val basePort = effectivePort(baseUri)
        require(
            uri.scheme.equals(baseUri.scheme, ignoreCase = true) &&
                uri.host.equals(baseUri.host, ignoreCase = true) &&
                effectivePort(uri) == basePort && uri.path == "/connect" &&
                uri.userInfo == null && uri.fragment == null
        )
        val query = parseQuery(uri.rawQuery)
        require(query.keys == setOf("session") && query["session"] == session)
    }

    private fun parseCallback(value: String): Callback {
        val uri = runCatching { URI(value) }.getOrElse { throw PlatformCallbackRejectedException() }
        if (!sameEndpoint(uri, callbackUri) || uri.fragment != null) throw PlatformCallbackRejectedException()
        val query = runCatching { parseQuery(uri.rawQuery) }.getOrElse { throw PlatformCallbackRejectedException() }
        if (!query.keys.all(setOf("session", "state", "error")::contains)) throw PlatformCallbackRejectedException()
        val session = query["session"]?.takeIf { it.matches(SessionPattern) }
            ?: throw PlatformCallbackRejectedException()
        val state = query["state"]?.takeIf { it.matches(PkceValuePattern) }
            ?: throw PlatformCallbackRejectedException()
        val error = query["error"]?.takeIf { it.matches(ErrorCodePattern) }
        if (query.containsKey("error") && error == null) throw PlatformCallbackRejectedException()
        return Callback(session, state, error)
    }

    private data class Callback(val session: String, val state: String, val error: String?)

    private companion object {
        val GameIdPattern = Regex("[a-z0-9][a-z0-9_-]{1,63}")
        val HighEntropyTokenPattern = Regex("[A-Za-z0-9_-]{43,128}")
        val CredentialPattern = Regex("\\S{32,8192}")
        val SessionPattern = Regex("[A-Za-z0-9_-]{64}")
        val PkceValuePattern = Regex("[A-Za-z0-9._~-]{43,128}")
        val ErrorCodePattern = Regex("[a-z0-9_]{1,64}")
    }
}

fun interface SecureEntropy {
    fun bytes(count: Int): ByteArray

    fun token(count: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(count))

    companion object {
        fun system(): SecureEntropy {
            val random = SecureRandom()
            return SecureEntropy { count -> ByteArray(count).also(random::nextBytes) }
        }
    }
}

private fun validateBaseUrl(value: String, allowCleartextLoopback: Boolean): URI {
    val uri = URI(value)
    require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
    require(uri.path.isNullOrEmpty() || uri.path == "/")
    val secure = uri.scheme.equals("https", ignoreCase = true)
    val loopback = uri.scheme.equals("http", ignoreCase = true) && uri.host.lowercase() in LoopbackHosts
    require(secure || allowCleartextLoopback && loopback)
    require(effectivePort(uri) in 1..65535)
    return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, null, null, null)
}

private fun validateCallbackUri(value: String): URI {
    val uri = URI(value)
    require(
        uri.scheme.equals("https", ignoreCase = true) && uri.host != null && uri.userInfo == null &&
            !uri.path.isNullOrEmpty() && uri.query == null && uri.fragment == null && effectivePort(uri) in 1..65535
    )
    return uri
}

private fun parseQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrEmpty()) return emptyMap()
    val result = linkedMapOf<String, String>()
    rawQuery.split('&').forEach { entry ->
        require(entry.isNotEmpty())
        val parts = entry.split('=', limit = 2)
        require(parts.size == 2)
        val name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())
        val value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
        require(name !in result)
        result[name] = value
    }
    return result
}

private fun sameEndpoint(first: URI, second: URI): Boolean =
    first.scheme.equals(second.scheme, ignoreCase = true) &&
        first.host.equals(second.host, ignoreCase = true) &&
        effectivePort(first) == effectivePort(second) && first.path == second.path && first.userInfo == null

private fun effectivePort(uri: URI): Int = when {
    uri.port >= 0 -> uri.port
    uri.scheme.equals("https", ignoreCase = true) -> 443
    uri.scheme.equals("http", ignoreCase = true) -> 80
    else -> -1
}

private fun pkceChallenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
)

private fun constantTimeEqual(first: String, second: String): Boolean = MessageDigest.isEqual(
    first.toByteArray(StandardCharsets.US_ASCII),
    second.toByteArray(StandardCharsets.US_ASCII),
)

private fun String.isCanonicalUuid(): Boolean = runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)

private val LoopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")
