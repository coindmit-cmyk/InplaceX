package com.mirkori.inplacex.backend.app

import com.mirkori.platform.sdk.MirkoriGameServerCredential
import java.nio.file.Path
import java.time.Duration

data class MirkoriTelemetryRuntimeConfig(
    val platformBaseUrl: String,
    val gameId: String,
    val credentialFile: Path,
    val allowCleartextLoopback: Boolean = false,
    val pollInterval: Duration = Duration.ofSeconds(5),
    val claimLease: Duration = Duration.ofSeconds(30),
    val heartbeatInterval: Duration = Duration.ofMinutes(4),
) {
    init {
        require(platformBaseUrl.length in 1..2048 && platformBaseUrl.none(Char::isISOControl))
        require(gameId.matches(GameIdPattern))
        require(credentialFile.isAbsolute) { "Mirkori telemetry credential file must use an absolute path" }
        require(!pollInterval.isNegative && !pollInterval.isZero)
        require(!claimLease.isNegative && !claimLease.isZero)
        require(heartbeatInterval in MinimumHeartbeatInterval..MaximumHeartbeatInterval)
    }

    internal fun readCredential(): MirkoriGameServerCredential = MirkoriGameServerCredential.fromToken(
        RuntimeSecretFile.readText(
            path = credentialFile,
            minimumCharacters = MinimumCredentialCharacters,
            maximumBytes = MaximumCredentialBytes,
        ),
    )

    companion object {
        const val EnabledEnvironmentKey = "INPLACEX_MIRKORI_TELEMETRY_ENABLED"
        const val PlatformBaseUrlEnvironmentKey = "INPLACEX_MIRKORI_PLATFORM_BASE_URL"
        const val GameIdEnvironmentKey = "INPLACEX_MIRKORI_GAME_ID"
        const val CredentialFileEnvironmentKey = "INPLACEX_MIRKORI_GAME_CREDENTIAL_FILE"
        const val AllowCleartextLoopbackEnvironmentKey = "INPLACEX_MIRKORI_ALLOW_CLEARTEXT_LOOPBACK"

        fun fromEnvironmentOrNull(
            environment: Map<String, String>,
            production: Boolean,
        ): MirkoriTelemetryRuntimeConfig? {
            val enabled = environment.boolean(EnabledEnvironmentKey, default = false)
            if (!enabled) return null
            val allowCleartextLoopback = environment.boolean(AllowCleartextLoopbackEnvironmentKey, default = false)
            require(!production || !allowCleartextLoopback) {
                "Production Mirkori telemetry forbids cleartext loopback"
            }
            return MirkoriTelemetryRuntimeConfig(
                platformBaseUrl = environment.required(PlatformBaseUrlEnvironmentKey),
                gameId = environment[GameIdEnvironmentKey]?.trim()?.takeIf(String::isNotEmpty) ?: "inplacex",
                credentialFile = Path.of(environment.required(CredentialFileEnvironmentKey)),
                allowCleartextLoopback = allowCleartextLoopback,
            )
        }

        private val GameIdPattern = Regex("[a-z0-9][a-z0-9-]{0,63}")
        private val MinimumHeartbeatInterval = Duration.ofSeconds(30)
        private val MaximumHeartbeatInterval = Duration.ofMinutes(4)
        private const val MinimumCredentialCharacters = 80
        private const val MaximumCredentialBytes = 256
    }
}

private fun Map<String, String>.required(key: String): String =
    get(key)?.trim()?.takeIf(String::isNotEmpty)
        ?: throw IllegalArgumentException("$key is required when Mirkori telemetry is enabled")

private fun Map<String, String>.boolean(key: String, default: Boolean): Boolean {
    val value = get(key)?.trim()?.lowercase() ?: return default
    return when (value) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("$key must be true or false")
    }
}
