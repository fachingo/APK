package ca.canadavhs.snapmobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The API key is encrypted at rest; the wrapping key never leaves Android Keystore. */
class SecureApiKeyStore(
    context: Context,
    private val wrappingKey: (() -> SecretKey)? = null,
    preferenceName: String = "openai-key-encrypted",
    private val alias: String = "snapims.openai.api-key.v1"
) {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        wrappingKey?.let { return it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun hasKey(): Boolean = preferences.contains("ciphertext")
    fun save(value: String) {
        val cleaned = value.trim()
        require(cleaned.length in 16..512 && cleaned.none(Char::isWhitespace)) { "Paste a valid API key without spaces." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(cleaned.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) { "Could not save the key securely." }
    }
    fun read(): String? {
        val encrypted = preferences.getString("ciphertext", null) ?: return null
        try {
            val iv = Base64.decode(preferences.getString("iv", null) ?: error("Missing encryption IV"), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            return String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { throw IllegalStateException("The stored API key cannot be unlocked. Remove it and save it again in Settings.") }
    }
    fun remove() {
        check(preferences.edit().clear().commit()) { "Could not remove the saved API key." }
        if (wrappingKey == null) KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
    }
}
