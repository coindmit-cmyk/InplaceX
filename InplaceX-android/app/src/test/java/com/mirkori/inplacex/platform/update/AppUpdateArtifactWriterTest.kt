package com.mirkori.inplacex.platform.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdateArtifactWriterTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `stream is bounded hashed synced and retained only when exact`() = runBlocking {
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        val target = File(temporary.root, "release.part")
        var offset = 0

        writeBoundedVerifiedArtifact(target, bytes.size.toLong(), sha256(bytes)) { buffer ->
            if (offset == bytes.size) {
                -1
            } else {
                val count = minOf(buffer.size, bytes.size - offset)
                bytes.copyInto(buffer, endIndex = offset + count, startIndex = offset)
                offset += count
                count
            }
        }

        assertArrayEquals(bytes, target.readBytes())
    }

    @Test
    fun `oversize short and digest mismatch streams remove partial file`() {
        listOf(
            ByteArray(11) { 1 } to 10L,
            ByteArray(9) { 1 } to 10L,
            ByteArray(10) { 1 } to 10L,
        ).forEachIndexed { index, (bytes, expectedSize) ->
            val target = File(temporary.root, "bad-$index.part")
            assertThrows(Exception::class.java) {
                runBlocking {
                    var served = false
                    writeBoundedVerifiedArtifact(
                        target = target,
                        expectedSize = expectedSize,
                        expectedSha256 = if (index == 2) "0".repeat(64) else sha256(bytes),
                    ) { buffer ->
                        if (served) -1 else bytes.copyInto(buffer).let { served = true; bytes.size }
                    }
                }
            }
            assertFalse(target.exists())
        }
    }

    @Test
    fun `stale directories are removed recursively without following entries`() {
        val root = temporary.newFolder("cache-root").toPath()
        val stale = Files.createDirectory(root.resolve("stale"))
        Files.write(stale.resolve("artifact.apk"), byteArrayOf(1, 2, 3))

        deleteEntryNoFollow(root, stale)

        assertFalse(Files.exists(stale))
    }

    @Test
    fun `symlink cleanup removes the link and preserves its target`() {
        val root = temporary.newFolder("symlink-root").toPath()
        val outside = temporary.newFile("outside.apk").toPath()
        Files.write(outside, byteArrayOf(7, 8, 9))
        val link = root.resolve("stale.apk")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (error: Exception) {
            assumeNoException(error)
        }

        deleteEntryNoFollow(root, link)

        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(outside))
    }

    @Test
    fun `directory fsync failure removes atomically moved target`() {
        val root = temporary.newFolder("atomic-root")
        val part = File(root, "release.part").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val target = File(root, "release.apk")

        assertThrows(IOException::class.java) {
            publishAtomicArtifact(
                part = part,
                target = target,
                root = root,
                syncDirectory = { throw IOException("fsync failed") },
                move = { source, destination ->
                    Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
                },
            )
        }

        assertFalse(part.exists())
        assertFalse(target.exists())
    }

    @Test
    fun `download cancels the streaming body for every required header mismatch`() {
        val bytes = byteArrayOf(1, 2, 3)
        val digest = sha256(bytes)
        val release = release(bytes)
        val mismatches = listOf(
            "missing-content-length" to headersOf(
                HttpHeaders.ContentType to listOf("application/vnd.android.package-archive"),
                "X-Artifact-SHA256" to listOf(digest),
            ),
            "wrong-content-length" to headersOf(
                HttpHeaders.ContentLength to listOf((bytes.size + 1).toString()),
                HttpHeaders.ContentType to listOf("application/vnd.android.package-archive"),
                "X-Artifact-SHA256" to listOf(digest),
            ),
            "missing-sha256" to headersOf(
                HttpHeaders.ContentLength to listOf(bytes.size.toString()),
                HttpHeaders.ContentType to listOf("application/vnd.android.package-archive"),
            ),
            "wrong-sha256" to headersOf(
                HttpHeaders.ContentLength to listOf(bytes.size.toString()),
                HttpHeaders.ContentType to listOf("application/vnd.android.package-archive"),
                "X-Artifact-SHA256" to listOf("0".repeat(64)),
            ),
            "missing-content-type" to headersOf(
                HttpHeaders.ContentLength to listOf(bytes.size.toString()),
                "X-Artifact-SHA256" to listOf(digest),
            ),
            "wrong-content-type" to headersOf(
                HttpHeaders.ContentLength to listOf(bytes.size.toString()),
                HttpHeaders.ContentType to listOf("application/octet-stream"),
                "X-Artifact-SHA256" to listOf(digest),
            ),
        )

        mismatches.forEach { (name, headers) ->
            val body = ByteReadChannel(ByteArray(128 * 1024) { 7 })
            val client = HttpClient(
                MockEngine {
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headers,
                    )
                },
            ) {
                applyAppUpdateDownloadClientPolicy()
            }
            val cache = AndroidAppUpdateArtifactCache(
                File(temporary.newFolder("headers-$name"), "app-updates"),
                client,
            )

            try {
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { cache.download(release) }
                }
                assertTrue("$name response body was not cancelled", body.isClosedForRead)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `download client cancels redirect body after exactly one request`() {
        val bytes = byteArrayOf(1, 2, 3)
        val digest = sha256(bytes)
        val release = release(bytes)
        val responseBody = ByteReadChannel(ByteArray(128 * 1024) { 7 })
        var requests = 0
        val client = HttpClient(
            MockEngine {
                requests += 1
                if (requests == 1) {
                    respond(
                        content = responseBody,
                        status = HttpStatusCode.TemporaryRedirect,
                        headers = headersOf(HttpHeaders.Location, "https://games.dmit.life/redirected.apk"),
                    )
                } else {
                    respond(
                        content = ByteReadChannel(bytes),
                        status = HttpStatusCode.OK,
                        headers = headersOf(
                            HttpHeaders.ContentLength to listOf(bytes.size.toString()),
                            HttpHeaders.ContentType to listOf("application/vnd.android.package-archive"),
                            "X-Artifact-SHA256" to listOf(digest),
                        ),
                    )
                }
            },
        ) {
            applyAppUpdateDownloadClientPolicy()
        }
        val cacheDirectory = File(temporary.root, "app-updates")
        val cache = AndroidAppUpdateArtifactCache(cacheDirectory, client)

        try {
            assertThrows(IOException::class.java) {
                runBlocking { cache.download(release) }
            }
            assertTrue(responseBody.isClosedForRead)
            assertEquals(1, requests)
            assertFalse(File(cacheDirectory, "${release.id}.part").exists())
            assertFalse(File(cacheDirectory, "${release.id}.apk").exists())
        } finally {
            client.close()
        }
    }

    private fun release(bytes: ByteArray) = AppUpdateRelease(
        id = "inplacex-1.0.0-100",
        versionName = "1.0.0",
        versionCode = 100,
        minimumSupportedVersionCode = 50,
        minimumAndroidSdk = 29,
        publishedAt = Instant.parse("2026-08-07T12:00:00Z"),
        changelog = "Update",
        fileName = "InplaceX-1.0.0.apk",
        sizeBytes = bytes.size.toLong(),
        sha256 = sha256(bytes),
        downloadUrl = "https://games.dmit.life/downloads/inplacex-1.0.0-100/InplaceX-1.0.0.apk",
        packageName = "com.mirkori.inplacex",
        signingCertificateSha256Fingerprints = listOf("AA:".repeat(31) + "AA"),
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
