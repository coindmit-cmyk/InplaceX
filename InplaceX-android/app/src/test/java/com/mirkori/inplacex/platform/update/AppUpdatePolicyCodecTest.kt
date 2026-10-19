package com.mirkori.inplacex.platform.update

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdatePolicyCodecTest {
    @Test
    fun `required release and cached signer identity round trip as one state document`() {
        val release = release()
        val artifact = VerifiedAppUpdateArtifact(
            releaseId = release.id,
            cacheFileName = "${release.id}.apk",
            sizeBytes = release.sizeBytes,
            sha256 = release.sha256,
            packageName = release.packageName,
            versionName = release.versionName,
            versionCode = release.versionCode,
            minimumAndroidSdk = release.minimumAndroidSdk,
            debuggable = false,
            currentSignerSha256Fingerprint = release.signingCertificateSha256Fingerprints.last(),
            orderedSignerLineage = release.signingCertificateSha256Fingerprints,
        )
        val state = AppUpdatePolicyState(
            nextCheckAfterMs = 123L,
            requiredRelease = release,
            cachedArtifact = artifact,
        )

        assertEquals(state, decodeAppUpdatePolicyState(encodeAppUpdatePolicyState(state)))
    }

    @Test
    fun `unknown or partial persisted schema is rejected for blocking store recovery`() {
        assertNull(decodeAppUpdatePolicyState("{}"))
        assertNull(decodeAppUpdatePolicyState("{\"schemaVersion\":3}"))
    }

    private fun release(): AppUpdateRelease {
        val first = List(32) { "AA" }.joinToString(":")
        val current = List(32) { "BB" }.joinToString(":")
        return AppUpdateRelease(
            id = "inplacex-1.0.0-100",
            versionName = "1.0.0",
            versionCode = 100,
            minimumSupportedVersionCode = 50,
            minimumAndroidSdk = 29,
            publishedAt = Instant.parse("2026-08-07T12:00:00Z"),
            changelog = "Release notes",
            fileName = "InplaceX-1.0.0.apk",
            sizeBytes = 12_345_678,
            sha256 = "a".repeat(64),
            downloadUrl = "https://games.dmit.life/downloads/inplacex-1.0.0-100/InplaceX-1.0.0.apk",
            packageName = "com.mirkori.inplacex",
            signingCertificateSha256Fingerprints = listOf(first, current),
        )
    }
}
