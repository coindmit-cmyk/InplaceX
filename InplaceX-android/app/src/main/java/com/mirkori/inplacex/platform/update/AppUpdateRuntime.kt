package com.mirkori.inplacex.platform.update

import com.mirkori.inplacex.platform.logging.AppLog
import com.mirkori.platform.sdk.PlatformGameUpdateDecision
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class AppUpdateRelease(
    val id: String,
    val versionName: String,
    val versionCode: Long,
    val minimumSupportedVersionCode: Long,
    val minimumAndroidSdk: Int,
    val publishedAt: Instant,
    val changelog: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val downloadUrl: String,
    val packageName: String,
    val signingCertificateSha256Fingerprints: List<String>,
)

internal data class AppUpdateCatalogDecision(
    val updateAvailable: Boolean,
    val required: Boolean,
    val release: AppUpdateRelease?,
)

internal fun interface StableAndroidUpdateCatalog {
    suspend fun check(installedVersionCode: Long): AppUpdateCatalogDecision
}

internal data class DownloadedAppUpdateArtifact(
    val cacheFileName: String,
    val file: File,
)

internal data class VerifiedAppUpdateArtifact(
    val releaseId: String,
    val cacheFileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minimumAndroidSdk: Int,
    val debuggable: Boolean,
    val currentSignerSha256Fingerprint: String,
    val orderedSignerLineage: List<String>,
)

internal data class AppUpdateSigningIdentity(
    val currentSigner: String,
    val orderedLineage: List<String>,
)

internal fun normalizedSigningIdentity(
    currentSigners: List<String>,
    rawHistory: List<String>,
): AppUpdateSigningIdentity {
    require(currentSigners.size == 1)
    val current = currentSigners.single()
    val ordered = rawHistory.ifEmpty { listOf(current) }
    require(ordered.last() == current) { "Signer history is not oldest-to-current" }
    require(ordered.isNotEmpty() && ordered.distinct().size == ordered.size)
    return AppUpdateSigningIdentity(current, ordered)
}

internal data class ParsedAppUpdatePackageIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minimumAndroidSdk: Int,
    val debuggable: Boolean,
    val currentSigners: List<String>,
    val rawSignerHistory: List<String>,
)

internal fun verifyAppUpdatePackageIdentity(
    release: AppUpdateRelease,
    artifact: DownloadedAppUpdateArtifact,
    artifactSizeBytes: Long,
    artifactSha256: String,
    archive: ParsedAppUpdatePackageIdentity,
    installed: ParsedAppUpdatePackageIdentity,
): VerifiedAppUpdateArtifact {
    val archiveSigning = normalizedSigningIdentity(archive.currentSigners, archive.rawSignerHistory)
    val installedSigning = normalizedSigningIdentity(installed.currentSigners, installed.rawSignerHistory)
    require(artifactSizeBytes == release.sizeBytes)
    require(artifactSha256 == release.sha256)
    require(archive.packageName == release.packageName && archive.packageName == installed.packageName)
    require(archive.versionCode == release.versionCode && archive.versionCode > installed.versionCode)
    require(archive.versionName == release.versionName)
    require(archive.minimumAndroidSdk == release.minimumAndroidSdk)
    require(!archive.debuggable)
    require(archiveSigning.orderedLineage == release.signingCertificateSha256Fingerprints)
    require(archiveSigning.currentSigner == release.signingCertificateSha256Fingerprints.lastOrNull())
    require(installedSigning.orderedLineage.size <= archiveSigning.orderedLineage.size)
    require(
        archiveSigning.orderedLineage.take(installedSigning.orderedLineage.size) ==
            installedSigning.orderedLineage,
    )
    require(installedSigning.currentSigner == installedSigning.orderedLineage.last())
    return VerifiedAppUpdateArtifact(
        releaseId = release.id,
        cacheFileName = artifact.cacheFileName,
        sizeBytes = artifactSizeBytes,
        sha256 = artifactSha256,
        packageName = archive.packageName,
        versionName = archive.versionName,
        versionCode = archive.versionCode,
        minimumAndroidSdk = archive.minimumAndroidSdk,
        debuggable = false,
        currentSignerSha256Fingerprint = archiveSigning.currentSigner,
        orderedSignerLineage = archiveSigning.orderedLineage,
    )
}

internal interface AppUpdateArtifactCache {
    suspend fun download(release: AppUpdateRelease): DownloadedAppUpdateArtifact

    fun resolve(cacheFileName: String): File?

    fun clear()
}

