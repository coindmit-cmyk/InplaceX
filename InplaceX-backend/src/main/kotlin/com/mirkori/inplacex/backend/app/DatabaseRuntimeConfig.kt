package com.mirkori.inplacex.backend.app

/**
 * Параметры подключения берутся только из окружения процесса. Пароль намеренно
 * не входит в data class, чтобы не оказаться в случайном toString() или логе.
 */
class DatabaseRuntimeConfig private constructor(
    val jdbcUrl: String,
    val username: String,
    val password: String,
) {
    companion object {
        const val JdbcUrlEnvironmentKey = "INPLACEX_DATABASE_JDBC_URL"
        const val UsernameEnvironmentKey = "INPLACEX_DATABASE_USERNAME"
        const val PasswordEnvironmentKey = "INPLACEX_DATABASE_PASSWORD"

        fun fromEnvironmentOrNull(environment: Map<String, String>): DatabaseRuntimeConfig? {
            val jdbcUrl = environment[JdbcUrlEnvironmentKey]?.takeIf(String::isNotBlank) ?: return null
            val username = environment[UsernameEnvironmentKey]?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("$UsernameEnvironmentKey must be set when $JdbcUrlEnvironmentKey is set")
            val password = environment[PasswordEnvironmentKey]
                ?: throw IllegalArgumentException("$PasswordEnvironmentKey must be set when $JdbcUrlEnvironmentKey is set")

            return DatabaseRuntimeConfig(jdbcUrl, username, password)
        }
    }
}
