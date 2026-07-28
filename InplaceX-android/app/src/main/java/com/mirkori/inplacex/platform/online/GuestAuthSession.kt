package com.mirkori.inplacex.platform.online

import com.mirkori.inplacex.logging.InplaceXLogger

data class GuestInstallation(
    val installationId: String,
    val locale: String,
    val regionCode: String,
    val appVersion: String? = null,
) {
    init {
        require(installationId.isNotBlank())
        require(locale.isNotBlank())
        require(regionCode.isNotBlank())
    }
}

data class GuestSession(
    val playerId: String,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtEpochMs: Long,
    val refreshExpiresAtEpochMs: Long,
) {
    init {
        require(playerId.isNotBlank())
        require(accessToken.isNotBlank())
        require(refreshToken.isNotBlank())
        require(accessExpiresAtEpochMs > 0)
        require(refreshExpiresAtEpochMs >= accessExpiresAtEpochMs)
    }

    override fun toString(): String = "GuestSession(playerId=$playerId, credentials=[redacted])"
}

sealed interface GuestAuthResult {
    data class Authenticated(val session: GuestSession) : GuestAuthResult

    data object Rejected : GuestAuthResult

    data object TemporarilyUnavailable : GuestAuthResult
}

interface GuestAuthApi {
    fun bootstrap(installation: GuestInstallation): GuestAuthResult

    fun refresh(playerId: String, refreshToken: String): GuestAuthResult
}

/**
 * Реализация обязана хранить refresh-токен в защищённом хранилище устройства.
 * Адаптер не журналирует и не сериализует credentials вне этого контракта.
 */
interface SecureGuestSessionStore {
    fun read(): GuestSession?

    fun write(session: GuestSession)

    fun clear()
}

class GuestAuthSessionManager(
    private val api: GuestAuthApi,
    private val store: SecureGuestSessionStore,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val logger: InplaceXLogger = InplaceXLogger(),
) : AccessTokenProvider {
    fun bootstrap(installation: GuestInstallation): GuestAuthResult {
        return persistSuccessfulResult(api.bootstrap(installation), operation = "bootstrap")
    }

    fun sessionOrNull(): GuestSession? = store.read()

    fun sessionWithFreshAccessTokenOrNull(): GuestSession? {
        return accessTokenOrNull()?.let { store.read() }
    }

    fun acceptProviderResult(result: GuestAuthResult): GuestAuthResult =
        persistSuccessfulResult(result, operation = "provider")

    fun clear() {
        store.clear()
        logger.info(
            tag = LogTag,
            message = "local online session cleared",
            attributes = mapOf("operation" to "sign_out"),
        )
    }

    fun accessTokenOrNull(): String? {
        val session = store.read() ?: return null
        return session.accessToken.takeIf { session.accessExpiresAtEpochMs > clockMs() }
            ?: refreshAccessTokenOrNull()
    }

    fun refreshAccessTokenOrNull(): String? {
        val session = store.read() ?: return null
        if (session.refreshExpiresAtEpochMs <= clockMs()) {
            store.clear()
            logger.warn(
                tag = LogTag,
                message = "guest refresh token expired",
                attributes = mapOf("outcome" to "expired"),
            )
            return null
        }
        return when (
            val result = persistSuccessfulResult(
                api.refresh(session.playerId, session.refreshToken),
                operation = "refresh",
            )
        ) {
            is GuestAuthResult.Authenticated -> result.session.accessToken
            GuestAuthResult.Rejected -> {
                store.clear()
                null
            }
            GuestAuthResult.TemporarilyUnavailable -> null
        }
    }

    override suspend fun currentAccessToken(): AccessToken? =
        accessTokenOrNull()?.let(AccessToken::from)

    override suspend fun refreshAccessToken(rejectedToken: AccessToken): AccessToken? {
        val current = store.read()
            ?.takeIf { it.accessExpiresAtEpochMs > clockMs() }
            ?.accessToken
            ?.let(AccessToken::from)
        if (current != null && !current.sameValueAs(rejectedToken)) {
            return current
        }
        return refreshAccessTokenOrNull()?.let(AccessToken::from)
    }

    private fun persistSuccessfulResult(result: GuestAuthResult, operation: String): GuestAuthResult = when (result) {
        is GuestAuthResult.Authenticated -> {
            store.write(result.session)
            logger.info(
                tag = LogTag,
                message = "guest session updated",
                attributes = mapOf("operation" to operation),
            )
            result
        }
        GuestAuthResult.Rejected,
        GuestAuthResult.TemporarilyUnavailable,
        -> result
    }

    private companion object {
        const val LogTag = "GuestAuth"
    }
}