internal fun interface AppUpdateArtifactVerifier {
    fun verify(release: AppUpdateRelease, artifact: DownloadedAppUpdateArtifact): VerifiedAppUpdateArtifact
}

internal sealed interface AppUpdateState {
    data object Checking : AppUpdateState

    data object Current : AppUpdateState

    data class Optional(val release: AppUpdateRelease) : AppUpdateState

    data class Required(val release: AppUpdateRelease) : AppUpdateState

    data class Downloading(val release: AppUpdateRelease, val required: Boolean) : AppUpdateState

    data class Verifying(val release: AppUpdateRelease, val required: Boolean) : AppUpdateState

    data class Refreshing(
        val release: AppUpdateRelease,
        val required: Boolean,
        val artifact: VerifiedAppUpdateArtifact?,
    ) : AppUpdateState

    data class NeedsPermission(
        val release: AppUpdateRelease,
        val required: Boolean,
        val artifact: VerifiedAppUpdateArtifact,
    ) : AppUpdateState

    data class Ready(
        val release: AppUpdateRelease,
        val required: Boolean,
        val artifact: VerifiedAppUpdateArtifact,
    ) : AppUpdateState

    data class HandingOff(
        val release: AppUpdateRelease,
        val required: Boolean,
        val artifact: VerifiedAppUpdateArtifact,
    ) : AppUpdateState

    data class Unsupported(val release: AppUpdateRelease, val required: Boolean) : AppUpdateState

    data class RecoverableError(
        val release: AppUpdateRelease?,
        val required: Boolean,
        val artifact: VerifiedAppUpdateArtifact? = null,
    ) : AppUpdateState

    data object PolicyRecovery : AppUpdateState
}

internal data class AppUpdateInstallClaim(
    val id: Long,
    val needsPermission: Boolean,
    val artifact: VerifiedAppUpdateArtifact,
)

internal data class AppUpdatePolicyState(
    val nextCheckAfterMs: Long = 0L,
    val dismissedReleaseId: String? = null,
    val dismissedUntilMs: Long = 0L,
    val requiredRelease: AppUpdateRelease? = null,
    val cachedArtifact: VerifiedAppUpdateArtifact? = null,
    val policyRecoveryRequired: Boolean = false,
)

internal sealed interface AppUpdatePolicyReadResult {
    data object Missing : AppUpdatePolicyReadResult

    data class Available(val state: AppUpdatePolicyState) : AppUpdatePolicyReadResult

    data object Invalid : AppUpdatePolicyReadResult
}

internal interface AppUpdatePolicyStore {
    fun read(): AppUpdatePolicyReadResult

    fun write(state: AppUpdatePolicyState): Boolean
}

internal class AppUpdateOriginPolicy(
    platformBaseUrl: String,
    allowCleartextLoopback: Boolean,
) {
    private val baseUri = validatedOrigin(platformBaseUrl, allowCleartextLoopback)

    fun accepts(release: AppUpdateRelease): Boolean {
        val uri = runCatching { URI(release.downloadUrl) }.getOrNull() ?: return false
        val expectedPath = "/downloads/${urlEncode(release.id)}/${urlEncode(release.fileName)}"
        return sameOrigin(uri, baseUri) &&
            uri.rawPath == expectedPath &&
            uri.rawQuery == null &&
            uri.fragment == null &&
            uri.userInfo == null
    }

    private fun validatedOrigin(value: String, allowCleartextLoopback: Boolean): URI {
        val uri = URI(value)
        val secure = uri.scheme.equals("https", ignoreCase = true)
        val loopback = uri.scheme.equals("http", ignoreCase = true) &&
            uri.host?.lowercase() in LoopbackHosts
        require(
            uri.host != null &&
                uri.userInfo == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
                uri.rawQuery == null &&
                uri.fragment == null &&
                (secure || allowCleartextLoopback && loopback) &&
                effectivePort(uri) in 1..65_535
        )
        return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, null, null, null)
    }
}

