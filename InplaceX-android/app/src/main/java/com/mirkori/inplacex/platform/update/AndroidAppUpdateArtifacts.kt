package com.mirkori.inplacex.platform.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import com.mirkori.inplacex.BuildConfig
import com.mirkori.inplacex.platform.mirkori.KtorMirkoriPlatformTransport
import com.mirkori.inplacex.platform.mirkori.createMirkoriHttpClient
import com.mirkori.inplacex.platform.online.AndroidConnectivityGate
import com.mirkori.platform.sdk.MirkoriGameSdk
import com.mirkori.platform.sdk.MirkoriGameSdkConfig
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

internal data class AndroidAppUpdateComponents(
    val runtime: AppUpdateRuntime,
    val installer: AndroidAppUpdateInstaller,
)

internal object AndroidAppUpdateFactory {
    fun createOrNull(context: Context): AndroidAppUpdateComponents? {
        if (!BuildConfig.SIDELOAD_UPDATES_ENABLED) return null
        val applicationContext = context.applicationContext
        val baseUrl = BuildConfig.MIRKORI_PLATFORM_BASE_URL
        require(baseUrl.isNotBlank())
        val catalogClient = createMirkoriHttpClient()
        val downloadClient = createAppUpdateDownloadClient()
        return try {
            val sdk = MirkoriGameSdk(
                config = MirkoriGameSdkConfig(
                    platformBaseUrl = baseUrl,
                    gameId = GameId,
                    redirectUri = LoginRedirectUri,
                    allowCleartextLoopback = false,
                ),
                transport = KtorMirkoriPlatformTransport(
                    client = catalogClient,
                    connectivity = AndroidConnectivityGate(applicationContext),
                ),
            )
            val cache = AndroidAppUpdateArtifactCache(applicationContext, downloadClient)
            val installer = AndroidAppUpdateInstaller(applicationContext, cache)
            val runtime = AppUpdateRuntime(
                catalog = platformUpdateCatalog(sdk::stableAndroidUpdate),
                store = AndroidAppUpdatePolicyStore(applicationContext),
                originPolicy = AppUpdateOriginPolicy(baseUrl, allowCleartextLoopback = false),
                artifactCache = cache,
                artifactVerifier = AndroidAppUpdateArtifactVerifier(applicationContext),
                installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
                installedAndroidSdk = android.os.Build.VERSION.SDK_INT,
                expectedPackageName = BuildConfig.APPLICATION_ID,
                canRequestPackageInstalls = installer::canRequestPackageInstalls,
                closeAction = {
                    catalogClient.close()
                    downloadClient.close()
                },
            )
            AndroidAppUpdateComponents(runtime, installer)
        } catch (error: Exception) {
            catalogClient.close()
            downloadClient.close()
            throw error
        }
    }

    private const val GameId = "inplacex"
    private const val LoginRedirectUri = "https://games.dmit.life/connect/inplacex/callback"
}

