package com.mirkori.inplacex.platform.services

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.mirkori.inplacex.platform.config.GooglePlayProviderConfig

data class GoogleCredential(
    val idToken: String,
    val playerName: String?,
) {
    override fun toString(): String = "GoogleCredential([redacted])"
}

sealed interface GoogleCredentialResult {
    data class Success(val credential: GoogleCredential) : GoogleCredentialResult
    data object Cancelled : GoogleCredentialResult
    data object Unavailable : GoogleCredentialResult
    data object Failed : GoogleCredentialResult
}

class GoogleCredentialSignIn(
    context: Context,
    private val config: GooglePlayProviderConfig,
) {
    private val credentialManager = CredentialManager.create(context)

    suspend fun signIn(
        activity: Activity,
        nonce: String,
    ): GoogleCredentialResult {
        if (!config.isConfigured || !nonce.matches(Regex("[A-Za-z0-9_-]{32,128}"))) {
            return GoogleCredentialResult.Unavailable
        }
        return request(activity, nonce, filterAuthorizedAccounts = true).let { authorized ->
            if (authorized == GoogleCredentialResult.Unavailable) {
                request(activity, nonce, filterAuthorizedAccounts = false)
            } else {
                authorized
            }
        }
    }

    suspend fun signOut(): Boolean = runCatching {
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
        true
    }.getOrDefault(false)

    private suspend fun request(
        activity: Activity,
        nonce: String,
        filterAuthorizedAccounts: Boolean,
    ): GoogleCredentialResult {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterAuthorizedAccounts)
            .setServerClientId(config.webClientId)
            .setNonce(nonce)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        return try {
            val credential = credentialManager.getCredential(
                context = activity,
                request = request,
            ).credential
            if (
                credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                GoogleCredentialResult.Failed
            } else {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                val token = google.idToken.takeIf {
                    it.length in 1..8_192 && it.none(Char::isWhitespace)
                } ?: return GoogleCredentialResult.Failed
                GoogleCredentialResult.Success(
                    GoogleCredential(
                        idToken = token,
                        playerName = google.displayName
                            ?.trim()
                            ?.takeIf { it.length in 1..120 && it.none(Char::isISOControl) },
                    ),
                )
            }
        } catch (_: NoCredentialException) {
            GoogleCredentialResult.Unavailable
        } catch (_: GetCredentialException) {
            GoogleCredentialResult.Cancelled
        } catch (_: IllegalArgumentException) {
            GoogleCredentialResult.Failed
        }
    }
}
