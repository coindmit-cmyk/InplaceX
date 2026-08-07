package com.mirkori.inplacex.backend.app

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

data class RuntimeDrainSnapshot(
    val draining: Boolean,
    val activeRequests: Int,
)

class RuntimeDrainController private constructor(
    private val drainMarker: Path?,
) {
    private val lock = Any()
    private var activeRequests = 0

    fun tryAcquireOnlineRequest(): RequestLease? = synchronized(lock) {
        if (isDrainRequested()) return@synchronized null
        activeRequests += 1
        RequestLease(::release)
    }

    fun snapshot(): RuntimeDrainSnapshot = synchronized(lock) {
        RuntimeDrainSnapshot(
            draining = isDrainRequested(),
            activeRequests = activeRequests,
        )
    }

    private fun isDrainRequested(): Boolean {
        val marker = drainMarker ?: return false
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return false
        return true
    }

    private fun release() = synchronized(lock) {
        check(activeRequests > 0) { "Online request drain lease was released more than once" }
        activeRequests -= 1
    }

    class RequestLease internal constructor(
        private val release: () -> Unit,
    ) : AutoCloseable {
        private var closed = false

        override fun close() = synchronized(this) {
            if (!closed) {
                closed = true
                release()
            }
        }
    }

    companion object {
        const val DrainMarkerPathEnvironmentKey = "INPLACEX_RUNTIME_DRAIN_FILE_PATH"

        fun fromEnvironment(environment: Map<String, String>, production: Boolean): RuntimeDrainController {
            val configured = environment[DrainMarkerPathEnvironmentKey]?.trim()?.takeIf(String::isNotEmpty)
            if (!production && configured == null) return RuntimeDrainController(null)
            require(configured != null) { "$DrainMarkerPathEnvironmentKey is required in production" }
            val path = Path.of(configured)
            require(path.isAbsolute) { "$DrainMarkerPathEnvironmentKey must be absolute" }
            return RuntimeDrainController(path)
        }

        fun disabled(): RuntimeDrainController = RuntimeDrainController(null)
    }
}
