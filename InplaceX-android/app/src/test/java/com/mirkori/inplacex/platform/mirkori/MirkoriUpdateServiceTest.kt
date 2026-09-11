package com.mirkori.inplacex.platform.mirkori

import com.mirkori.platform.sdk.PlatformDistributionDeliveryChannel
import com.mirkori.platform.sdk.PlatformDistributionGameRelease
import com.mirkori.platform.sdk.PlatformDistributionMarketScope
import com.mirkori.platform.sdk.PlatformDistributionPaymentChannel
import com.mirkori.platform.sdk.PlatformDistributionStatus
import com.mirkori.platform.sdk.PlatformDistributionUpdateDecision
import com.mirkori.platform.sdk.PlatformDistributionVariant
import com.mirkori.platform.sdk.PlatformReleaseChannel
import com.mirkori.platform.sdk.PlatformReleasePlatform
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MirkoriUpdateServiceTest {
    @Test
    fun `maps current direct APK and Google Play decisions without granting trust to a wrong package`() = runBlocking {
        var requestedVersion = 0L
        val current = service { version ->
            requestedVersion = version
            decision(updateAvailable = false)
        }.check()
        assertSame(MirkoriUpdateCheckResult.Current, current)
        assertEquals(42L, requestedVersion)

        val direct = service { decision(required = true) }.check() as MirkoriUpdateCheckResult.Available
        assertTrue(direct.update.required)
        assertTrue(direct.update.compatible)
        assertEquals(ReleaseId, direct.update.releaseId)
        assertEquals(DownloadUrl, (direct.update.target as MirkoriUpdateTarget.DirectApk).url)

        val play = service(installedPackageName = GlobalPackage) {
            decision(
                distribution = distribution(
                    id = "global-google",
                    packageName = GlobalPackage,
                    marketScope = PlatformDistributionMarketScope.GLOBAL,
                    paymentChannel = PlatformDistributionPaymentChannel.GOOGLE_PLAY,
                    deliveryChannel = PlatformDistributionDeliveryChannel.GOOGLE_PLAY,
                ),
                release = release(
                    distributionId = "global-google",
                    packageName = GlobalPackage,
                    downloadUrl = null,
                ),
            )
        }.check()
        assertEquals(
            GlobalPackage,
            ((play as MirkoriUpdateCheckResult.Available).update.target as MirkoriUpdateTarget.GooglePlay).packageName,
        )

        val wrongPackage = MirkoriUpdateService(
            source = MirkoriUpdateDecisionSource { decision() },
            installedVersionCode = 42,
            installedPackageName = "com.mirkori.inplacex.wrong",
            installedAndroidSdk = 35,
        ).check()
        assertSame(MirkoriUpdateCheckResult.Unavailable, wrongPackage)

        val unsupported = service { decision(release = release(minimumAndroidSdk = 36)) }.check()
            as MirkoriUpdateCheckResult.Available
        assertFalse(unsupported.update.compatible)
    }

    private fun service(
        installedPackageName: String = RfPackage,
        source: suspend (Long) -> PlatformDistributionUpdateDecision,
    ) = MirkoriUpdateService(
        source = MirkoriUpdateDecisionSource(source),
        installedVersionCode = 42,
        installedPackageName = installedPackageName,
        installedAndroidSdk = 35,
    )

    private fun decision(
        updateAvailable: Boolean = true,
        required: Boolean = false,
        distribution: PlatformDistributionVariant = distribution(),
        release: PlatformDistributionGameRelease? = if (updateAvailable) {
            release(minimumSupportedVersionCode = if (required) 43 else 1)
        } else {
            null
        },
    ) = PlatformDistributionUpdateDecision(
        gameId = "inplacex",
        distribution = distribution,
        channel = PlatformReleaseChannel.STABLE,
        currentVersionCode = 42,
        updateAvailable = updateAvailable,
        required = required,
        release = release,
    )

    private fun distribution(
        id: String = "rf-mirkori",
        packageName: String = RfPackage,
        marketScope: PlatformDistributionMarketScope = PlatformDistributionMarketScope.RF,
        paymentChannel: PlatformDistributionPaymentChannel = PlatformDistributionPaymentChannel.MIRKORI,
        deliveryChannel: PlatformDistributionDeliveryChannel = PlatformDistributionDeliveryChannel.DIRECT_APK,
    ) = PlatformDistributionVariant(
        id = id,
        gameId = "inplacex",
        platform = PlatformReleasePlatform.ANDROID,
        marketScope = marketScope,
        packageName = packageName,
        signingIdentityRef = "inplacex-signing",
        signingCertificateSha256Fingerprints = listOf(Fingerprint),
        paymentChannel = paymentChannel,
        deliveryChannel = deliveryChannel,
        releaseChannels = setOf(PlatformReleaseChannel.STABLE),
        status = PlatformDistributionStatus.ACTIVE,
        effectiveConfigurationVersion = 1,
    )

    private fun release(
        distributionId: String = "rf-mirkori",
        packageName: String = RfPackage,
        downloadUrl: String? = DownloadUrl,
        minimumAndroidSdk: Int = 29,
        minimumSupportedVersionCode: Long = 1,
    ) = PlatformDistributionGameRelease(
        id = ReleaseId,
        gameId = "inplacex",
        distributionId = distributionId,
        platform = PlatformReleasePlatform.ANDROID,
        channel = PlatformReleaseChannel.STABLE,
        versionName = "1.1",
        versionCode = 43,
        minimumSupportedVersionCode = minimumSupportedVersionCode,
        minimumAndroidSdk = minimumAndroidSdk,
        publishedAt = Instant.parse("2026-09-11T10:00:00Z"),
        changelogs = mapOf("ru" to "Исправления", "en" to "Fixes"),
        fileName = "InplaceX-rf-43.apk",
        sizeBytes = 123,
        sha256 = "a".repeat(64),
        downloadUrl = downloadUrl,
        packageName = packageName,
        signingIdentityRef = "inplacex-signing",
        signingCertificateSha256Fingerprints = listOf(Fingerprint),
    )

    private companion object {
        const val ReleaseId = "inplacex-rf-43"
        const val RfPackage = "com.mirkori.inplacex.rf"
        const val GlobalPackage = "com.mirkori.inplacex"
        const val DownloadUrl = "https://games.dmit.life/downloads/inplacex-rf-43/InplaceX-rf-43.apk"
        const val Fingerprint = "AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:" +
            "AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA:AA"
    }
}
