package com.mirkori.inplacex.platform.mirkori

import com.mirkori.platform.sdk.MirkoriGameSdk
import com.mirkori.platform.sdk.PlatformDistributionDeliveryChannel
import com.mirkori.platform.sdk.PlatformDistributionUpdateDecision
import kotlinx.coroutines.CancellationException

internal sealed interface MirkoriUpdateTarget {
    data class DirectApk(val url: String) : MirkoriUpdateTarget

    data class GooglePlay(val packageName: String) : MirkoriUpdateTarget
}

internal data class MirkoriAvailableUpdate(
    val releaseId: String,
    val versionName: String,
    val required: Boolean,
    val compatible: Boolean,
    val minimumAndroidSdk: Int,
    val changelogs: Map<String, String>,
    val target: MirkoriUpdateTarget,
)

internal sealed interface MirkoriUpdateCheckResult {
    data object Current : MirkoriUpdateCheckResult

    data class Available(val update: MirkoriAvailableUpdate) : MirkoriUpdateCheckResult

    data object Unavailable : MirkoriUpdateCheckResult
}

internal fun interface MirkoriUpdateDecisionSource {
    suspend fun check(currentVersionCode: Long): PlatformDistributionUpdateDecision
}

internal class MirkoriUpdateService(
    private val source: MirkoriUpdateDecisionSource,
    private val installedVersionCode: Long,
    private val installedPackageName: String,
    private val installedAndroidSdk: Int,
) {
    constructor(
        sdk: MirkoriGameSdk,
        installedVersionCode: Long,
        installedPackageName: String,
        installedAndroidSdk: Int,
    ) : this(
        source = MirkoriUpdateDecisionSource { versionCode ->
            sdk.checkForDistributionUpdate(versionCode)
        },
        installedVersionCode = installedVersionCode,
        installedPackageName = installedPackageName,
        installedAndroidSdk = installedAndroidSdk,
    )

    init {
        require(installedVersionCode > 0)
        require(installedPackageName.matches(AndroidPackageNamePattern))
        require(installedAndroidSdk in 21..100)
    }

    suspend fun check(): MirkoriUpdateCheckResult = try {
        source.check(installedVersionCode).toResult()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        MirkoriUpdateCheckResult.Unavailable
    }

    private fun PlatformDistributionUpdateDecision.toResult(): MirkoriUpdateCheckResult {
        require(distribution.packageName == installedPackageName)
        if (!updateAvailable) {
            require(!required && release == null)
            return MirkoriUpdateCheckResult.Current
        }
        val targetRelease = requireNotNull(release)
        require(targetRelease.versionCode > installedVersionCode)
        require(required == (installedVersionCode < targetRelease.minimumSupportedVersionCode))
        require(targetRelease.changelogs.keys == setOf("ru", "en"))
        val target = when (distribution.deliveryChannel) {
            PlatformDistributionDeliveryChannel.DIRECT_APK ->
                MirkoriUpdateTarget.DirectApk(requireNotNull(targetRelease.downloadUrl))
            PlatformDistributionDeliveryChannel.GOOGLE_PLAY ->
                MirkoriUpdateTarget.GooglePlay(distribution.packageName)
        }
        return MirkoriUpdateCheckResult.Available(
            MirkoriAvailableUpdate(
                releaseId = targetRelease.id,
                versionName = targetRelease.versionName,
                required = required,
                compatible = targetRelease.minimumAndroidSdk <= installedAndroidSdk,
                minimumAndroidSdk = targetRelease.minimumAndroidSdk,
                changelogs = targetRelease.changelogs,
                target = target,
            ),
        )
    }

    private companion object {
        val AndroidPackageNamePattern = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+")
    }
}
