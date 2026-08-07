package com.mirkori.inplacex.backend.app

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal object RuntimeSecretFile {
    fun readText(path: Path, minimumCharacters: Int, maximumBytes: Int): String {
        require(minimumCharacters > 0 && maximumBytes >= minimumCharacters)
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Runtime secret path must be a regular file"
        }
        val bytes = Files.newByteChannel(
            path,
            setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            val size = channel.size()
            require(size in minimumCharacters.toLong()..maximumBytes.toLong()) {
                "Runtime secret file has an invalid size"
            }
            val target = ByteBuffer.allocate(size.toInt())
            while (target.hasRemaining()) {
                require(channel.read(target) >= 0) { "Runtime secret file changed while it was read" }
            }
            target.array()
        }
        return try {
            val decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .trimEnd('\r', '\n')
            require(decoded.length >= minimumCharacters && decoded.toByteArray(StandardCharsets.UTF_8).size <= maximumBytes)
            require(decoded.none { it.isISOControl() || it.isWhitespace() }) {
                "Runtime secret must not contain whitespace or control characters"
            }
            decoded
        } finally {
            bytes.fill(0)
        }
    }
}
