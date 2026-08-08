package com.mirkori.inplacex.backend.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
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
    private lateinit var databasePassword: Path
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
        databasePassword = write("database-password.txt", "database-password")
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
    fun `live v2 lease authorizes migration candidate beside legacy verified state`() {
        writeLegacyActivation(verified)
        writeActivation(pending, clock.instant().epochSecond + 8)

        assertTrue(guard().refreshAuthorization())
    }

    @Test
    fun `malformed legacy state blocks a live v2 lease`() {
        Files.writeString(
            verified,
            listOf(
                "INPLACEX_ACTIVATION_VERSION=1",
                "INPLACEX_ACTIVATION_RELEASE_ID=legacy-release",
            ).joinToString("\n", postfix = "\n"),
        )
        writeActivation(pending, clock.instant().epochSecond + 8)

        assertFalse(guard().refreshAuthorization())
    }

    @Test
    fun `expired lease fails closed`() {
        writeActivation(pending, clock.instant().epochSecond - 1)
        assertFalse(guard().refreshAuthorization())
    }

    @Test
    fun `mounted fingerprint changes after startup fail closed`() {
        listOf(databasePassword, stateKey, publicKey, geoIp).forEach { path ->
            writeActivation(verified)
            val runningGuard = guard()
            assertTrue(runningGuard.refreshAuthorization())

            val original = Files.readString(path)
            Files.writeString(path, "$original-tampered")
            assertFalse(runningGuard.refreshAuthorization())
            Files.writeString(path, original)
        }
    }

    @Test
    fun `unchanged GeoIP metadata reuses digest and a mounted change is rehashed`() {
        writeActivation(verified)
        var geoIpReads = 0
        val runningGuard = guard { path ->
            geoIpReads += 1
            sha256(path)
        }

        repeat(4) { assertTrue(runningGuard.refreshAuthorization()) }
        assertEquals(1, geoIpReads)

        val modifiedTime = Files.getLastModifiedTime(geoIp)
        Files.writeString(geoIp, "geo-xx")
        Files.setLastModifiedTime(geoIp, FileTime.fromMillis(modifiedTime.toMillis() + 1_000L))

        assertFalse(runningGuard.refreshAuthorization())
        assertEquals(2, geoIpReads)
    }

    @Test
    fun `duplicate or unknown activation fields fail closed`() {
        writeActivation(verified)
        writeActivation(pending, clock.instant().epochSecond + 8)
        Files.writeString(
            verified,
            Files.readString(verified) + "INPLACEX_ACTIVATION_RELEASE_ID=release-1\n",
        )

        assertFalse(guard().refreshAuthorization())
    }

    private fun guard(
        geoIpDigest: (Path) -> String = ::sha256,
    ): RuntimeActivationGuard = RuntimeActivationGuard.fromEnvironment(
        environment = mapOf(
            OnlineRuntimeConfig.StateEncryptionKeyPath to stateKey.toString(),
            OnlineRuntimeConfig.PublicKeyPathKey to publicKey.toString(),
            DatabaseRuntimeConfig.PasswordPathEnvironmentKey to databasePassword.toString(),
            RuntimeActivationGuard.GeoIpFingerprintPathEnvironmentKey to geoIp.toString(),
            RuntimeActivationGuard.RuntimeConfigShaEnvironmentKey to "c".repeat(64),
            RuntimeActivationGuard.VerifiedStatePathEnvironmentKey to verified.toString(),
            RuntimeActivationGuard.PendingPermitPathEnvironmentKey to pending.toString(),
        ),
        identity = identity,
        clock = clock,
        geoIpDigest = geoIpDigest,
    )

    private fun writeActivation(path: Path, expiresAt: Long? = null) {
        val lines = mutableListOf(
            "INPLACEX_ACTIVATION_VERSION=2",
            "INPLACEX_ACTIVATION_RELEASE_ID=${identity.releaseId}",
            "INPLACEX_ACTIVATION_GIT_SHA=${identity.gitSha}",
            "INPLACEX_ACTIVATION_IMAGE_DIGEST=${identity.imageDigest}",
            "INPLACEX_ACTIVATION_DATABASE_PASSWORD_SHA256=${sha256(databasePassword)}",
            "INPLACEX_ACTIVATION_STATE_KEY_SHA256=${sha256(stateKey)}",
            "INPLACEX_ACTIVATION_PUBLIC_KEY_SHA256=${sha256(publicKey)}",
            "INPLACEX_ACTIVATION_GEOIP_SHA256=${sha256(geoIp)}",
            "INPLACEX_ACTIVATION_RUNTIME_CONFIG_SHA256=${"c".repeat(64)}",
        )
        expiresAt?.let { lines += "INPLACEX_ACTIVATION_EXPIRES_AT_EPOCH_SECOND=$it" }
        Files.writeString(path, lines.joinToString("\n", postfix = "\n"))
    }

    private fun writeLegacyActivation(path: Path) {
        val lines = listOf(
            "INPLACEX_ACTIVATION_VERSION=1",
            "INPLACEX_ACTIVATION_RELEASE_ID=legacy-release",
            "INPLACEX_ACTIVATION_GIT_SHA=${"d".repeat(40)}",
            "INPLACEX_ACTIVATION_IMAGE_DIGEST=sha256:${"e".repeat(64)}",
            "INPLACEX_ACTIVATION_STATE_KEY_SHA256=${sha256(stateKey)}",
            "INPLACEX_ACTIVATION_PUBLIC_KEY_SHA256=${sha256(publicKey)}",
            "INPLACEX_ACTIVATION_GEOIP_SHA256=${sha256(geoIp)}",
            "INPLACEX_ACTIVATION_RUNTIME_CONFIG_SHA256=${"f".repeat(64)}",
        )
        Files.writeString(path, lines.joinToString("\n", postfix = "\n"))
    }

    private fun write(name: String, value: String): Path = root.resolve(name).also {
        Files.writeString(it, value)
    }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
