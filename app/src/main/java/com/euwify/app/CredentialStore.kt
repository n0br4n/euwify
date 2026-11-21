package com.euwify.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the Eufy login on this phone only, encrypted with a key that lives
 * in the Android Keystore (the key itself can never be read out).
 */
class CredentialStore(context: Context) {

    data class Login(val email: String, val password: String, val country: String)

    private val prefs = context.applicationContext
        .getSharedPreferences("credentials", Context.MODE_PRIVATE)

    fun save(login: Login) {
        val plain = JSONObject()
            .put("email", login.email)
            .put("password", login.password)
            .put("country", login.country)
            .toString().toByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(plain)
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    fun load(): Login? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val data = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_SIZE))
            val json = JSONObject(String(cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE)))
            Login(json.getString("email"), json.getString("password"), json.getString("country"))
        } catch (e: Exception) {
            null // key gone or data damaged: user logs in again
        }
    }

    fun hasLogin() = prefs.contains(KEY_BLOB)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "eufyguard_login"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val KEY_BLOB = "login"
    }
}
