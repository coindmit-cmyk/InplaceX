package com.mirkori.inplacex.platform.mirkori

import java.security.KeyPairGenerator
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MirkoriProClientConfigurationTest {
    @Test
    fun disabledConfigurationDoesNotActivatePro() {
        assertNull(
            MirkoriProClientConfiguration.parseOrNull(
                enabled = false,
                distributionId = "",
                encodedPublicKeys = "",
            ),
        )
    }

    @Test
    fun completeConfigurationPinsRsaKeysToDistribution() {
        val publicKey = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
            .public
        val configuration = MirkoriProClientConfiguration.parseOrNull(
            enabled = true,
            distributionId = "rf-mirkori",
            encodedPublicKeys = "pro-2026=${Base64.getEncoder().encodeToString(publicKey.encoded)}",
        )

        assertNotNull(configuration)
        assertEquals("rf-mirkori", configuration?.distributionId)
        assertNotNull(configuration?.snapshotVerifier)
    }

    @Test
    fun enabledConfigurationRejectsMissingOrMalformedTrustMaterial() {
        assertThrows(IllegalArgumentException::class.java) {
            MirkoriProClientConfiguration.parseOrNull(true, "", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MirkoriProClientConfiguration.parseOrNull(true, "rf-mirkori", "pro-2026=not-base64")
        }
    }

    @Test
    fun enabledConfigurationRejectsDuplicateKeyIds() {
        val encoded = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
            .public
            .encoded
            .let(Base64.getEncoder()::encodeToString)

        assertThrows(IllegalArgumentException::class.java) {
            MirkoriProClientConfiguration.parseOrNull(
                enabled = true,
                distributionId = "rf-mirkori",
                encodedPublicKeys = "pro-2026=$encoded;pro-2026=$encoded",
            )
        }
    }
}
