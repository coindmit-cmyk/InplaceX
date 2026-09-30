package com.mirkori.inplacex.backend.app

data class BackendRuntimeConfig(
    val host: String,
    val port: Int,
    val environment: String,
    val database: DatabaseRuntimeConfig? = null,
    val online: OnlineRuntimeConfig? = null,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): BackendRuntimeConfig {
            val portValue = environment["INPLACEX_BACKEND_PORT"] ?: environment["PORT"] ?: DefaultPort.toString()
            val port = portValue.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("Backend port must be an integer in 1..65535")

            return BackendRuntimeConfig(
                host = environment["INPLACEX_BACKEND_HOST"]?.takeIf(String::isNotBlank) ?: DefaultHost,
                port = port,
                environment = environment["INPLACEX_BACKEND_ENVIRONMENT"]
                    ?.takeIf(String::isNotBlank)
                    ?: DefaultEnvironment,
                database = DatabaseRuntimeConfig.fromEnvironmentOrNull(environment),
                online = OnlineRuntimeConfig.fromEnvironmentOrNull(environment),
            )
        }

        const val DefaultHost = "0.0.0.0"
        const val DefaultPort = 8080
        const val DefaultEnvironment = "development"
    }
}