internal class AndroidAppUpdateInstaller(
    context: Context,
    private val cache: AppUpdateArtifactCache,
) {
    private val applicationContext = context.applicationContext

    fun canRequestPackageInstalls(): Boolean = applicationContext.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${applicationContext.packageName}"),
    )

    fun installIntent(artifact: VerifiedAppUpdateArtifact): Intent {
        val file = requireNotNull(cache.resolve(artifact.cacheFileName))
        val uri = FileProvider.getUriForFile(
            applicationContext,
            "${applicationContext.packageName}.update-files",
            file,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, AndroidPackageMimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

internal class AndroidAppUpdateArtifactCache internal constructor(
    private val directory: File,
    private val client: HttpClient,
) : AppUpdateArtifactCache {
    constructor(context: Context, client: HttpClient) : this(
        File(context.applicationContext.cacheDir, CacheDirectoryName),
        client,
    )

    override suspend fun download(release: AppUpdateRelease): DownloadedAppUpdateArtifact {
        val root = ensurePrivateDirectory()
        val part = File(root, "${release.id}.part")
        val target = File(root, "${release.id}.apk")
        requireDirectChild(root, part)
        requireDirectChild(root, target)
        clearDirectory(root)
        try {
            val response = client.request {
                method = HttpMethod.Get
                url(release.downloadUrl)
                header(HttpHeaders.Accept, AndroidPackageMimeType)
                header(HttpHeaders.AcceptEncoding, "identity")
            }
            val channel = response.bodyAsChannel()
            try {
                if (response.status != HttpStatusCode.OK) {
                    throw IOException("Unexpected APK response")
                }
                val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                require(declaredLength == release.sizeBytes)
                require(declaredLength in 1..MaximumArtifactBytes)
                require(response.headers[ArtifactSha256Header] == release.sha256)
                require(response.headers[HttpHeaders.ContentType]?.substringBefore(';') == AndroidPackageMimeType)
                writeBoundedVerifiedArtifact(
                    target = part,
                    expectedSize = release.sizeBytes,
                    expectedSha256 = release.sha256,
                    read = { buffer -> channel.readAvailable(buffer, 0, buffer.size) },
                )
            } catch (error: Exception) {
                channel.cancel(error)
                throw error
            }
            publishAtomicArtifact(part, target, root, ::syncDirectory)
            return DownloadedAppUpdateArtifact(target.name, target)
        } catch (cancelled: CancellationException) {
            runCatching { deleteEntryNoFollow(root.toPath(), part.toPath()) }
            throw cancelled
        } catch (error: Exception) {
            runCatching { deleteEntryNoFollow(root.toPath(), part.toPath()) }
            throw error
        }
    }

    override fun resolve(cacheFileName: String): File? {
        if (!cacheFileName.matches(CacheFileNamePattern)) return null
        val root = runCatching { ensurePrivateDirectory() }.getOrNull() ?: return null
        val candidate = File(root, cacheFileName)
        return candidate.takeIf {
            runCatching {
                requireDirectChild(root, it)
                it.isFile && !Files.isSymbolicLink(it.toPath())
            }.getOrDefault(false)
        }
    }

    override fun clear() {
        val root = ensurePrivateDirectory()
        clearDirectory(root)
        syncDirectory(root)
    }

    private fun ensurePrivateDirectory(): File {
        val cacheRoot = requireNotNull(directory.parentFile).toPath().toAbsolutePath().normalize()
        val path = directory.toPath().toAbsolutePath().normalize()
        require(path.parent == cacheRoot)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
        ) {
            deleteEntryNoFollow(cacheRoot, path)
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path)
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
        return path.toFile()
    }

    private fun clearDirectory(root: File) {
        requireNotNull(root.listFiles()).forEach { file ->
            requireDirectChild(root, file)
            deleteEntryNoFollow(root.toPath(), file.toPath())
        }
    }

    private fun requireDirectChild(root: File, child: File) {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val childPath = child.toPath().toAbsolutePath().normalize()
        require(childPath.parent == rootPath)
    }

    private fun syncDirectory(root: File) {
        val descriptor = Os.open(root.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    private companion object {
        const val CacheDirectoryName = "app-updates"
        const val ArtifactSha256Header = "X-Artifact-SHA256"
        const val MaximumArtifactBytes = 4L * 1024L * 1024L * 1024L
        val CacheFileNamePattern = Regex("[a-z0-9][a-z0-9._-]{1,63}\\.apk")
    }
}

internal fun deleteEntryNoFollow(root: Path, entry: Path) {
    val normalizedRoot = root.toAbsolutePath().normalize()
    val normalizedEntry = entry.toAbsolutePath().normalize()
    require(normalizedEntry.parent == normalizedRoot)
    if (!Files.exists(normalizedEntry, LinkOption.NOFOLLOW_LINKS)) return
    if (Files.isDirectory(normalizedEntry, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(normalizedEntry)) {
        Files.newDirectoryStream(normalizedEntry).use { children ->
            children.forEach { child -> deleteEntryNoFollow(normalizedEntry, child) }
        }
    }
    Files.deleteIfExists(normalizedEntry)
}

internal fun publishAtomicArtifact(
    part: File,
    target: File,
    root: File,
    syncDirectory: (File) -> Unit,
    move: (Path, Path) -> Unit = { source, destination ->
        try {
            Files.move(
                source,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IOException("APK cache does not support atomic publication", unsupported)
        }
    },
) {
    var moved = false
    try {
        move(part.toPath(), target.toPath())
        moved = true
        syncDirectory(root)
    } catch (error: Exception) {
        if (moved) {
            runCatching { deleteEntryNoFollow(root.toPath(), target.toPath()) }
            runCatching { syncDirectory(root) }
        }
        runCatching { deleteEntryNoFollow(root.toPath(), part.toPath()) }
        throw error
    }
}

internal class AndroidAppUpdateArtifactVerifier(context: Context) : AppUpdateArtifactVerifier {
    private val packageManager = context.applicationContext.packageManager
    private val installedPackageName = context.applicationContext.packageName

    override fun verify(
        release: AppUpdateRelease,
        artifact: DownloadedAppUpdateArtifact,
    ): VerifiedAppUpdateArtifact {
        require(artifact.file.isFile && !Files.isSymbolicLink(artifact.file.toPath()))
        val size = artifact.file.length()
        require(size == release.sizeBytes)
        val digest = sha256(artifact.file)
        require(digest == release.sha256)
        val archive = requireNotNull(
            packageManager.getPackageArchiveInfo(
                artifact.file.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ),
        )
        archive.applicationInfo?.apply {
            sourceDir = artifact.file.absolutePath
            publicSourceDir = artifact.file.absolutePath
        }
        val installed = packageManager.getPackageInfo(
            installedPackageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        return verifyAppUpdatePackageIdentity(
            release = release,
            artifact = artifact,
            artifactSizeBytes = size,
            artifactSha256 = digest,
            archive = archive.toParsedIdentity(),
            installed = installed.toParsedIdentity(),
        )
    }

    private fun PackageInfo.toParsedIdentity(): ParsedAppUpdatePackageIdentity {
        val application = requireNotNull(applicationInfo)
        val signing = requireNotNull(signingInfo)
        require(!signing.hasMultipleSigners())
        return ParsedAppUpdatePackageIdentity(
            packageName = packageName,
            versionName = requireNotNull(versionName),
            versionCode = longVersionCode,
            minimumAndroidSdk = application.minSdkVersion,
            debuggable = application.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            currentSigners = signing.apkContentsSigners.orEmpty().map(Signature::fingerprint),
            rawSignerHistory = signing.signingCertificateHistory.orEmpty().map(Signature::fingerprint),
        )
    }
}

private fun createAppUpdateDownloadClient(): HttpClient = HttpClient(OkHttp) {
    applyAppUpdateDownloadClientPolicy()
    engine {
        config {
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
        }
    }
}

internal fun HttpClientConfig<*>.applyAppUpdateDownloadClientPolicy() {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000L
        requestTimeoutMillis = 10L * 60L * 1_000L
        socketTimeoutMillis = 30_000L
    }
}

internal suspend fun writeBoundedVerifiedArtifact(
    target: File,
    expectedSize: Long,
    expectedSha256: String,
    read: suspend (ByteArray) -> Int,
) {
    require(expectedSize in 1..4L * 1024L * 1024L * 1024L)
    require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    try {
        FileOutputStream(target, false).use { output ->
            while (true) {
                val count = read(buffer)
                if (count == -1) break
                require(count in 0..buffer.size)
                if (count == 0) continue
                if (count.toLong() > expectedSize - total) {
                    throw IOException("APK response exceeded declared size")
                }
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                total += count
            }
            output.fd.sync()
        }
        require(total == expectedSize)
        require(digest.digest().toHexLowercase() == expectedSha256)
    } catch (error: Exception) {
        target.delete()
        throw error
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHexLowercase()
}

private fun Signature.fingerprint(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray())
    .joinToString(":") { "%02X".format(it.toInt() and 0xff) }

private fun ByteArray.toHexLowercase(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

private const val AndroidPackageMimeType = "application/vnd.android.package-archive"
