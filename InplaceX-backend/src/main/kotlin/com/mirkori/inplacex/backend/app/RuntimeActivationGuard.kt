package com.mirkori.inplacex.backend.app

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

internal data class BackendActivationSnapshot(
    val releaseId: String,
    val gitSha: String,
    val imageDigest: String,
    val databasePasswordSha256: String,
    val stateKeySha256: String,
    val publicKeySha256: String,
    val geoIpSha256: String,
    val runtimeConfigSha256: String,
)

/**
 * Не позволяет Docker restart policy поднять кандидат, который не был подтвержден
 * durable-записью релиза или короткой lease активного deploy/rollback процесса.
 */
internal class RuntimeActivationGuard private constructor(
    private val expectedSnapshot: BackendActivationSnapshot,
    private val verifiedStatePath: Path,
    private val pendingPermitPath: Path,
    private val clock: Clock,
) : AutoCloseable {
    private val authorized = AtomicBoolean(false)
    private var monitorThread: Thread? = null

    fun requireAuthorized() {
        check(refreshAuthorization()) {
            "Backend runtime is not covered by a verified activation state or a live deployment permit"
        }
    }

    fun isAuthorized(): Boolean = authorized.get()

    fun refreshAuthorization(): Boolean {
        val result = runCatching {
            val verifiedLines = readStateLines(verifiedStatePath)
            val verifiedMatches = when {
                verifiedLines == null -> false
                isLegacyV1VerifiedState(verifiedLines) -> false
                else -> parseState(verifiedLines, pending = false).snapshot == expectedSnapshot
            }
            verifiedMatches || readState(pendingPermitPath, pending = true)?.let { state ->
                state.snapshot == expectedSnapshot && state.expiresAtEpochSecond?.let(::leaseIsLive) == true
            } == true
        }.getOrDefault(false)
        authorized.set(result)
        return result
    }

    fun startMonitor() {
        check(monitorThread == null) { "Runtime activation monitor is already running" }
        monitorThread = thread(
            start = true,
            isDaemon = true,
            name = "inplacex-runtime-activation-monitor",
        ) {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(MonitorIntervalMillis)
                } catch (_: InterruptedException) {
                    return@thread
                }
                if (!refreshAuthorization()) {
                    System.err.println("InplaceX activation authorization expired; terminating fail-closed")
                    exitProcess(ActivationFailureExitCode)
                }
            }
        }
    }

    override fun close() {
        monitorThread?.interrupt()
        monitorThread = null
    }

    private fun leaseIsLive(expiresAtEpochSecond: Long): Boolean {
        val now = clock.instant().epochSecond
        return expiresAtEpochSecond in now..(now + MaximumLeaseFutureSeconds)
    }

    private fun readState(path: Path, pending: Boolean): ActivationState? {
        val lines = readStateLines(path) ?: return null
        return parseState(lines, pending)
    }

    private fun readStateLines(path: Path): List<String>? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        require(path.isAbsolute) { "Activation state path must be absolute" }
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Activation state must be a regular non-symlink file"
        }
        require(Files.size(path) in 1..MaximumStateBytes) { "Activation state size is invalid" }
        return Files.readAllLines(path, Charsets.UTF_8)
    }

    private fun parseState(lines: List<String>, pending: Boolean): ActivationState {
        val entries = linkedMapOf<String, String>()
        lines.forEach { line ->
            require(line.isNotEmpty() && !line.startsWith("#") && line.count { it == '=' } == 1) {
                "Activation state line is invalid"
            }
            val key = line.substringBefore('=')
            val value = line.substringAfter('=')
            require(key in CommonFields || pending && key == ExpiresField) { "Unknown activation state field" }
            require(entries.put(key, value) == null) { "Duplicate activation state field" }
        }
        val expectedFields = if (pending) CommonFields + ExpiresField else CommonFields
        require(entries.keys.toList() == expectedFields.toList()) {
            "Activation state fields are incomplete or out of order"
        }
        require(entries.getValue(VersionField) == StateVersion) { "Unsupported activation state version" }
        val snapshot = BackendActivationSnapshot(
            releaseId = entries.getValue(ReleaseIdField),
            gitSha = entries.getValue(GitShaField),
            imageDigest = entries.getValue(ImageDigestField),
            databasePasswordSha256 = entries.getValue(DatabasePasswordShaField),
            stateKeySha256 = entries.getValue(StateKeyShaField),
            publicKeySha256 = entries.getValue(PublicKeyShaField),
            geoIpSha256 = entries.getValue(GeoIpShaField),
            runtimeConfigSha256 = entries.getValue(RuntimeConfigShaField),
        )
        require(ReleaseIdPattern.matches(snapshot.releaseId)) { "Activation release id is invalid" }
        require(GitShaPattern.matches(snapshot.gitSha)) { "Activation Git SHA is invalid" }
        require(ImageDigestPattern.matches(snapshot.imageDigest)) { "Activation image digest is invalid" }
        require(
            listOf(
                snapshot.databasePasswordSha256,
                snapshot.stateKeySha256,
                snapshot.publicKeySha256,
                snapshot.geoIpSha256,
                snapshot.runtimeConfigSha256,
            ).all(Sha256Pattern::matches),
        ) { "Activation fingerprint is invalid" }
        val expiresAt = entries[ExpiresField]?.toLongOrNull()
        require(!pending || expiresAt != null) { "Activation permit expiry is invalid" }
        return ActivationState(snapshot, expiresAt)
    }

    private fun isLegacyV1VerifiedState(lines: List<String>): Boolean {
        if (lines.firstOrNull() != "$VersionField=$LegacyStateVersion") return false
        val entries = linkedMapOf<String, String>()
        lines.forEach { line ->
            require(line.isNotEmpty() && !line.startsWith("#") && line.count { it == '=' } == 1) {
                "Legacy activation state line is invalid"
            }
            val key = line.substringBefore('=')
            val value = line.substringAfter('=')
            require(key in LegacyCommonFields) { "Unknown legacy activation state field" }
            require(entries.put(key, value) == null) { "Duplicate legacy activation state field" }
        }
        require(entries.keys.toList() == LegacyCommonFields.toList()) {
            "Legacy activation state fields are incomplete or out of order"
        }
        require(ReleaseIdPattern.matches(entries.getValue(ReleaseIdField))) {
            "Legacy activation release id is invalid"
        }
        require(GitShaPattern.matches(entries.getValue(GitShaField))) {
            "Legacy activation Git SHA is invalid"
        }
        require(ImageDigestPattern.matches(entries.getValue(ImageDigestField))) {
            "Legacy activation image digest is invalid"
        }
        require(
            listOf(
                entries.getValue(StateKeyShaField),
                entries.getValue(PublicKeyShaField),
                entries.getValue(GeoIpShaField),
                entries.getValue(RuntimeConfigShaField),
            ).all(Sha256Pattern::matches),
        ) { "Legacy activation fingerprint is invalid" }
        return true
    }

    private data class ActivationState(
        val snapshot: BackendActivationSnapshot,
        val expiresAtEpochSecond: Long?,
    )

    companion object {
        const val VerifiedStatePathEnvironmentKey = "INPLACEX_VERIFIED_ACTIVATION_STATE_PATH"
        const val PendingPermitPathEnvironmentKey = "INPLACEX_PENDING_ACTIVATION_PERMIT_PATH"
        const val GeoIpFingerprintPathEnvironmentKey = "INPLACEX_ACTIVATION_GEOIP_FINGERPRINT_PATH"
        const val RuntimeConfigShaEnvironmentKey = "INPLACEX_RUNTIME_CONFIG_SHA256"

        private const val ActivationFailureExitCode = 78
        private const val MonitorIntervalMillis = 250L
        private const val MaximumLeaseFutureSeconds = 30L
        private const val MaximumStateBytes = 8L * 1024L
        private const val StateVersion = "2"
        private const val LegacyStateVersion = "1"
        private const val VersionField = "INPLACEX_ACTIVATION_VERSION"
        private const val ReleaseIdField = "INPLACEX_ACTIVATION_RELEASE_ID"
        private const val GitShaField = "INPLACEX_ACTIVATION_GIT_SHA"
        private const val ImageDigestField = "INPLACEX_ACTIVATION_IMAGE_DIGEST"
        private const val DatabasePasswordShaField = "INPLACEX_ACTIVATION_DATABASE_PASSWORD_SHA256"
        private const val StateKeyShaField = "INPLACEX_ACTIVATION_STATE_KEY_SHA256"
        private const val PublicKeyShaField = "INPLACEX_ACTIVATION_PUBLIC_KEY_SHA256"
        private const val GeoIpShaField = "INPLACEX_ACTIVATION_GEOIP_SHA256"
        private const val RuntimeConfigShaField = "INPLACEX_ACTIVATION_RUNTIME_CONFIG_SHA256"
        private const val ExpiresField = "INPLACEX_ACTIVATION_EXPIRES_AT_EPOCH_SECOND"
        private val CommonFields = linkedSetOf(
            VersionField,
            ReleaseIdField,
            GitShaField,
            ImageDigestField,
            DatabasePasswordShaField,
            StateKeyShaField,
            PublicKeyShaField,
            GeoIpShaField,
            RuntimeConfigShaField,
        )
        private val LegacyCommonFields = linkedSetOf(
            VersionField,
            ReleaseIdField,
            GitShaField,
            ImageDigestField,
            StateKeyShaField,
            PublicKeyShaField,
            GeoIpShaField,
            RuntimeConfigShaField,
        )
        private val ReleaseIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val GitShaPattern = Regex("[0-9a-f]{40}")
        private val ImageDigestPattern = Regex("sha256:[0-9a-f]{64}")
        private val Sha256Pattern = Regex("[0-9a-f]{64}")

        fun fromEnvironment(
            environment: Map<String, String>,
            identity: BackendReleaseIdentity,
            clock: Clock = Clock.systemUTC(),
        ): RuntimeActivationGuard {
            fun requiredPath(key: String): Path {
                val value = environment[key]?.trim().orEmpty()
                require(value.isNotEmpty()) { "$key is required in production" }
                return Path.of(value).also { require(it.isAbsolute) { "$key must be absolute" } }
            }

            val stateKeyPath = requiredPath(OnlineRuntimeConfig.StateEncryptionKeyPath)
            val publicKeyPath = requiredPath(OnlineRuntimeConfig.PublicKeyPathKey)
            val databasePasswordPath = requiredPath(DatabaseRuntimeConfig.PasswordPathEnvironmentKey)
            val geoIpPath = requiredPath(GeoIpFingerprintPathEnvironmentKey)
            val runtimeConfigSha = environment[RuntimeConfigShaEnvironmentKey]?.trim().orEmpty()
            require(Sha256Pattern.matches(runtimeConfigSha)) {
                "$RuntimeConfigShaEnvironmentKey must be a lowercase SHA-256"
            }
            return RuntimeActivationGuard(
                expectedSnapshot = BackendActivationSnapshot(
                    releaseId = identity.releaseId,
                    gitSha = identity.gitSha,
                    imageDigest = identity.imageDigest,
                    databasePasswordSha256 = sha256(databasePasswordPath),
                    stateKeySha256 = sha256(stateKeyPath),
                    publicKeySha256 = sha256(publicKeyPath),
                    geoIpSha256 = sha256(geoIpPath),
                    runtimeConfigSha256 = runtimeConfigSha,
                ),
                verifiedStatePath = requiredPath(VerifiedStatePathEnvironmentKey),
                pendingPermitPath = requiredPath(PendingPermitPathEnvironmentKey),
                clock = clock,
            )
        }

        private fun sha256(path: Path): String {
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                "Activation fingerprint input must be a regular non-symlink file"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}
