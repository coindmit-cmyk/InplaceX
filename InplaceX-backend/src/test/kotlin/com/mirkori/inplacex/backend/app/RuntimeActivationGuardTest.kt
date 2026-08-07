package com.mirkori.inplacex.backend.app

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeActivationGuardTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: Path
    private lateinit var stateKey: Path
    private lateinit var publicKey: Path
    private lateinit var geoIp: Path
    private lateinit var verified: Path
    private lateinit var pending: Path
    private lateinit var identity: BackendReleaseIdentity
    private lateinit var clock: Clock

    @Before
    fun prepare() {
        root = temporaryFolder.root.toPath()
        stateKey = write("state.key", "state-key")
        publicKey = write("public.key", "public-key")
        geoIp = write("geo.mmdb", "geo-db")
        verified = root.resolve("verified.env")
        pending = root.resolve("pending.env")
        identity = BackendReleaseIdentity("release-1", "a".repeat(40), "sha256:${"b".repeat(64)}")
        clock = Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC)
    }

    @Test
    fun `exact verified activation authorizes runtime`() {
        writeActivation(verified)

        val guard = guard()
        guard.requireAuthorized()

        assertTrue(guard.isAuthorized())
    }

    @Test
    fun `live pending lease authorizes candidate`() {
        writeActivation(pending, clock.instant().epochSecond + 8)

        assertTrue(guard().refreshAuthorization())
    }

    @Test
    fun `expired lease and changed secret fail closed`() {
        writeActivation(pending, clock.instant().epochSecond - 1)
        assertFalse(guard().refreshAuthorization())

        writeActivation(verified)
        Files.writeString(stateKey, "rotated-state-key")
        assertFalse(guard().refreshAuthorization())
    }

    @Test
    fun `duplicate or unknown activation fields fail closed`() {
        writeActivation(verified)
        Files.writeString(
            verified,
            Files.readString(verified) + "INPLACEX_ACTIVATION_RELEASE_ID=release-1\n",
        )

        assertFalse(guard().refreshAuthorization())
    }

    private fun guard(): RuntimeActivationGuard = RuntimeActivationGuard.fromEnvironment(
        environment = mapOf(
            OnlineRuntimeConfig.StateEncryptionKeyPath to stateKey.toString(),
            OnlineRuntimeConfig.PublicKeyPathKey to publicKey.toString(),
            RuntimeActivationGuard.GeoIpFingerprintPathEnvironmentKey to geoIp.toString(),
            RuntimeActivationGuard.RuntimeConfigShaEnvironmentKey to "c".repeat(64),
            RuntimeActivationGuard.VerifiedStatePathEnvironmentKey to verified.toString(),
            RuntimeActivationGuard.PendingPermitPathEnvironmentKey to pending.toString(),
        ),
        identity = identity,
        clock = clock,
    )

    private fun writeActivation(path: Path, expiresAt: Long? = null) {
        val lines = mutableListOf(
            "INPLACEX_ACTIVATION_VERSION=1",
            "INPLACEX_ACTIVATION_RELEASE_ID=${identity.releaseId}",
            "INPLACEX_ACTIVATION_GIT_SHA=${identity.gitSha}",
            "INPLACEX_ACTIVATION_IMAGE_DIGEST=${identity.imageDigest}",
            "INPLACEX_ACTIVATION_STATE_KEY_SHA256=${sha256(stateKey)}",
            "INPLACEX_ACTIVATION_PUBLIC_KEY_SHA256=${sha256(publicKey)}",
            "INPLACEX_ACTIVATION_GEOIP_SHA256=${sha256(geoIp)}",
            "INPLACEX_ACTIVATION_RUNTIME_CONFIG_SHA256=${"c".repeat(64)}",
        )
        expiresAt?.let { lines += "INPLACEX_ACTIVATION_EXPIRES_AT_EPOCH_SECOND=$it" }
        Files.writeString(path, lines.joinToString("\n", postfix = "\n"))
    }

    private fun write(name: String, value: String): Path = root.resolve(name).also {
        Files.writeString(it, value)
    }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
