package com.mirkori.inplacex.backend.app

import java.nio.file.Path

/**
 * Параметры подключения берутся только из окружения процесса. Пароль намеренно
 * не входит в data class, чтобы не оказаться в случайном toString() или логе.
 */
class DatabaseRuntimeConfig private constructor(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val usesExternalPassword: Boolean,
    val acknowledgeLegacyChecksumBaseline: Boolean,
) {
    val isPostgres: Boolean
        get() = jdbcUrl.startsWith("jdbc:postgresql://")

    val hasNonEmptyPassword: Boolean
        get() = password.isNotEmpty()

    override fun toString(): String =
        "DatabaseRuntimeConfig(jdbcUrl=${jdbcUrl.substringBefore('?')}[query-redacted], " +
            "username=$username, password=[redacted])"

    companion object {
        const val JdbcUrlEnvironmentKey = "INPLACEX_DATABASE_JDBC_URL"
        const val UsernameEnvironmentKey = "INPLACEX_DATABASE_USERNAME"
        const val PasswordEnvironmentKey = "INPLACEX_DATABASE_PASSWORD"
        const val PasswordPathEnvironmentKey = "INPLACEX_DATABASE_PASSWORD_PATH"
        const val LegacyChecksumBaselineAcknowledgementEnvironmentKey =
            "INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK"
        const val LegacyChecksumBaselineAcknowledgement = "acknowledge-inplacex-schema-v1-v8"

        fun fromEnvironmentOrNull(environment: Map<String, String>): DatabaseRuntimeConfig? {
            val jdbcUrl = environment[JdbcUrlEnvironmentKey]?.takeIf(String::isNotBlank) ?: return null
            require(jdbcUrl.none(Char::isWhitespace) && jdbcUrl.none(Char::isISOControl)) {
                "$JdbcUrlEnvironmentKey has an invalid format"
            }
            require('?' !in jdbcUrl && '#' !in jdbcUrl && '@' !in jdbcUrl.removePrefix("jdbc:postgresql://")) {
                "$JdbcUrlEnvironmentKey must not contain query parameters, fragments, or userinfo"
            }
            val username = environment[UsernameEnvironmentKey]?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("$UsernameEnvironmentKey must be set when $JdbcUrlEnvironmentKey is set")
            val inlinePassword = environment[PasswordEnvironmentKey]
            val passwordPath = environment[PasswordPathEnvironmentKey]?.trim()?.takeIf(String::isNotEmpty)
            require(inlinePassword == null || passwordPath == null) {
                "Configure either $PasswordEnvironmentKey or $PasswordPathEnvironmentKey, not both"
            }
            val password = inlinePassword ?: passwordPath?.let { path ->
                RuntimeSecretFile.readText(Path.of(path), minimumCharacters = 16, maximumBytes = 512)
            } ?: throw IllegalArgumentException(
                "$PasswordEnvironmentKey or $PasswordPathEnvironmentKey must be set when $JdbcUrlEnvironmentKey is set",
            )

            val baselineAcknowledgement = environment[LegacyChecksumBaselineAcknowledgementEnvironmentKey]
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            require(
                baselineAcknowledgement == null ||
                    baselineAcknowledgement == LegacyChecksumBaselineAcknowledgement,
            ) {
                "$LegacyChecksumBaselineAcknowledgementEnvironmentKey must use the exact documented acknowledgement"
            }

            return DatabaseRuntimeConfig(
                jdbcUrl = jdbcUrl,
                username = username,
                password = password,
                usesExternalPassword = passwordPath != null,
                acknowledgeLegacyChecksumBaseline = baselineAcknowledgement != null,
            )
        }
    }
}
