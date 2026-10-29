package ca.canadavhs.snapmobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec

/** OAuth envelope is excluded from SnapIMS backup, export and Android Auto Backup. */
internal class SecureCredentialStore(context:Context,private val namespace:String="siwc",private val suppliedKey:(()->SecretKey)?=null) {
    private val prefs=context.getSharedPreferences("$namespace-encrypted",Context.MODE_PRIVATE)
    private val alias="snapims.$namespace.credentials.v1"
    private fun key():SecretKey {
        suppliedKey?.let {return it()}
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance("AES","AndroidKeyStore").apply {init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes("GCM").setEncryptionPaddings("NoPadding").setRandomizedEncryptionRequired(true).build())}.generateKey()
    }
    fun read():String? {
        val value=prefs.getString("envelope",null) ?: return null
        return try {val bytes=Base64.decode(value,Base64.NO_WRAP);require(bytes.size>28)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)));updateAAD(alias.toByteArray())}
            String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8)
        } catch(_:Exception) {error("Saved credentials cannot be unlocked. Disconnect and connect again.")}
    }
    fun save(value:String) {
        require(value.length<=131072)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key());updateAAD(alias.toByteArray())}
        val bytes=cipher.iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("envelope",Base64.encodeToString(bytes,Base64.NO_WRAP)).commit()) {"Could not securely save Saved credentials."}
    }
    fun remove() {check(prefs.edit().clear().commit())}
}
