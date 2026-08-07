package com.mirkori.inplacex.backend.app

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeDrainControllerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `drain marker rejects new calls and waits for existing lease`() {
        val marker = temporaryFolder.root.toPath().resolve("drain.flag")
        val controller = RuntimeDrainController.fromEnvironment(
            mapOf(RuntimeDrainController.DrainMarkerPathEnvironmentKey to marker.toString()),
            production = true,
        )
        val lease = controller.tryAcquireOnlineRequest()
        assertNotNull(lease)
        assertEquals(1, controller.snapshot().activeRequests)

        Files.writeString(marker, "deployment-id\n")

        assertTrue(controller.snapshot().draining)
        assertNull(controller.tryAcquireOnlineRequest())
        requireNotNull(lease).close()
        assertEquals(0, controller.snapshot().activeRequests)
    }

    @Test
    fun `development controller remains open without a marker path`() {
        val controller = RuntimeDrainController.fromEnvironment(emptyMap(), production = false)

        assertFalse(controller.snapshot().draining)
        requireNotNull(controller.tryAcquireOnlineRequest()).close()
    }
}