internal class AppUpdateRuntime(
    private val catalog: StableAndroidUpdateCatalog,
    private val store: AppUpdatePolicyStore,
    private val originPolicy: AppUpdateOriginPolicy,
    private val artifactCache: AppUpdateArtifactCache,
    private val artifactVerifier: AppUpdateArtifactVerifier,
    private val installedVersionCode: Long,
    private val installedAndroidSdk: Int,
    private val expectedPackageName: String,
    private val canRequestPackageInstalls: () -> Boolean,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val closeAction: () -> Unit = {},
) : AutoCloseable {
    private val operationMutex = Mutex()
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(initialState())
    private var activeArtifact: VerifiedAppUpdateArtifact? = null
    private var activeInstallClaimId: Long? = null
    private var nextInstallClaimId = 1L

    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    init {
        require(installedVersionCode > 0)
        require(installedAndroidSdk in 21..100)
        require(expectedPackageName.matches(AndroidPackageNamePattern))
    }

    suspend fun refresh() = operationMutex.withLock {
        val now = clockMs().coerceAtLeast(0L)
        var stateBeforeRefresh = mutableState.value
        var snapshot = readNormalized(now)
        var before = snapshot.state
        snapshot.installedRequiredPolicy?.let { installedPolicy ->
            val cleared = before.withoutRequiredUpdate().copy(policyRecoveryRequired = false)
            if (
                !prepareAuthoritativeCacheTransition(
                    finalState = cleared,
                    recoveryMarkerBase = installedPolicy,
                )
            ) {
                return@withLock
            }
            if (!persist(cleared)) {
                mutableState.value = AppUpdateState.PolicyRecovery
                return@withLock
            }
            stateBeforeRefresh = AppUpdateState.Current
            mutableState.value = stateBeforeRefresh
            snapshot = readNormalized(now)
            before = snapshot.state
        }

        if (before.nextCheckAfterMs > now) {
            publishStoredGateOrCurrent(before, snapshot.recoveryRequired)
            return@withLock
        }

        val stateBeforeRefreshGuard = before
        var refreshGuardWritten = false
        if (!snapshot.recoveryRequired) {
            val guarded = before.copy(
                nextCheckAfterMs = 0L,
                policyRecoveryRequired = true,
            )
            if (!persist(guarded)) {
                activeArtifact = null
                mutableState.value = AppUpdateState.PolicyRecovery
                return@withLock
            }
            before = guarded
            snapshot = NormalizedPolicySnapshot(guarded, recoveryRequired = true)
            refreshGuardWritten = true
        }

        if (snapshot.recoveryRequired && !refreshGuardWritten) {
            mutableState.value = AppUpdateState.PolicyRecovery
        } else if (stateBeforeRefresh.releaseOrNull() == null) {
            mutableState.value = AppUpdateState.Checking
        } else {
            mutableState.value = AppUpdateState.Refreshing(
                release = requireNotNull(stateBeforeRefresh.releaseOrNull()),
                required = stateBeforeRefresh.isRequired(),
                artifact = stateBeforeRefresh.artifactOrNull(),
            )
        }
        val decision = try {
            catalog.check(installedVersionCode).also(::validateDecision)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (refreshGuardWritten) {
                val restored = stateBeforeRefreshGuard.copy(
                    nextCheckAfterMs = safeDeadline(now, RetryCooldownMs),
                    policyRecoveryRequired = false,
                )
                if (persist(restored)) {
                    publishRecoverableFailure(restored, stateBeforeRefresh)
                } else {
                    mutableState.value = AppUpdateState.PolicyRecovery
                }
            } else if (snapshot.recoveryRequired) {
                mutableState.value = AppUpdateState.PolicyRecovery
            } else {
                persist(before.copy(nextCheckAfterMs = safeDeadline(now, RetryCooldownMs)))
                publishRecoverableFailure(before, stateBeforeRefresh)
            }
            logFailure("App update check is unavailable", error)
            return@withLock
        }

        if (!decision.updateAvailable) {
            val cleared = before.withoutRequiredUpdate().copy(
                nextCheckAfterMs = safeDeadline(now, CurrentVersionCooldownMs),
                dismissedReleaseId = before.dismissedReleaseId.takeIf { before.dismissedUntilMs > now },
                dismissedUntilMs = before.dismissedUntilMs.takeIf { it > now } ?: 0L,
                policyRecoveryRequired = false,
            )
            if (!prepareAuthoritativeCacheTransition(cleared)) return@withLock
            if (!persist(cleared)) {
                mutableState.value = AppUpdateState.PolicyRecovery
                return@withLock
            }
            mutableState.value = AppUpdateState.Current
            return@withLock
        }

        val release = requireNotNull(decision.release)
        val compatible = release.minimumAndroidSdk <= installedAndroidSdk
        if (decision.required) {
            val sameRelease = before.requiredRelease == release
            val durable = before.copy(
                nextCheckAfterMs = 0L,
                dismissedReleaseId = null,
                dismissedUntilMs = 0L,
                requiredRelease = release,
                cachedArtifact = before.cachedArtifact.takeIf { sameRelease && !snapshot.recoveryRequired },
                policyRecoveryRequired = false,
            )
            val needsCacheTransition = !sameRelease || snapshot.recoveryRequired
            if (needsCacheTransition && !prepareAuthoritativeCacheTransition(durable)) return@withLock
            if (!persist(durable)) {
                mutableState.value = if (snapshot.recoveryRequired || needsCacheTransition) {
                    AppUpdateState.PolicyRecovery
                } else {
                    AppUpdateState.RecoverableError(release, required = true)
                }
                return@withLock
            }
            if (!sameRelease) {
                mutableState.value = if (compatible) {
                    AppUpdateState.Required(release)
                } else {
                    AppUpdateState.Unsupported(release, required = true)
                }
            }
            if (!compatible) {
                mutableState.value = AppUpdateState.Unsupported(release, required = true)
                return@withLock
            }
            publishReleaseWithCachedArtifact(release, required = true, readNormalized(now).state)
            return@withLock
        }

        val optionalState = before.withoutRequiredUpdate().copy(
            nextCheckAfterMs = 0L,
            policyRecoveryRequired = false,
        )
        val needsCacheTransition = snapshot.recoveryRequired ||
            before.requiredRelease != null ||
            activeArtifact?.releaseId?.let { it != release.id } == true
        if (needsCacheTransition && !prepareAuthoritativeCacheTransition(optionalState)) return@withLock
        if (!persist(optionalState)) {
            if (snapshot.recoveryRequired || needsCacheTransition) {
                mutableState.value = AppUpdateState.PolicyRecovery
            } else {
                publishRecoverableFailure(before, stateBeforeRefresh)
            }
            return@withLock
        }
        if (before.requiredRelease != null) {
            mutableState.value = if (compatible) {
                AppUpdateState.Optional(release)
            } else {
                AppUpdateState.Unsupported(release, required = false)
            }
        }
        if (!compatible) {
            mutableState.value = AppUpdateState.Unsupported(release, required = false)
        } else if (
            optionalState.dismissedReleaseId == release.id && optionalState.dismissedUntilMs > now
        ) {
            mutableState.value = AppUpdateState.Current
        } else {
            publishReleaseWithCachedArtifact(release, required = false, optionalState)
        }
    }

    suspend fun prepareInstall() = operationMutex.withLock {
        val current = mutableState.value
        val (release, required) = current.downloadTarget() ?: return@withLock
        mutableState.value = AppUpdateState.Downloading(release, required)
        try {
            val downloaded = artifactCache.download(release)
            mutableState.value = AppUpdateState.Verifying(release, required)
            val verified = artifactVerifier.verify(release, downloaded)
            activeArtifact = verified
            if (required) {
                val now = clockMs().coerceAtLeast(0L)
                val durable = readNormalized(now).state.copy(
                    nextCheckAfterMs = 0L,
                    requiredRelease = release,
                    cachedArtifact = verified,
                )
                if (!persist(durable)) {
                    activeArtifact = null
                    mutableState.value = AppUpdateState.RecoverableError(release, required = true)
                    return@withLock
                }
            }
            publishReadyState(release, required, verified)
        } catch (cancelled: CancellationException) {
            mutableState.value = if (required) {
                AppUpdateState.Required(release)
            } else {
                AppUpdateState.Optional(release)
            }
            throw cancelled
        } catch (error: Exception) {
            activeArtifact = null
            mutableState.value = AppUpdateState.RecoverableError(release, required)
            clearCacheSafely(release, required)
            logFailure("App update artifact could not be prepared", error)
        }
    }

    suspend fun performInstallAction(
        launch: suspend (AppUpdateInstallClaim) -> Unit,
    ): Boolean = operationMutex.withLock {
        val current = mutableState.value
        val release = current.releaseOrNull() ?: return@withLock false
        val candidate = current.artifactOrNull() ?: return@withLock false
        if (current !is AppUpdateState.NeedsPermission &&
            current !is AppUpdateState.Ready &&
            current !is AppUpdateState.RecoverableError
        ) {
            return@withLock false
        }
        if (activeInstallClaimId != null) return@withLock false
        val required = current.isRequired()
        if (required) {
            val stored = readNormalized(clockMs().coerceAtLeast(0L))
            if (stored.recoveryRequired ||
                stored.state.requiredRelease != release ||
                stored.state.cachedArtifact != candidate
            ) {
                activeArtifact = null
                mutableState.value = AppUpdateState.PolicyRecovery
                clearCacheSafely(policyRecoveryOnFailure = true)
                return@withLock false
            }
        } else if (activeArtifact != candidate) {
            mutableState.value = AppUpdateState.RecoverableError(release, required = false)
            clearCacheSafely(release, required = false)
            return@withLock false
        }
        val verified = restoreExactArtifact(release, candidate)
        if (verified == null) {
            activeArtifact = null
            if (required) {
                val stored = readNormalized(clockMs().coerceAtLeast(0L)).state
                persist(stored.copy(cachedArtifact = null))
            }
            mutableState.value = AppUpdateState.RecoverableError(release, required)
            clearCacheSafely(release, required)
            return@withLock false
        }
        val claimId = nextInstallClaimId++
        activeInstallClaimId = claimId
        mutableState.value = AppUpdateState.HandingOff(release, required, verified)
        val claim = AppUpdateInstallClaim(
            id = claimId,
            needsPermission = !canRequestPackageInstalls(),
            artifact = verified,
        )
        var launched = false
        try {
            launch(claim)
            launched = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logFailure("App update installer hand-off failed", error)
        } finally {
            activeInstallClaimId = null
            if (!launched) {
                mutableState.value = AppUpdateState.RecoverableError(
                    release = release,
                    required = required,
                    artifact = verified,
                )
            }
        }
        launched
    }

    suspend fun refreshInstallPermission() = operationMutex.withLock {
        if (activeInstallClaimId != null) return@withLock
        val current = mutableState.value
        if (current !is AppUpdateState.HandingOff &&
            current !is AppUpdateState.NeedsPermission &&
            current !is AppUpdateState.Ready &&
            current !is AppUpdateState.RecoverableError
        ) {
            return@withLock
        }
        val release = current.releaseOrNull() ?: return@withLock
        val artifact = current.artifactOrNull() ?: return@withLock
        publishReadyState(release, current.isRequired(), artifact)
    }

    suspend fun dismissOptional(releaseId: String) = operationMutex.withLock {
        require(releaseId.matches(ReleaseIdPattern))
        val current = mutableState.value
        val release = current.releaseOrNull()
        if (current.isRequired() || release?.id != releaseId) return@withLock
        val now = clockMs().coerceAtLeast(0L)
        val before = readNormalized(now).state
        if (
            persist(
                before.withoutRequiredUpdate().copy(
                    dismissedReleaseId = releaseId,
                    dismissedUntilMs = safeDeadline(now, OptionalDismissalMs),
                ),
            )
        ) {
            activeArtifact = null
            mutableState.value = AppUpdateState.Current
            clearCacheSafely(release, required = false)
        } else {
            mutableState.value = AppUpdateState.RecoverableError(release, required = false)
        }
    }

    override fun close() {
        closeAction()
    }

    private fun initialState(): AppUpdateState {
        val now = clockMs().coerceAtLeast(0L)
        val snapshot = readNormalized(now)
        if (snapshot.recoveryRequired || snapshot.installedRequiredPolicy != null) {
            return AppUpdateState.PolicyRecovery
        }
        val required = snapshot.state.requiredRelease
            ?.takeIf { it.versionCode > installedVersionCode }
        return if (required == null) AppUpdateState.Checking else AppUpdateState.Required(required)
    }

    private fun validateDecision(decision: AppUpdateCatalogDecision) {
        if (!decision.updateAvailable) {
            require(!decision.required && decision.release == null)
            return
        }
        validateRelease(requireNotNull(decision.release))
        require(decision.release.versionCode > installedVersionCode)
        require(decision.required == (installedVersionCode < decision.release.minimumSupportedVersionCode))
    }

    private fun validateRelease(release: AppUpdateRelease) {
        require(release.id.matches(ReleaseIdPattern))
        require(release.versionName.length in 1..64 && release.versionName.trim() == release.versionName)
        require(release.versionCode > 0)
        require(release.minimumSupportedVersionCode in 1..release.versionCode)
        require(release.minimumAndroidSdk in 21..100)
        require(release.fileName.matches(ArtifactFileNamePattern) && release.fileName.endsWith(".apk", true))
        require(release.sizeBytes in 1..MaximumArtifactBytes)
        require(release.sha256.matches(Sha256Pattern))
        require(release.packageName == expectedPackageName)
        require(release.signingCertificateSha256Fingerprints.isNotEmpty())
        require(
            release.signingCertificateSha256Fingerprints.distinct().size ==
                release.signingCertificateSha256Fingerprints.size,
        )
        require(release.signingCertificateSha256Fingerprints.all { it.matches(CertificateFingerprintPattern) })
        require(originPolicy.accepts(release))
    }

    private fun readNormalized(now: Long): NormalizedPolicySnapshot = synchronized(stateLock) {
        when (val result = store.read()) {
            AppUpdatePolicyReadResult.Missing -> NormalizedPolicySnapshot(AppUpdatePolicyState(), false)
            AppUpdatePolicyReadResult.Invalid -> AppUpdatePolicyState(policyRecoveryRequired = true).let { marker ->
                runCatching { store.write(marker) }
                NormalizedPolicySnapshot(marker, true)
            }
            is AppUpdatePolicyReadResult.Available -> result.state.normalized(now).also { snapshot ->
                if (snapshot.recoveryRequired && !result.state.policyRecoveryRequired) {
                    runCatching { store.write(result.state.copy(policyRecoveryRequired = true)) }
                }
            }
        }
    }

    private fun persist(state: AppUpdatePolicyState): Boolean = synchronized(stateLock) {
        runCatching { store.write(state) }.getOrDefault(false)
    }.also { stored ->
        if (!stored) AppLog.warn(LogTag, "App update policy state was not persisted")
    }

    private fun publishStoredGateOrCurrent(stored: AppUpdatePolicyState, recoveryRequired: Boolean) {
        if (recoveryRequired) {
            mutableState.value = AppUpdateState.PolicyRecovery
            return
        }
        val required = stored.requiredRelease
        if (required == null) {
            mutableState.value = AppUpdateState.Current
            return
        }
        if (required.minimumAndroidSdk > installedAndroidSdk) {
            mutableState.value = AppUpdateState.Unsupported(required, required = true)
            return
        }
        publishReleaseWithCachedArtifact(required, required = true, stored)
    }

    private fun publishReleaseWithCachedArtifact(
        release: AppUpdateRelease,
        required: Boolean,
        stored: AppUpdatePolicyState,
    ) {
        val candidate = activeArtifact?.takeIf { it.releaseId == release.id }
            ?: stored.cachedArtifact?.takeIf { it.releaseId == release.id }
        if (candidate != null) {
            val restored = runCatching {
                val file = requireNotNull(artifactCache.resolve(candidate.cacheFileName))
                artifactVerifier.verify(release, DownloadedAppUpdateArtifact(candidate.cacheFileName, file))
            }.getOrNull()
            if (restored == candidate) {
                activeArtifact = restored
                publishReadyState(release, required, restored)
                return
            }
            activeArtifact = null
            mutableState.value = if (required) AppUpdateState.Required(release) else AppUpdateState.Optional(release)
            if (required) persist(stored.copy(cachedArtifact = null))
            if (!clearCacheSafely(release, required)) return
        }
        mutableState.value = if (required) AppUpdateState.Required(release) else AppUpdateState.Optional(release)
    }

    private fun publishReadyState(
        release: AppUpdateRelease,
        required: Boolean,
        artifact: VerifiedAppUpdateArtifact,
    ) {
        mutableState.value = if (canRequestPackageInstalls()) {
            AppUpdateState.Ready(release, required, artifact)
        } else {
            AppUpdateState.NeedsPermission(release, required, artifact)
        }
    }

    private fun publishRecoverableFailure(
        stored: AppUpdatePolicyState,
        fallback: AppUpdateState = mutableState.value,
    ) {
        if (fallback is AppUpdateState.PolicyRecovery) {
            mutableState.value = AppUpdateState.PolicyRecovery
            return
        }
        val release = stored.requiredRelease
        if (release != null) {
            val artifact = restoredArtifactOrNull(release, stored)
            mutableState.value = AppUpdateState.RecoverableError(release, required = true, artifact = artifact)
        } else {
            mutableState.value = AppUpdateState.RecoverableError(
                release = fallback.releaseOrNull(),
                required = fallback.isRequired(),
                artifact = fallback.artifactOrNull(),
            )
        }
    }

    private fun restoredArtifactOrNull(
        release: AppUpdateRelease,
        stored: AppUpdatePolicyState,
    ): VerifiedAppUpdateArtifact? {
        val candidate = activeArtifact?.takeIf { it.releaseId == release.id }
            ?: stored.cachedArtifact?.takeIf { it.releaseId == release.id }
            ?: return null
        val restored = runCatching {
            val file = requireNotNull(artifactCache.resolve(candidate.cacheFileName))
            artifactVerifier.verify(release, DownloadedAppUpdateArtifact(candidate.cacheFileName, file))
        }.getOrNull()
        return restored.takeIf { it == candidate }?.also { activeArtifact = it }
    }

    private fun restoreExactArtifact(
        release: AppUpdateRelease,
        candidate: VerifiedAppUpdateArtifact,
    ): VerifiedAppUpdateArtifact? = runCatching {
        val file = requireNotNull(artifactCache.resolve(candidate.cacheFileName))
        artifactVerifier.verify(release, DownloadedAppUpdateArtifact(candidate.cacheFileName, file))
    }.getOrNull()?.takeIf { it == candidate }

    private fun prepareAuthoritativeCacheTransition(
        finalState: AppUpdatePolicyState,
        recoveryMarkerBase: AppUpdatePolicyState = finalState,
    ): Boolean {
        activeArtifact = null
        mutableState.value = AppUpdateState.PolicyRecovery
        val recoveryMarker = recoveryMarkerBase.copy(
            nextCheckAfterMs = 0L,
            cachedArtifact = null,
            policyRecoveryRequired = true,
        )
        val markerPersisted = persist(recoveryMarker)
        val cacheCleared = clearCacheSafely(policyRecoveryOnFailure = true)
        return markerPersisted && cacheCleared
    }

    private fun clearCacheSafely(
        release: AppUpdateRelease? = null,
        required: Boolean = false,
        policyRecoveryOnFailure: Boolean = false,
    ): Boolean = try {
        artifactCache.clear()
        true
    } catch (error: Exception) {
        if (policyRecoveryOnFailure) {
            mutableState.value = AppUpdateState.PolicyRecovery
        } else if (release != null) {
            mutableState.value = AppUpdateState.RecoverableError(release, required)
        }
        logFailure("App update cache could not be cleared", error)
        false
    }

    private fun logFailure(message: String, error: Exception) {
        AppLog.warn(
            tag = LogTag,
            message = message,
            attributes = mapOf("errorClass" to error.javaClass.name),
        )
    }

    private fun AppUpdatePolicyState.normalized(now: Long): NormalizedPolicySnapshot {
        val next = nextCheckAfterMs.takeIf { it in 1..safeDeadline(now, MaximumAcceptedCooldownMs) } ?: 0L
        val validDismissal = dismissedReleaseId?.takeIf { it.matches(ReleaseIdPattern) }
        val until = dismissedUntilMs.takeIf {
            validDismissal != null && it in 1..safeDeadline(now, MaximumAcceptedCooldownMs)
        } ?: 0L
        val requiredIsInstalled = requiredRelease?.versionCode?.let { it <= installedVersionCode } == true
        val requiredIsValid = requiredRelease?.let { release ->
            runCatching { validateRelease(release) }.isSuccess
        } ?: true
        val required = requiredRelease?.takeIf { requiredIsValid && !requiredIsInstalled }
        val cached = cachedArtifact?.takeIf { artifact ->
            required != null && artifact.releaseId == required.id && artifact.matches(required)
        }
        return NormalizedPolicySnapshot(
            state = AppUpdatePolicyState(
                nextCheckAfterMs = next,
                dismissedReleaseId = validDismissal.takeIf { until > 0L },
                dismissedUntilMs = until,
                requiredRelease = required,
                cachedArtifact = cached,
                policyRecoveryRequired = policyRecoveryRequired,
            ),
            recoveryRequired = policyRecoveryRequired ||
                requiredRelease != null && !requiredIsInstalled && !requiredIsValid,
            installedRequiredPolicy = if (requiredIsInstalled) this else null,
        )
    }

    private fun AppUpdatePolicyState.withoutRequiredUpdate() = copy(
        requiredRelease = null,
        cachedArtifact = null,
    )

    private fun VerifiedAppUpdateArtifact.matches(release: AppUpdateRelease): Boolean =
        sizeBytes == release.sizeBytes &&
            sha256 == release.sha256 &&
            packageName == release.packageName &&
            versionName == release.versionName &&
            versionCode == release.versionCode &&
            minimumAndroidSdk == release.minimumAndroidSdk &&
            !debuggable &&
            orderedSignerLineage == release.signingCertificateSha256Fingerprints &&
            currentSignerSha256Fingerprint == orderedSignerLineage.lastOrNull()

    private fun AppUpdateState.downloadTarget(): Pair<AppUpdateRelease, Boolean>? = when (this) {
        is AppUpdateState.Optional -> release to false
        is AppUpdateState.Required -> release to true
        is AppUpdateState.RecoverableError -> release?.let { it to required }
        else -> null
    }

    private fun AppUpdateState.releaseOrNull(): AppUpdateRelease? = when (this) {
        is AppUpdateState.Optional -> release
        is AppUpdateState.Required -> release
        is AppUpdateState.Downloading -> release
        is AppUpdateState.Verifying -> release
        is AppUpdateState.Refreshing -> release
        is AppUpdateState.NeedsPermission -> release
        is AppUpdateState.Ready -> release
        is AppUpdateState.HandingOff -> release
        is AppUpdateState.Unsupported -> release
        is AppUpdateState.RecoverableError -> release
        AppUpdateState.Checking, AppUpdateState.Current, AppUpdateState.PolicyRecovery -> null
    }

    private fun AppUpdateState.artifactOrNull(): VerifiedAppUpdateArtifact? = when (this) {
        is AppUpdateState.NeedsPermission -> artifact
        is AppUpdateState.Ready -> artifact
        is AppUpdateState.Refreshing -> artifact
        is AppUpdateState.HandingOff -> artifact
        is AppUpdateState.RecoverableError -> artifact
        else -> null
    }

    private fun AppUpdateState.isRequired(): Boolean = when (this) {
        is AppUpdateState.Required -> true
        is AppUpdateState.Downloading -> required
        is AppUpdateState.Verifying -> required
        is AppUpdateState.Refreshing -> required
        is AppUpdateState.NeedsPermission -> required
        is AppUpdateState.Ready -> required
        is AppUpdateState.HandingOff -> required
        is AppUpdateState.Unsupported -> required
        is AppUpdateState.RecoverableError -> required
        AppUpdateState.PolicyRecovery -> true
        else -> false
    }

    private companion object {
        const val LogTag = "AppUpdate"
        const val RetryCooldownMs = 15L * 60L * 1_000L
        const val CurrentVersionCooldownMs = 6L * 60L * 60L * 1_000L
        const val OptionalDismissalMs = 24L * 60L * 60L * 1_000L
        const val MaximumAcceptedCooldownMs = 48L * 60L * 60L * 1_000L
        const val MaximumArtifactBytes = 4L * 1024L * 1024L * 1024L
        val ReleaseIdPattern = Regex("[a-z0-9][a-z0-9._-]{1,63}")
        val ArtifactFileNamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val Sha256Pattern = Regex("[0-9a-f]{64}")
        val AndroidPackageNamePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
        val CertificateFingerprintPattern = Regex("(?:[0-9A-F]{2}:){31}[0-9A-F]{2}")
    }
}

