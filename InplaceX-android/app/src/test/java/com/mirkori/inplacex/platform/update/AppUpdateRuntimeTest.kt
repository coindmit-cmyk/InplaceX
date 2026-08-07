package com.mirkori.inplacex.platform.update

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRuntimeTest {
    @Test
    fun `current version cooldown survives runtime recreation`() = runBlocking {
        var now = NowMs
        val store = MemoryUpdateStore()
        val firstCatalog = RecordingCatalog(AppUpdateCatalogDecision(false, false, null))

        val first = runtime(firstCatalog, store) { now }.check(42)
        val secondCatalog = RecordingCatalog(AppUpdateCatalogDecision(false, false, null))
        val second = runtime(secondCatalog, store) { now }.check(42)

        assertEquals(AppUpdateCheckResult.NoAction, first)
        assertEquals(AppUpdateCheckResult.NoAction, second)
        assertEquals(1, firstCatalog.calls)
        assertEquals(0, secondCatalog.calls)
        now += 6L * 60L * 60L * 1_000L
        runtime(secondCatalog, store) { now }.check(42)
        assertEquals(1, secondCatalog.calls)
    }

    @Test
    fun `optional dismissal is persisted for the exact release only`() = runBlocking {
        val store = MemoryUpdateStore()
        val firstRuntime = runtime(RecordingCatalog(optionalDecision()), store)

        val prompt = firstRuntime.check(42) as AppUpdateCheckResult.Prompt
        firstRuntime.dismissOptional(prompt.release.id)
        val suppressedCatalog = RecordingCatalog(optionalDecision())
        val suppressed = runtime(suppressedCatalog, store).check(42)
        val nextRelease = runtime(
            RecordingCatalog(optionalDecision(releaseId = "inplacex-1.0.1-101", versionCode = 101)),
            store,
        ).check(42)

        assertTrue(!prompt.required)
        assertEquals(AppUpdateCheckResult.NoAction, suppressed)
        assertEquals(1, suppressedCatalog.calls)
        assertTrue(nextRelease is AppUpdateCheckResult.Prompt)
    }

    @Test
    fun `required update ignores an optional dismissal`() = runBlocking {
        val store = MemoryUpdateStore()
        val runtime = runtime(RecordingCatalog(requiredDecision()), store)
        runtime.dismissOptional(ReleaseId)

        val result = runtime.check(42)

        assertTrue(result is AppUpdateCheckResult.Prompt && result.required)
    }

    @Test
    fun `foreign download origin fails closed and enters bounded retry cooldown`() = runBlocking {
        val store = MemoryUpdateStore()
        val catalog = RecordingCatalog(
            optionalDecision(
                release = release(downloadUrl = "https://evil.example/downloads/$ReleaseId/InplaceX-1.0.0.apk"),
            ),
        )
        val runtime = runtime(catalog, store)

        val first = runtime.check(42)
        val second = runtime.check(42)

        assertEquals(AppUpdateCheckResult.Unavailable, first)
        assertEquals(AppUpdateCheckResult.NoAction, second)
        assertEquals(1, catalog.calls)
        assertEquals(NowMs + 15L * 60L * 1_000L, store.state.nextCheckAfterMs)
    }

    private fun runtime(
        catalog: StableAndroidUpdateCatalog,
        store: AppUpdatePolicyStore,
        clockMs: () -> Long = { NowMs },
    ) = AppUpdateRuntime(
        catalog = catalog,
        store = store,
        originPolicy = AppUpdateOriginPolicy("https://games.dmit.life", false),
        clockMs = clockMs,
    )

    private fun optionalDecision(
        releaseId: String = ReleaseId,
        versionCode: Long = 100,
        release: AppUpdateRelease = release(releaseId = releaseId, versionCode = versionCode),
    ) = AppUpdateCatalogDecision(updateAvailable = true, required = false, release = release)

    private fun requiredDecision() = AppUpdateCatalogDecision(
        updateAvailable = true,
        required = true,
        release = release(minimumSupportedVersionCode = 50),
    )

    private companion object {
        const val NowMs = 1_786_000_000_000L
        const val ReleaseId = "inplacex-1.0.0-100"

        fun release(
            releaseId: String = ReleaseId,
            versionCode: Long = 100,
            minimumSupportedVersionCode: Long = 1,
            downloadUrl: String = "https://games.dmit.life/downloads/$releaseId/InplaceX-1.0.0.apk",
        ) = AppUpdateRelease(
            id = releaseId,
            versionName = "1.0.0",
            versionCode = versionCode,
            minimumSupportedVersionCode = minimumSupportedVersionCode,
            minimumAndroidSdk = 29,
            publishedAt = Instant.parse("2026-08-07T12:00:00Z"),
            changelog = "Release notes",
            fileName = "InplaceX-1.0.0.apk",
            sizeBytes = 12_345_678,
            sha256 = "a".repeat(64),
            downloadUrl = downloadUrl,
        )
    }
}

private class MemoryUpdateStore : AppUpdatePolicyStore {
    var state = AppUpdatePolicyState()
    override fun read(): AppUpdatePolicyState = state
    override fun write(state: AppUpdatePolicyState): Boolean {
        this.state = state
        return true
    }
}

private class RecordingCatalog(
    private val decision: AppUpdateCatalogDecision,
) : StableAndroidUpdateCatalog {
    var calls: Int = 0
        private set

    override suspend fun check(installedVersionCode: Long): AppUpdateCatalogDecision {
        calls += 1
        return decision
    }
}
