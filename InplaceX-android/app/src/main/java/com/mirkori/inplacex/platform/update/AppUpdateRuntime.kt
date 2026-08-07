package com.mirkori.inplacex.platform.update

import com.mirkori.inplacex.platform.logging.AppLog
import com.mirkori.inplacex.platform.mirkori.MirkoriPlatformRuntime
import com.mirkori.platform.sdk.PlatformGameUpdateDecision
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.coroutines.CancellationException
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
)

internal data class AppUpdateCatalogDecision(
    val updateAvailable: Boolean,
    val required: Boolean,
    val release: AppUpdateRelease?,
)

internal fun interface StableAndroidUpdateCatalog {
    suspend fun check(installedVersionCode: Long): AppUpdateCatalogDecision
}

internal sealed interface AppUpdateCheckResult {
    data object NoAction : AppUpdateCheckResult

    data object Unavailable : AppUpdateCheckResult

    data class Prompt(
        val release: AppUpdateRelease,
        val required: Boolean,
    ) : AppUpdateCheckResult
}

internal data class AppUpdatePolicyState(
    val nextCheckAfterMs: Long = 0L,
    val dismissedReleaseId: String? = null,
    val dismissedUntilMs: Long = 0L,
)

internal interface AppUpdatePolicyStore {
    fun read(): AppUpdatePolicyState

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
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val checkMutex = Mutex()
    private val stateLock = Any()

    suspend fun check(installedVersionCode: Long): AppUpdateCheckResult = checkMutex.withLock {
        require(installedVersionCode > 0)
        val now = clockMs().coerceAtLeast(0L)
        val before = synchronized(stateLock) { store.read().normalized(now) }
        if (before.nextCheckAfterMs > now) return@withLock AppUpdateCheckResult.NoAction

        val decision = try {
            catalog.check(installedVersionCode).also { decision ->
                validateDecision(decision, installedVersionCode)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            persist(
                before.copy(nextCheckAfterMs = safeDeadline(now, RetryCooldownMs)),
            )
            AppLog.warn(
                tag = LogTag,
                message = "App update check is unavailable",
                attributes = mapOf("errorClass" to error.javaClass.name),
            )
            return@withLock AppUpdateCheckResult.Unavailable
        }

        if (!decision.updateAvailable) {
            persist(
                before.copy(
                    nextCheckAfterMs = safeDeadline(now, CurrentVersionCooldownMs),
                    dismissedReleaseId = before.dismissedReleaseId.takeIf { before.dismissedUntilMs > now },
                    dismissedUntilMs = before.dismissedUntilMs.takeIf { it > now } ?: 0L,
                ),
            )
            return@withLock AppUpdateCheckResult.NoAction
        }

        val release = requireNotNull(decision.release)
        val current = synchronized(stateLock) { store.read().normalized(now) }
        if (
            !decision.required &&
            current.dismissedReleaseId == release.id &&
            current.dismissedUntilMs > now
        ) {
            return@withLock AppUpdateCheckResult.NoAction
        }
        if (current.dismissedReleaseId != null && current.dismissedReleaseId != release.id) {
            persist(current.copy(dismissedReleaseId = null, dismissedUntilMs = 0L))
        }
        AppLog.info(
            tag = LogTag,
            message = "App update is available",
            attributes = mapOf(
                "releaseId" to release.id,
                "versionCode" to release.versionCode.toString(),
                "required" to decision.required.toString(),
            ),
        )
        AppUpdateCheckResult.Prompt(release, decision.required)
    }

    fun dismissOptional(releaseId: String) {
        require(releaseId.matches(ReleaseIdPattern))
        val now = clockMs().coerceAtLeast(0L)
        val current = synchronized(stateLock) { store.read().normalized(now) }
        persist(
            current.copy(
                dismissedReleaseId = releaseId,
                dismissedUntilMs = safeDeadline(now, OptionalDismissalMs),
            ),
        )
    }

    private fun validateDecision(decision: AppUpdateCatalogDecision, installedVersionCode: Long) {
        if (!decision.updateAvailable) {
            require(!decision.required && decision.release == null)
            return
        }
        val release = requireNotNull(decision.release)
        require(release.id.matches(ReleaseIdPattern))
        require(release.versionCode > installedVersionCode)
        require(release.minimumSupportedVersionCode in 1..release.versionCode)
        require(release.minimumAndroidSdk in 21..100)
        require(release.sizeBytes in 1..MaximumArtifactBytes)
        require(release.sha256.matches(Sha256Pattern))
        require(decision.required == (installedVersionCode < release.minimumSupportedVersionCode))
        require(originPolicy.accepts(release))
    }

    private fun persist(state: AppUpdatePolicyState) {
        val stored = synchronized(stateLock) { store.write(state) }
        if (!stored) {
            AppLog.warn(
                tag = LogTag,
                message = "App update policy state was not persisted",
            )
        }
    }

    private fun AppUpdatePolicyState.normalized(now: Long): AppUpdatePolicyState {
        val next = nextCheckAfterMs.takeIf { it in 1..safeDeadline(now, MaximumAcceptedCooldownMs) } ?: 0L
        val validDismissal = dismissedReleaseId?.takeIf { it.matches(ReleaseIdPattern) }
        val until = dismissedUntilMs.takeIf {
            validDismissal != null && it in 1..safeDeadline(now, MaximumAcceptedCooldownMs)
        } ?: 0L
        return AppUpdatePolicyState(
            nextCheckAfterMs = next,
            dismissedReleaseId = validDismissal.takeIf { until > 0L },
            dismissedUntilMs = until,
        )
    }

    companion object {
        fun create(
            platformRuntime: MirkoriPlatformRuntime,
            store: AppUpdatePolicyStore,
            platformBaseUrl: String,
            allowCleartextLoopback: Boolean,
            clockMs: () -> Long = System::currentTimeMillis,
        ): AppUpdateRuntime = AppUpdateRuntime(
            catalog = StableAndroidUpdateCatalog { installedVersionCode ->
                platformRuntime.stableAndroidUpdate(installedVersionCode).toAppDecision()
            },
            store = store,
            originPolicy = AppUpdateOriginPolicy(platformBaseUrl, allowCleartextLoopback),
            clockMs = clockMs,
        )

        private const val LogTag = "AppUpdate"
        private const val RetryCooldownMs = 15L * 60L * 1_000L
        private const val CurrentVersionCooldownMs = 6L * 60L * 60L * 1_000L
        private const val OptionalDismissalMs = 24L * 60L * 60L * 1_000L
        private const val MaximumAcceptedCooldownMs = 48L * 60L * 60L * 1_000L
        private const val MaximumArtifactBytes = 4L * 1024L * 1024L * 1024L
        private val ReleaseIdPattern = Regex("[a-z0-9][a-z0-9._-]{1,63}")
        private val Sha256Pattern = Regex("[0-9a-f]{64}")
    }
}

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
            )
        },
    )

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
