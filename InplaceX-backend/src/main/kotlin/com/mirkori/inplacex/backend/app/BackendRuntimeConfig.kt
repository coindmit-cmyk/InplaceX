package com.mirkori.inplacex.backend.app

import com.mirkori.inplacex.backend.ads.AdMarketRuntimeConfig

data class BackendRuntimeConfig(
    val host: String,
    val port: Int,
    val environment: String,
    val database: DatabaseRuntimeConfig? = null,
    val online: OnlineRuntimeConfig? = null,
    val adMarket: AdMarketRuntimeConfig? = null,
    val releaseIdentity: BackendReleaseIdentity? = null,
) {
    val isProduction: Boolean
        get() = environment == ProductionEnvironment

    init {
        require(host.length in 1..255 && host.none { it.isISOControl() || it.isWhitespace() }) {
            "Backend host has an invalid format"
        }
        require(environment.matches(EnvironmentPattern)) {
            "Backend environment has an invalid format"
        }
        if (isProduction) {
            require(
                database != null &&
                    database.isPostgres &&
                    database.hasNonEmptyPassword &&
                    database.usesExternalPassword,
            ) {
                "Production requires a PostgreSQL database with an external password"
            }
            require(
                online != null &&
                    online.stateEncryptionKey != null &&
                    online.usesExternalVerificationKey &&
                    online.usesExternalStateEncryptionKey,
            ) {
                "Production requires the durable Mirkori Platform online runtime with external key files"
            }
            require(adMarket != null) {
                "Production requires an explicit ad market source"
            }
            require(releaseIdentity != null) {
                "Production requires an immutable release identity"
            }
        }
    }

    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): BackendRuntimeConfig {
            val portValue = environment["INPLACEX_BACKEND_PORT"] ?: environment["PORT"] ?: DefaultPort.toString()
            val port = portValue.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("Backend port must be an integer in 1..65535")

            val database = DatabaseRuntimeConfig.fromEnvironmentOrNull(environment)
            val online = OnlineRuntimeConfig.fromEnvironmentOrNull(environment)
            val releaseIdentity = BackendReleaseIdentity.fromEnvironmentOrNull(environment)
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
                releaseIdentity = releaseIdentity,
            )
        }

        const val DefaultHost = "0.0.0.0"
        const val DefaultPort = 8080
        const val DefaultEnvironment = "development"
        const val ProductionEnvironment = "production"
        private val EnvironmentPattern = Regex("[a-z][a-z0-9_-]{0,31}")
    }
}

data class BackendReleaseIdentity(
    val releaseId: String,
    val gitSha: String,
    val imageDigest: String,
) {
    init {
        require(releaseId.matches(ReleaseIdPattern))
        require(gitSha.matches(GitShaPattern))
        require(imageDigest.matches(ImageDigestPattern))
    }

    companion object {
        const val ReleaseIdEnvironmentKey = "INPLACEX_RELEASE_ID"
        const val GitShaEnvironmentKey = "INPLACEX_GIT_SHA"
        const val ImageDigestEnvironmentKey = "INPLACEX_IMAGE_DIGEST"

        fun fromEnvironmentOrNull(environment: Map<String, String>): BackendReleaseIdentity? {
            val values = listOf(
                environment[ReleaseIdEnvironmentKey]?.trim()?.takeIf(String::isNotEmpty),
                environment[GitShaEnvironmentKey]?.trim()?.takeIf(String::isNotEmpty),
                environment[ImageDigestEnvironmentKey]?.trim()?.takeIf(String::isNotEmpty),
            )
            if (values.all { it == null }) return null
            require(values.all { it != null }) {
                "Release ID, Git SHA, and image digest must be configured together"
            }
            return BackendReleaseIdentity(
                releaseId = requireNotNull(values[0]),
                gitSha = requireNotNull(values[1]),
                imageDigest = requireNotNull(values[2]),
            )
        }

        private val ReleaseIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val GitShaPattern = Regex("[0-9a-f]{40}")
        private val ImageDigestPattern = Regex("sha256:[0-9a-f]{64}")
    }
}
