package com.mirkori.inplacex.platform.update

import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRuntimeTest {
    @Test
    fun `current response cooldown survives runtime recreation`() = runBlocking {
        var now = NowMs
        val store = MemoryUpdateStore()
        val firstCatalog = RecordingCatalog(currentDecision())
        val first = runtime(firstCatalog, store, clockMs = { now })

        first.refresh()
        val secondCatalog = RecordingCatalog(currentDecision())
        val second = runtime(secondCatalog, store, clockMs = { now })
        second.refresh()

        assertEquals(AppUpdateState.Current, first.state.value)
        assertEquals(AppUpdateState.Current, second.state.value)
        assertEquals(1, firstCatalog.calls)
        assertEquals(0, secondCatalog.calls)
        now += 6L * 60L * 60L * 1_000L
        second.refresh()
        assertEquals(1, secondCatalog.calls)
    }

    @Test
    fun `missing policy on first install starts normally`() = runBlocking {
        val store = MemoryUpdateStore(missing = true)
        val runtime = runtime(RecordingCatalog(currentDecision()), store)

        assertEquals(AppUpdateState.Checking, runtime.state.value)
        runtime.refresh()
        assertEquals(AppUpdateState.Current, runtime.state.value)
    }

    @Test
    fun `required gate is durable before presentation and survives restart plus outage`() = runBlocking {
        val store = MemoryUpdateStore()
        val first = runtime(RecordingCatalog(requiredDecision()), store)

        first.refresh()

        assertTrue(first.state.value is AppUpdateState.Required)
        assertEquals(ReleaseId, store.state.requiredRelease?.id)
        val restarted = runtime(ThrowingCatalog, store)
        assertTrue(restarted.state.value is AppUpdateState.Required)
        restarted.refresh()
        val failed = restarted.state.value as AppUpdateState.RecoverableError
        assertTrue(failed.required)
        assertEquals(ReleaseId, failed.release?.id)
        assertEquals(ReleaseId, store.state.requiredRelease?.id)
    }

    @Test
    fun `authoritative required replacement and revocation update the durable gate`() = runBlocking {
        val store = MemoryUpdateStore()
        runtime(RecordingCatalog(requiredDecision()), store).refresh()
        val replacement = release(
            releaseId = "inplacex-1.0.1-101",
            versionName = "1.0.1",
            versionCode = 101,
            minimumSupportedVersionCode = 60,
        )
        val replaced = runtime(
            RecordingCatalog(AppUpdateCatalogDecision(true, true, replacement)),
            store,
        )

        replaced.refresh()
        assertEquals(replacement.id, store.state.requiredRelease?.id)
        runtime(RecordingCatalog(currentDecision()), store).refresh()

        assertNull(store.state.requiredRelease)
        assertNull(store.state.cachedArtifact)
    }

    @Test
    fun `preflight storage failure aborts before learning a required decision`() = runBlocking {
        val store = MemoryUpdateStore(acceptWrites = false)
        val catalog = RecordingCatalog(requiredDecision())
        val runtime = runtime(catalog, store)

        runtime.refresh()

        assertEquals(AppUpdateState.PolicyRecovery, runtime.state.value)
        assertNull(store.state.requiredRelease)
        assertEquals(0, catalog.calls)
    }

    @Test
    fun `unsupported Android release remains an explicit blocking state`() = runBlocking {
        val unsupported = release(minimumAndroidSdk = 36, minimumSupportedVersionCode = 50)
        val runtime = runtime(
            RecordingCatalog(AppUpdateCatalogDecision(true, true, unsupported)),
            MemoryUpdateStore(),
            installedAndroidSdk = 35,
        )

        runtime.refresh()

        val state = runtime.state.value as AppUpdateState.Unsupported
        assertTrue(state.required)
        assertEquals(36, state.release.minimumAndroidSdk)
    }

    @Test
    fun `verified cached identity is persisted atomically for required release`() = runBlocking {
        val store = MemoryUpdateStore()
        val cache = MemoryArtifactCache()
        val verifier = MatchingVerifier()
        val runtime = runtime(
            RecordingCatalog(requiredDecision()),
            store,
            cache = cache,
            verifier = verifier,
            canInstall = { true },
        )

        runtime.refresh()
        runtime.prepareInstall()

        val ready = runtime.state.value as AppUpdateState.Ready
        assertEquals(ReleaseId, ready.artifact.releaseId)
        assertEquals(ready.artifact, store.state.cachedArtifact)
        assertEquals(ReleaseId, store.state.requiredRelease?.id)
        assertEquals(listOf(Fingerprint), ready.artifact.orderedSignerLineage)
    }

    @Test
    fun `verified required artifact is rechecked and remains installable after offline restart`() = runBlocking {
        val store = MemoryUpdateStore()
        val cache = MemoryArtifactCache()
        val verifier = MatchingVerifier()
        val first = runtime(
            RecordingCatalog(requiredDecision()),
            store,
            cache = cache,
            verifier = verifier,
            canInstall = { true },
        )
        first.refresh()
        first.prepareInstall()

        val restarted = runtime(
            ThrowingCatalog,
            store,
            cache = cache,
            verifier = verifier,
            canInstall = { true },
        )
        assertTrue(restarted.state.value is AppUpdateState.Required)
        restarted.refresh()

        val offline = restarted.state.value as AppUpdateState.RecoverableError
        assertTrue(offline.required)
        assertNotNull(offline.artifact)
        restarted.refreshInstallPermission()
        assertTrue(restarted.state.value is AppUpdateState.Ready)
    }

    @Test
    fun `installed required cleanup stays durable and retries across offline restart`() = runBlocking {
        val store = MemoryUpdateStore()
        val cache = MemoryArtifactCache()
        val first = runtime(
            RecordingCatalog(requiredDecision()),
            store,
            cache = cache,
            canInstall = { true },
        )
        first.refresh()
        first.prepareInstall()
        assertTrue(cache.artifactPresent)
        assertEquals(ReleaseId, store.state.requiredRelease?.id)

        cache.failClear = true
        val cleanupBlocked = runtime(
            ThrowingCatalog,
            store,
            cache = cache,
            canInstall = { true },
            installedVersionCode = 100,
        )
        assertEquals(AppUpdateState.PolicyRecovery, cleanupBlocked.state.value)

        cleanupBlocked.refresh()

        assertEquals(AppUpdateState.PolicyRecovery, cleanupBlocked.state.value)
        assertTrue(cache.artifactPresent)
        assertEquals(ReleaseId, store.state.requiredRelease?.id)
        assertNull(store.state.cachedArtifact)
        assertTrue(store.state.policyRecoveryRequired)

        cache.failClear = false
        val offlineRestart = runtime(
            ThrowingCatalog,
            store,
            cache = cache,
            canInstall = { true },
            installedVersionCode = 100,
        )
        assertEquals(AppUpdateState.PolicyRecovery, offlineRestart.state.value)

        offlineRestart.refresh()

        val offline = offlineRestart.state.value as AppUpdateState.RecoverableError
        assertFalse(offline.required)
        assertNull(offline.release)
        assertFalse(cache.artifactPresent)
        assertNull(store.state.requiredRelease)
        assertNull(store.state.cachedArtifact)
        assertFalse(store.state.policyRecoveryRequired)

        val clearedRestart = runtime(
            ThrowingCatalog,
            store,
            cache = cache,
            installedVersionCode = 100,
        )
        clearedRestart.refresh()
        assertEquals(AppUpdateState.Current, clearedRestart.state.value)
    }

    @Test
    fun `optional dismissal is exact and a new release replaces it`() = runBlocking {
        val store = MemoryUpdateStore()
        val first = runtime(RecordingCatalog(optionalDecision()), store)
        first.refresh()
        first.dismissOptional(ReleaseId)

        val same = runtime(RecordingCatalog(optionalDecision()), store)
        same.refresh()
        val next = runtime(
            RecordingCatalog(optionalDecision(release("inplacex-1.0.1-101", "1.0.1", 101))),
            store,
        )
        next.refresh()

        assertEquals(AppUpdateState.Current, same.state.value)
        assertTrue(next.state.value is AppUpdateState.Optional)
    }

    @Test
    fun `optional dismissal cannot race a required catalog decision out of durable storage`() = runBlocking {
        val store = MemoryUpdateStore()
        val catalog = BlockingSecondCatalog(optionalDecision(), requiredDecision())
        val runtime = runtime(catalog, store)
        runtime.refresh()
        assertTrue(runtime.state.value is AppUpdateState.Optional)

        val refresh = async { runtime.refresh() }
        catalog.secondCallStarted.await()
        val dismiss = async { runtime.dismissOptional(ReleaseId) }
        yield()
        assertFalse(dismiss.isCompleted)
        catalog.releaseSecondCall.complete(Unit)
        refresh.await()
        dismiss.await()

        assertTrue(runtime.state.value is AppUpdateState.Required)
        assertEquals(ReleaseId, store.state.requiredRelease?.id)
        assertNull(store.state.dismissedReleaseId)
    }

    @Test
    fun `installer claim waits for authoritative refresh and cannot install a revoked release`() = runBlocking {
        val store = MemoryUpdateStore()
        val cache = MemoryArtifactCache()
        val catalog = BlockingSecondCatalog(requiredDecision(), currentDecision())
        val runtime = runtime(catalog, store, cache = cache, canInstall = { true })
        runtime.refresh()
        runtime.prepareInstall()
        assertTrue(runtime.state.value is AppUpdateState.Ready)

        val refresh = async { runtime.refresh() }
        catalog.secondCallStarted.await()
        assertTrue(runtime.state.value is AppUpdateState.Refreshing)
        var callbackCalls = 0
        val claim = async {
            runtime.performInstallAction { callbackCalls += 1 }
        }
        yield()
        assertFalse(claim.isCompleted)

        catalog.releaseSecondCall.complete(Unit)
        refresh.await()
        assertFalse(claim.await())
        assertEquals(0, callbackCalls)
        assertEquals(AppUpdateState.Current, runtime.state.value)
        assertNull(store.state.requiredRelease)
    }

    @Test
    fun `install handoff failure releases claim and remains recoverable`() = runBlocking {
        val runtime = runtime(
            RecordingCatalog(requiredDecision()),
            MemoryUpdateStore(),
            cache = MemoryArtifactCache(),
            canInstall = { true },
        )
        runtime.refresh()
        runtime.prepareInstall()

        var claimedArtifact: VerifiedAppUpdateArtifact? = null
        assertFalse(
            runtime.performInstallAction { claim ->
                claimedArtifact = claim.artifact
                throw java.io.IOException("installer unavailable")
            },
        )

        val failed = runtime.state.value as AppUpdateState.RecoverableError
        assertTrue(failed.required)
        assertEquals(claimedArtifact, failed.artifact)
        assertTrue(runtime.performInstallAction { })
    }

    @Test
    fun `unknown sources handoff is one shot until permission return`() = runBlocking {
        var permissionGranted = false
        val runtime = runtime(
            RecordingCatalog(requiredDecision()),
            MemoryUpdateStore(),
            cache = MemoryArtifactCache(),
            canInstall = { permissionGranted },
        )
        runtime.refresh()
        runtime.prepareInstall()
        assertTrue(runtime.state.value is AppUpdateState.NeedsPermission)

        var callbackCalls = 0
        assertTrue(
            runtime.performInstallAction { claim ->
                callbackCalls += 1
                assertTrue(claim.needsPermission)
            },
        )
        assertTrue(runtime.state.value is AppUpdateState.HandingOff)
        assertFalse(runtime.performInstallAction { callbackCalls += 1 })
        assertEquals(1, callbackCalls)

        permissionGranted = true
        runtime.refreshInstallPermission()
        assertTrue(runtime.state.value is AppUpdateState.Ready)
    }

    @Test
    fun `claim first handoff linearizes before authoritative revocation`() = runBlocking {
        val catalog = BlockingSecondCatalog(requiredDecision(), currentDecision())
        val store = MemoryUpdateStore()
        val runtime = runtime(catalog, store, cache = MemoryArtifactCache(), canInstall = { true })
        runtime.refresh()
        runtime.prepareInstall()
        val callbackStarted = CompletableDeferred<Unit>()
        val releaseCallback = CompletableDeferred<Unit>()

        val handoff = async {
            runtime.performInstallAction {
                callbackStarted.complete(Unit)
                releaseCallback.await()
            }
        }
        callbackStarted.await()
        val refresh = async { runtime.refresh() }
        yield()
        assertFalse(refresh.isCompleted)

        releaseCallback.complete(Unit)
        assertTrue(handoff.await())
        catalog.secondCallStarted.await()
        catalog.releaseSecondCall.complete(Unit)
        refresh.await()

        assertEquals(AppUpdateState.Current, runtime.state.value)
        assertNull(store.state.requiredRelease)
    }

    @Test
    fun `claim first revocation cleanup failure stays policy recovery`() = runBlocking {
        val catalog = BlockingSecondCatalog(requiredDecision(), currentDecision())
        val store = MemoryUpdateStore()
        val cache = MemoryArtifactCache()
        val runtime = runtime(catalog, store, cache = cache, canInstall = { true })
        runtime.refresh()
        runtime.prepareInstall()
        cache.failClear = true
        val callbackStarted = CompletableDeferred<Unit>()
        val releaseCallback = CompletableDeferred<Unit>()

        val handoff = async {
            runtime.performInstallAction {
                callbackStarted.complete(Unit)
                releaseCallback.await()
            }
        }
        callbackStarted.await()
        val refresh = async { runtime.refresh() }
        yield()
        assertFalse(refresh.isCompleted)

        releaseCallback.complete(Unit)
        assertTrue(handoff.await())
        catalog.secondCallStarted.await()
        catalog.releaseSecondCall.complete(Unit)
        refresh.await()

        assertEquals(AppUpdateState.PolicyRecovery, runtime.state.value)
        assertNull(store.state.requiredRelease)
        assertTrue(store.state.policyRecoveryRequired)
    }

    @Test
    fun `claim first handoff linearizes before authoritative replacement`() = runBlocking {
        val replacement = release(
            releaseId = "inplacex-1.0.1-101",
            versionName = "1.0.1",
            versionCode = 101,
            minimumSupportedVersionCode = 60,
        )
        val catalog = BlockingSecondCatalog(
            requiredDecision(),
            AppUpdateCatalogDecision(true, true, replacement),
        )
        val store = MemoryUpdateStore()
        val runtime = runtime(catalog, store, cache = MemoryArtifactCache(), canInstall = { true })
        runtime.refresh()
        runtime.prepareInstall()
        val callbackStarted = CompletableDeferred<Unit>()
        val releaseCallback = CompletableDeferred<Unit>()

        val handoff = async {
            runtime.performInstallAction {
                callbackStarted.complete(Unit)
                releaseCallback.await()
            }
        }
        callbackStarted.await()
        val refresh = async { runtime.refresh() }
        yield()
        assertFalse(refresh.isCompleted)

        releaseCallback.complete(Unit)
        assertTrue(handoff.await())
        catalog.secondCallStarted.await()
        catalog.releaseSecondCall.complete(Unit)
        refresh.await()

        val required = runtime.state.value as AppUpdateState.Required
        assertEquals(replacement.id, required.release.id)
        assertEquals(replacement.id, store.state.requiredRelease?.id)
    }

    @Test
    fun `concurrent handoff is one shot until activity return`() = runBlocking {
        val runtime = runtime(
            RecordingCatalog(requiredDecision()),
            MemoryUpdateStore(),
            cache = MemoryArtifactCache(),
            canInstall = { true },
        )
        runtime.refresh()
        runtime.prepareInstall()
        val callbackStarted = CompletableDeferred<Unit>()
        val releaseCallback = CompletableDeferred<Unit>()
        var callbackCalls = 0

        val first = async {
            runtime.performInstallAction {
                callbackCalls += 1
                callbackStarted.complete(Unit)
                releaseCallback.await()
            }
        }
        callbackStarted.await()
        val second = async { runtime.performInstallAction { callbackCalls += 1 } }
        yield()
        assertFalse(second.isCompleted)

        releaseCallback.complete(Unit)
        assertTrue(first.await())
        assertFalse(second.await())
        assertEquals(1, callbackCalls)
        assertTrue(runtime.state.value is AppUpdateState.HandingOff)

        runtime.refreshInstallPermission()
        assertTrue(runtime.state.value is AppUpdateState.Ready)
    }

    @Test
    fun `invalid persisted policy stays blocking offline until an authoritative response replaces it`() = runBlocking {
        val store = MemoryUpdateStore(invalid = true)
        val offline = runtime(ThrowingCatalog, store)

        assertEquals(AppUpdateState.PolicyRecovery, offline.state.value)
        offline.refresh()
        assertEquals(AppUpdateState.PolicyRecovery, offline.state.value)

        val restarted = runtime(RecordingCatalog(currentDecision()), store)
        assertEquals(AppUpdateState.PolicyRecovery, restarted.state.value)
        restarted.refresh()
        assertEquals(AppUpdateState.Current, restarted.state.value)
        assertTrue(store.read() is AppUpdatePolicyReadResult.Available)
    }

    @Test
    fun `persisted required validation drift remains a blocking recovery gate`() = runBlocking {
        val drifted = release(minimumSupportedVersionCode = 50).copy(
            downloadUrl = "https://old.games.dmit.life/downloads/$ReleaseId/InplaceX-1.0.0.apk",
        )
        val store = MemoryUpdateStore(requiredRelease = drifted)
        val runtime = runtime(ThrowingCatalog, store)

        assertEquals(AppUpdateState.PolicyRecovery, runtime.state.value)
        runtime.refresh()
        assertEquals(AppUpdateState.PolicyRecovery, runtime.state.value)
        assertEquals(drifted, store.state.requiredRelease)
    }

    @Test
    fun `claim first replacement cleanup failure stays policy recovery`() = runBlocking {
        val replacement = release(
            releaseId = "inplacex-1.0.1-101",
            versionName = "1.0.1",
            versionCode = 101,
            minimumSupportedVersionCode = 60,
        )
        val catalog = BlockingSecondCatalog(
            requiredDecision(),
            AppUpdateCatalogDecision(true, true, replacement),
        )
        val store = MemoryUpdateStore()
        val cache = MemoryArtifactCache()
        val runtime = runtime(catalog, store, cache = cache, canInstall = { true })
        runtime.refresh()
        runtime.prepareInstall()
        cache.failClear = true

        val callbackStarted = CompletableDeferred<Unit>()
        val releaseCallback = CompletableDeferred<Unit>()
        val handoff = async {
            runtime.performInstallAction {
                callbackStarted.complete(Unit)
                releaseCallback.await()
            }
        }
        callbackStarted.await()

        val refresh = async { runtime.refresh() }
        yield()
        assertFalse(refresh.isCompleted)
        releaseCallback.complete(Unit)
        assertTrue(handoff.await())
        catalog.secondCallStarted.await()
        catalog.releaseSecondCall.complete(Unit)
        refresh.await()

        assertEquals(AppUpdateState.PolicyRecovery, runtime.state.value)
        assertEquals(replacement.id, store.state.requiredRelease?.id)
        assertTrue(store.state.policyRecoveryRequired)
    }

    @Test
    fun `revocation marker write failure blocks offline restart with clear success and failure`() = runBlocking {
        listOf(false, true).forEach { clearFails ->
            val store = MemoryUpdateStore()
            val cache = MemoryArtifactCache()
            val catalog = BlockingSecondCatalog(requiredDecision(), currentDecision())
            val runtime = runtime(catalog, store, cache = cache, canInstall = { true })
            runtime.refresh()
            runtime.prepareInstall()
            store.failWriteAfter(additionalAttempts = 2)
            cache.failClear = clearFails

            val refresh = async { runtime.refresh() }
            catalog.secondCallStarted.await()
            catalog.releaseSecondCall.complete(Unit)
            refresh.await()

            assertEquals(AppUpdateState.PolicyRecovery, runtime.state.value)
            assertTrue(store.state.policyRecoveryRequired)
            assertEquals(clearFails, cache.artifactPresent)

            val restarted = runtime(
                ThrowingCatalog,
                store,
                cache = cache,
                canInstall = { true },
            )
            assertEquals(AppUpdateState.PolicyRecovery, restarted.state.value)
            restarted.refresh()
            var launchCalls = 0
            assertFalse(restarted.performInstallAction { launchCalls += 1 })
            assertEquals(0, launchCalls)
        }
    }

    @Test
    fun `replacement marker write failure blocks offline restart with clear success and failure`() = runBlocking {
        val replacement = release(
            releaseId = "inplacex-1.0.1-101",
            versionName = "1.0.1",
            versionCode = 101,
            minimumSupportedVersionCode = 60,
        )
        listOf(false, true).forEach { clearFails ->
            val store = MemoryUpdateStore()
            val cache = MemoryArtifactCache()
            val catalog = BlockingSecondCatalog(
                requiredDecision(),
                AppUpdateCatalogDecision(true, true, replacement),
            )
            val runtime = runtime(catalog, store, cache = cache, canInstall = { true })
            runtime.refresh()
            runtime.prepareInstall()
            store.failWriteAfter(additionalAttempts = 2)
            cache.failClear = clearFails

            val refresh = async { runtime.refresh() }
            catalog.secondCallStarted.await()
            catalog.releaseSecondCall.complete(Unit)
            refresh.await()

            assertEquals(AppUpdateState.PolicyRecovery, runtime.state.value)
            assertTrue(store.state.policyRecoveryRequired)
            assertEquals(clearFails, cache.artifactPresent)

            val restarted = runtime(
                ThrowingCatalog,
                store,
                cache = cache,
                canInstall = { true },
            )
            assertEquals(AppUpdateState.PolicyRecovery, restarted.state.value)
            restarted.refresh()
            var launchCalls = 0
            assertFalse(restarted.performInstallAction { launchCalls += 1 })
            assertEquals(0, launchCalls)
        }
    }

    @Test
    fun `no-cache revocation and replacement marker failures block offline restart`() = runBlocking {
        val replacement = release(
            releaseId = "inplacex-1.0.1-101",
            versionName = "1.0.1",
            versionCode = 101,
            minimumSupportedVersionCode = 60,
        )
        listOf(
            currentDecision(),
            AppUpdateCatalogDecision(true, true, replacement),
        ).forEach { nextDecision ->
            val store = MemoryUpdateStore()
            runtime(RecordingCatalog(requiredDecision()), store).refresh()
            assertNull(store.state.cachedArtifact)
            store.failWriteAfter(additionalAttempts = 2)

            val refreshed = runtime(RecordingCatalog(nextDecision), store)
            refreshed.refresh()

            assertEquals(AppUpdateState.PolicyRecovery, refreshed.state.value)
            assertTrue(store.state.policyRecoveryRequired)
            val restarted = runtime(ThrowingCatalog, store)
            assertEquals(AppUpdateState.PolicyRecovery, restarted.state.value)
            restarted.refresh()
            assertEquals(AppUpdateState.PolicyRecovery, restarted.state.value)
            var launchCalls = 0
            assertFalse(restarted.performInstallAction { launchCalls += 1 })
            assertEquals(0, launchCalls)
        }
    }

    @Test
    fun `first required response marker failure blocks offline restart without an artifact`() = runBlocking {
        val store = MemoryUpdateStore()
        store.failWriteAfter(additionalAttempts = 2)
        val runtime = runtime(RecordingCatalog(requiredDecision()), store)

        runtime.refresh()

        assertEquals(AppUpdateState.PolicyRecovery, runtime.state.value)
        assertTrue(store.state.policyRecoveryRequired)
        assertNull(store.state.requiredRelease)
        assertNull(store.state.cachedArtifact)
        val restarted = runtime(ThrowingCatalog, store)
        assertEquals(AppUpdateState.PolicyRecovery, restarted.state.value)
        restarted.refresh()
        assertEquals(AppUpdateState.PolicyRecovery, restarted.state.value)
    }

    @Test
    fun `foreign artifact origin fails closed without erasing a durable gate`() = runBlocking {
        val required = release(minimumSupportedVersionCode = 50)
        val store = MemoryUpdateStore(requiredRelease = required)
        val foreign = required.copy(downloadUrl = "https://evil.example/downloads/$ReleaseId/InplaceX-1.0.0.apk")
        val runtime = runtime(
            RecordingCatalog(AppUpdateCatalogDecision(true, true, foreign)),
            store,
        )

        runtime.refresh()

        val state = runtime.state.value as AppUpdateState.RecoverableError
        assertTrue(state.required)
        assertEquals(ReleaseId, store.state.requiredRelease?.id)
    }

    @Test
    fun `signer identity rejects ambiguous current signers and preserves ordered lineage`() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizedSigningIdentity(listOf(Fingerprint, NextFingerprint), listOf(Fingerprint, NextFingerprint))
        }
        assertEquals(
            AppUpdateSigningIdentity(NextFingerprint, listOf(Fingerprint, NextFingerprint)),
            normalizedSigningIdentity(
                currentSigners = listOf(NextFingerprint),
                rawHistory = listOf(Fingerprint, NextFingerprint),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            normalizedSigningIdentity(
                currentSigners = listOf(NextFingerprint),
                rawHistory = listOf(NextFingerprint, Fingerprint),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizedSigningIdentity(listOf(NextFingerprint), listOf(Fingerprint))
        }
    }

    @Test
    fun `archive identity requires exact package version sdk debug state and installed lineage prefix`() {
        val release = release().copy(
            signingCertificateSha256Fingerprints = listOf(Fingerprint, NextFingerprint),
        )
        val artifact = DownloadedAppUpdateArtifact("${release.id}.apk", File("unused.apk"))
        val archive = ParsedAppUpdatePackageIdentity(
            packageName = PackageName,
            versionName = release.versionName,
            versionCode = release.versionCode,
            minimumAndroidSdk = release.minimumAndroidSdk,
            debuggable = false,
            currentSigners = listOf(NextFingerprint),
            rawSignerHistory = listOf(Fingerprint, NextFingerprint),
        )
        val installed = archive.copy(
            versionName = "0.9.0",
            versionCode = 42,
            currentSigners = listOf(Fingerprint),
            rawSignerHistory = listOf(Fingerprint),
        )

        val verified = verifyAppUpdatePackageIdentity(
            release,
            artifact,
            release.sizeBytes,
            release.sha256,
            archive,
            installed,
        )
        assertEquals(NextFingerprint, verified.currentSignerSha256Fingerprint)
        assertEquals(listOf(Fingerprint, NextFingerprint), verified.orderedSignerLineage)

        listOf(
            archive.copy(packageName = "com.attacker.inplacex"),
            archive.copy(versionCode = 42),
            archive.copy(versionName = "other"),
            archive.copy(minimumAndroidSdk = 30),
            archive.copy(debuggable = true),
            archive.copy(currentSigners = listOf(Fingerprint, NextFingerprint)),
            archive.copy(rawSignerHistory = listOf(NextFingerprint)),
        ).forEach { invalidArchive ->
            assertThrows(IllegalArgumentException::class.java) {
                verifyAppUpdatePackageIdentity(
                    release,
                    artifact,
                    release.sizeBytes,
                    release.sha256,
                    invalidArchive,
                    installed,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifyAppUpdatePackageIdentity(
                release,
                artifact,
                release.sizeBytes,
                release.sha256,
                archive,
                installed.copy(
                    currentSigners = listOf(NextFingerprint),
                    rawSignerHistory = listOf(NextFingerprint),
                ),
            )
        }
    }

    private fun runtime(
        catalog: StableAndroidUpdateCatalog,
        store: AppUpdatePolicyStore,
        installedVersionCode: Long = 42,
        installedAndroidSdk: Int = 35,
        cache: AppUpdateArtifactCache = MemoryArtifactCache(),
        verifier: AppUpdateArtifactVerifier = MatchingVerifier(),
        canInstall: () -> Boolean = { false },
        clockMs: () -> Long = { NowMs },
    ) = AppUpdateRuntime(
        catalog = catalog,
        store = store,
        originPolicy = AppUpdateOriginPolicy("https://games.dmit.life", false),
        artifactCache = cache,
        artifactVerifier = verifier,
        installedVersionCode = installedVersionCode,
        installedAndroidSdk = installedAndroidSdk,
        expectedPackageName = PackageName,
        canRequestPackageInstalls = canInstall,
        clockMs = clockMs,
    )

    private fun currentDecision() = AppUpdateCatalogDecision(false, false, null)

    private fun optionalDecision(release: AppUpdateRelease = release()) =
        AppUpdateCatalogDecision(updateAvailable = true, required = false, release = release)

    private fun requiredDecision() = AppUpdateCatalogDecision(
        updateAvailable = true,
        required = true,
        release = release(minimumSupportedVersionCode = 50),
    )

    private companion object {
        const val NowMs = 1_786_000_000_000L
        const val ReleaseId = "inplacex-1.0.0-100"
        const val PackageName = "com.mirkori.inplacex"
        val Fingerprint = List(32) { "AA" }.joinToString(":")
        val NextFingerprint = List(32) { "BB" }.joinToString(":")

        fun release(
            releaseId: String = ReleaseId,
            versionName: String = "1.0.0",
            versionCode: Long = 100,
            minimumSupportedVersionCode: Long = 1,
            minimumAndroidSdk: Int = 29,
            downloadUrl: String = "https://games.dmit.life/downloads/$releaseId/InplaceX-$versionName.apk",
        ) = AppUpdateRelease(
            id = releaseId,
            versionName = versionName,
            versionCode = versionCode,
            minimumSupportedVersionCode = minimumSupportedVersionCode,
            minimumAndroidSdk = minimumAndroidSdk,
            publishedAt = Instant.parse("2026-08-07T12:00:00Z"),
            changelog = "Release notes",
            fileName = "InplaceX-$versionName.apk",
            sizeBytes = 12_345_678,
            sha256 = "a".repeat(64),
            downloadUrl = downloadUrl,
            packageName = PackageName,
            signingCertificateSha256Fingerprints = listOf(Fingerprint),
        )
    }
}

private class MemoryUpdateStore(
    requiredRelease: AppUpdateRelease? = null,
    private val acceptWrites: Boolean = true,
    private var invalid: Boolean = false,
    private var missing: Boolean = false,
) : AppUpdatePolicyStore {
    var state = AppUpdatePolicyState(requiredRelease = requiredRelease)
    private var writeAttempts = 0
    private val failedWriteAttempts = mutableSetOf<Int>()

    fun failWriteAfter(additionalAttempts: Int) {
        require(additionalAttempts > 0)
        failedWriteAttempts += writeAttempts + additionalAttempts
    }

    override fun read(): AppUpdatePolicyReadResult = when {
        invalid -> AppUpdatePolicyReadResult.Invalid
        missing -> AppUpdatePolicyReadResult.Missing
        else -> AppUpdatePolicyReadResult.Available(state)
    }
    override fun write(state: AppUpdatePolicyState): Boolean {
        writeAttempts += 1
        val accepted = acceptWrites && writeAttempts !in failedWriteAttempts
        if (accepted) {
            this.state = state
            invalid = false
            missing = false
        }
        return accepted
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

private class BlockingSecondCatalog(
    private val firstDecision: AppUpdateCatalogDecision,
    private val secondDecision: AppUpdateCatalogDecision,
) : StableAndroidUpdateCatalog {
    val secondCallStarted = CompletableDeferred<Unit>()
    val releaseSecondCall = CompletableDeferred<Unit>()
    private var calls = 0

    override suspend fun check(installedVersionCode: Long): AppUpdateCatalogDecision {
        calls += 1
        if (calls == 1) return firstDecision
        secondCallStarted.complete(Unit)
        releaseSecondCall.await()
        return secondDecision
    }
}

private object ThrowingCatalog : StableAndroidUpdateCatalog {
    override suspend fun check(installedVersionCode: Long): AppUpdateCatalogDecision =
        throw java.io.IOException("offline")
}

private class MemoryArtifactCache(
    var failClear: Boolean = false,
) : AppUpdateArtifactCache {
    private val file = File("inplacex-test-cache.apk")
    var artifactPresent = false
        private set
    override suspend fun download(release: AppUpdateRelease): DownloadedAppUpdateArtifact {
        artifactPresent = true
        return DownloadedAppUpdateArtifact(
            cacheFileName = "${release.id}.apk",
            file = file,
        )
    }

    override fun resolve(cacheFileName: String): File? = file.takeIf { artifactPresent }
    override fun clear() {
        if (failClear) throw java.io.IOException("cleanup failed")
        artifactPresent = false
    }
}

private class MatchingVerifier : AppUpdateArtifactVerifier {
    override fun verify(
        release: AppUpdateRelease,
        artifact: DownloadedAppUpdateArtifact,
    ) = VerifiedAppUpdateArtifact(
        releaseId = release.id,
        cacheFileName = artifact.cacheFileName,
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
}
