package com.mirkori.inplacex.backend.app

import com.mirkori.inplacex.backend.ads.AdMarketRuntimeConfig

data class BackendRuntimeConfig(
    val host: String,
    val port: Int,
    val environment: String,
    val database: DatabaseRuntimeConfig? = null,
    val online: OnlineRuntimeConfig? = null,
    val adMarket: AdMarketRuntimeConfig? = null,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): BackendRuntimeConfig {
            val portValue = environment["INPLACEX_BACKEND_PORT"] ?: environment["PORT"] ?: DefaultPort.toString()
            val port = portValue.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("Backend port must be an integer in 1..65535")

            val database = DatabaseRuntimeConfig.fromEnvironmentOrNull(environment)
            val online = OnlineRuntimeConfig.fromEnvironmentOrNull(environment)
            require(database == null || online == null || online.stateEncryptionKey != null) {
                "${OnlineRuntimeConfig.StateEncryptionKey} is required when PostgreSQL online runtime is enabled"
            }
            return BackendRuntimeConfig(
                host = environment["INPLACEX_BACKEND_HOST"]?.takeIf(String::isNotBlank) ?: DefaultHost,
                port = port,
                environment = environment["INPLACEX_BACKEND_ENVIRONMENT"]
                    ?.takeIf(String::isNotBlank)
                    ?: DefaultEnvironment,
                database = database,
                online = online,
                adMarket = AdMarketRuntimeConfig.fromEnvironmentOrNull(environment),
            )
        }

        const val DefaultHost = "0.0.0.0"
        const val DefaultPort = 8080
        const val DefaultEnvironment = "development"
    }
}
