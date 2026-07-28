package com.mirkori.inplacex.platform.online

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreGuestSessionStore(
    context: Context,
    private val preferencesName: String = DefaultPreferencesName,
    private val keyAlias: String = DefaultKeyAlias,
) : SecureGuestSessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun read(): GuestSession? {
        val encoded = preferences.getString(SessionKey, null) ?: return null
        return runCatching { decrypt(encoded) }.getOrElse {
            clear()
            null
        }
    }

    override fun write(session: GuestSession) {
        check(preferences.edit().putString(SessionKey, encrypt(session)).commit()) {
            "Unable to persist guest session"
        }
    }

    override fun clear() {
        preferences.edit().remove(SessionKey).commit()
    }

    private fun encrypt(session: GuestSession): String {
        val cipher = Cipher.getInstance(Transformation).apply {
            init(Cipher.ENCRYPT_MODE, key())
        }
        val iv = requireNotNull(cipher.iv).also { require(it.size == IvLength) }
        val encrypted = cipher.doFinal(session.toBytes())
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): GuestSession {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IvLength)
        val cipher = Cipher.getInstance(Transformation).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TagLengthBits, bytes.copyOfRange(0, IvLength)))
        }
        return bytes.copyOfRange(IvLength, bytes.size).let(cipher::doFinal).toGuestSession()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore).apply {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun GuestSession.toBytes(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(FormatVersion)
            output.writeUTF(playerId)
            output.writeUTF(accessToken)
            output.writeUTF(refreshToken)
            output.writeLong(accessExpiresAtEpochMs)
            output.writeLong(refreshExpiresAtEpochMs)
        }
        bytes.toByteArray()
    }

    private fun ByteArray.toGuestSession(): GuestSession = DataInputStream(ByteArrayInputStream(this)).use { input ->
        require(input.readInt() == FormatVersion)
        GuestSession(
            playerId = input.readUTF(),
            accessToken = input.readUTF(),
            refreshToken = input.readUTF(),
            accessExpiresAtEpochMs = input.readLong(),
            refreshExpiresAtEpochMs = input.readLong(),
        )
    }

    private companion object {
        const val AndroidKeyStore = "AndroidKeyStore"
        const val Transformation = "AES/GCM/NoPadding"
        const val DefaultPreferencesName = "inplacex_guest_session"
        const val DefaultKeyAlias = "inplacex.guest.session.v1"
        const val SessionKey = "encrypted_session"
        const val IvLength = 12
        const val TagLengthBits = 128
        const val FormatVersion = 1
    }
}
