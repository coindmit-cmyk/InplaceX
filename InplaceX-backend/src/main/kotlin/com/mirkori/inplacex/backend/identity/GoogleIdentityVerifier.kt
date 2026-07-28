package com.mirkori.inplacex.backend.identity

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

data class VerifiedGoogleIdentity(
    val subject: String,
    val displayName: String?,
)

fun interface GoogleIdentityVerifier {
    fun verify(idToken: String, expectedNonce: String): VerifiedGoogleIdentity?
}

class GoogleApiIdentityVerifier(
    webClientId: String,
) : GoogleIdentityVerifier {
    private val delegate = GoogleIdTokenVerifier.Builder(
        GoogleNetHttpTransport.newTrustedTransport(),
        GsonFactory.getDefaultInstance(),
    )
        .setAudience(Collections.singletonList(webClientId))
        .build()

    init {
        require(webClientId.isSafeProviderClientId()) {
            "Google web client ID has an invalid format"
        }
    }

    override fun verify(idToken: String, expectedNonce: String): VerifiedGoogleIdentity? {
        if (idToken.length !in 1..MaximumGoogleIdTokenCharacters) return null
        if (!expectedNonce.matches(NoncePattern)) return null
        val token = runCatching { delegate.verify(idToken) }.getOrNull() ?: return null
        val payload = token.payload
        val nonce = payload["nonce"] as? String ?: return null
        if (!constantTimeEquals(expectedNonce, nonce)) return null
        val subject = payload.subject
            ?.takeIf { it.length in 1..255 && it.none(Char::isISOControl) }
            ?: return null
        val displayName = (payload["name"] as? String)
            ?.trim()
            ?.takeIf { it.length in 1..120 && it.none(Char::isISOControl) }
        return VerifiedGoogleIdentity(subject = subject, displayName = displayName)
    }

    override fun toString(): String = "GoogleApiIdentityVerifier([configured])"
}

fun String.isSafeProviderClientId(): Boolean =
    length in 8..512 && none { it.isISOControl() || it.isWhitespace() }

private fun constantTimeEquals(expected: String, actual: String): Boolean =
    MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.UTF_8),
        actual.toByteArray(StandardCharsets.UTF_8),
    )

private const val MaximumGoogleIdTokenCharacters = 8_192
private val NoncePattern = Regex("[A-Za-z0-9_-]{32,128}")