private data class NormalizedPolicySnapshot(
    val state: AppUpdatePolicyState,
    val recoveryRequired: Boolean,
    val installedRequiredPolicy: AppUpdatePolicyState? = null,
)

private fun PlatformGameUpdateDecision.toAppDecision(): AppUpdateCatalogDecision =
    AppUpdateCatalogDecision(
        updateAvailable = updateAvailable,
        required = required,
        release = release?.let { value ->
            AppUpdateRelease(
                id = value.id,
                versionName = value.versionName,
                versionCode = value.versionCode,
                minimumSupportedVersionCode = value.minimumSupportedVersionCode,
                minimumAndroidSdk = requireNotNull(value.minimumAndroidSdk),
                publishedAt = value.publishedAt,
                changelog = value.changelog,
                fileName = value.fileName,
                sizeBytes = value.sizeBytes,
                sha256 = value.sha256,
                downloadUrl = value.downloadUrl,
                packageName = requireNotNull(value.packageName),
                signingCertificateSha256Fingerprints = value.signingCertificateSha256Fingerprints,
            )
        },
    )

internal fun platformUpdateCatalog(
    check: suspend (Long) -> PlatformGameUpdateDecision,
): StableAndroidUpdateCatalog = StableAndroidUpdateCatalog { installedVersionCode ->
    check(installedVersionCode).toAppDecision()
}

private fun safeDeadline(now: Long, durationMs: Long): Long =
    if (now > Long.MAX_VALUE - durationMs) Long.MAX_VALUE else now + durationMs

private fun sameOrigin(first: URI, second: URI): Boolean =
    first.scheme.equals(second.scheme, ignoreCase = true) &&
        first.host.equals(second.host, ignoreCase = true) &&
        effectivePort(first) == effectivePort(second)

private fun effectivePort(uri: URI): Int = when {
    uri.port >= 0 -> uri.port
    uri.scheme.equals("https", ignoreCase = true) -> 443
    uri.scheme.equals("http", ignoreCase = true) -> 80
    else -> -1
}

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

private val LoopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")
