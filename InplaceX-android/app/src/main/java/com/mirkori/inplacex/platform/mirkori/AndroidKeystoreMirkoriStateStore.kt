package com.mirkori.inplacex.platform.mirkori

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreMirkoriStateStore(
    context: Context,
    private val preferencesName: String = DefaultPreferencesName,
    private val keyAlias: String = DefaultKeyAlias,
) : SecureMirkoriStateStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun read(): MirkoriPersistedState? {
        val encoded = preferences.getString(StateKey, null) ?: return null
        return runCatching { decrypt(encoded) }.getOrElse {
            clear()
            null
        }
    }

    override fun write(state: MirkoriPersistedState) {
        check(preferences.edit().putString(StateKey, encrypt(state)).commit()) {
            "Unable to persist Mirkori platform state"
        }
    }

    override fun clear() {
        preferences.edit().remove(StateKey).commit()
    }

    private fun encrypt(state: MirkoriPersistedState): String {
        val cipher = Cipher.getInstance(Transformation).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = requireNotNull(cipher.iv).also { require(it.size == IvLength) }
        val encrypted = cipher.doFinal(MirkoriStateCodec.encode(state))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): MirkoriPersistedState {
        require(encoded.length <= MaximumEncodedLength)
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IvLength)
        val cipher = Cipher.getInstance(Transformation).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TagLengthBits, bytes.copyOfRange(0, IvLength)))
        }
        return MirkoriStateCodec.decode(cipher.doFinal(bytes.copyOfRange(IvLength, bytes.size)))
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

    private companion object {
        const val AndroidKeyStore = "AndroidKeyStore"
        const val Transformation = "AES/GCM/NoPadding"
        const val DefaultPreferencesName = "inplacex_mirkori_platform_state"
        const val DefaultKeyAlias = "inplacex.mirkori.platform.state.v1"
        const val StateKey = "encrypted_state"
        const val IvLength = 12
        const val TagLengthBits = 128
        const val MaximumEncodedLength = 64 * 1024
    }
}
