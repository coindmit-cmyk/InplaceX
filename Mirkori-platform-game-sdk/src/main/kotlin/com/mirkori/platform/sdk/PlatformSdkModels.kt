package com.mirkori.platform.sdk

import java.time.Instant

data class MirkoriGameSdkConfig(
    val platformBaseUrl: String,
    val gameId: String,
    val redirectUri: String,
    val allowCleartextLoopback: Boolean = false,
)

enum class GameClientPlatform(val wireName: String) {
    ANDROID("android"),
    IOS("ios"),
    WINDOWS("windows"),
    LINUX("linux"),
    WEB("web"),
}

enum class PlatformAuthMode(val wireName: String) {
    GUEST("guest"),
    GOOGLE("google"),
    LOCAL("local"),
    TELEGRAM("telegram");

    companion object {
        internal fun fromWireName(value: String): PlatformAuthMode? = entries.firstOrNull { it.wireName == value }
    }
}

class InstallationIdentity(
    val installationId: String,
    val installationSecret: String,
) {
    override fun toString(): String = "InstallationIdentity([redacted])"
}

class PlatformCredentials(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Instant,
    val refreshExpiresAt: Instant,
) {
    override fun toString(): String = "PlatformCredentials([redacted])"
}

class GameIdentitySession(
    val accountId: String,
    val gamePlayerId: String,
    val gameId: String,
    val installationId: String?,
    val authMode: PlatformAuthMode,
    val credentials: PlatformCredentials,
) {
    override fun toString(): String =
        "GameIdentitySession(gameId=$gameId, authMode=${authMode.wireName}, [redacted])"
}

class PendingGameLogin(
    val session: String,
    val state: String,
    val codeVerifier: String,
    val connectUrl: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "PendingGameLogin(expiresAt=$expiresAt, [redacted])"
}

class PlatformIdempotencyKey(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9._~-]{1,128}")))
    }

    override fun toString(): String = "PlatformIdempotencyKey([redacted])"
}

class PlatformApiException(
    val status: Int,
    val errorCode: String,
) : IllegalStateException("Platform request failed: HTTP $status ($errorCode)")

class PlatformCallbackRejectedException : IllegalArgumentException("Game login callback is not accepted")

class PlatformProfileConflictException : IllegalStateException("The platform account already has a game profile")
