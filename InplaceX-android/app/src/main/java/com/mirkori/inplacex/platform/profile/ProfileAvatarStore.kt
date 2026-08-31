package com.mirkori.inplacex.platform.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.roundToInt

class ProfileAvatarStore(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "profile-avatars")

    fun current(gamePlayerId: String): String? = avatarFile(gamePlayerId)
        .takeIf(File::isFile)
        ?.let(::versionedPath)

    fun import(gamePlayerId: String, source: Uri): String? = runCatching {
        appContext.contentResolver.openAssetFileDescriptor(source, "r")?.use { descriptor ->
            val length = descriptor.length
            require(length == -1L || length in 1..MaxSourceBytes)
        }
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(appContext.contentResolver, source)) {
                decoder, info, _ ->
            require(info.size.width in 64..MaxSourceDimension)
            require(info.size.height in 64..MaxSourceDimension)
            val scale = min(1f, MaxDecodeDimension.toFloat() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(
                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val side = min(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(
            decoded,
            (decoded.width - side) / 2,
            (decoded.height - side) / 2,
            side,
            side,
        )
        val normalized = Bitmap.createScaledBitmap(square, OutputSize, OutputSize, true)
        directory.mkdirs()
        val target = avatarFile(gamePlayerId)
        val temporary = File(directory, "${target.name}.tmp")
        temporary.outputStream().buffered().use { output ->
            check(normalized.compress(Bitmap.CompressFormat.JPEG, 90, output))
        }
        check(temporary.length() in 1..MaxOutputBytes)
        if (target.exists()) check(target.delete())
        check(temporary.renameTo(target))
        versionedPath(target)
    }.getOrNull()

    fun clear(gamePlayerId: String) {
        avatarFile(gamePlayerId).delete()
    }

    private fun avatarFile(gamePlayerId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(gamePlayerId.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(directory, "$digest.jpg")
    }

    private fun versionedPath(file: File): String = "${file.absolutePath}?v=${file.lastModified()}"

    private companion object {
        const val OutputSize = 512
        const val MaxDecodeDimension = 2048
        const val MaxSourceDimension = 20_000
        const val MaxSourceBytes = 20L * 1024 * 1024
        const val MaxOutputBytes = 2L * 1024 * 1024
    }
}
