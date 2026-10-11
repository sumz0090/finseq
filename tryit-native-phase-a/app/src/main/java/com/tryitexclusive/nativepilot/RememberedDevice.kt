package com.tryitexclusive.nativepilot

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** System mode uses an authentication-bound key. PIN mode uses the existing encrypted store,
 * with persistent PIN attempt throttling in MainActivity. Neither mode stores a password. */
class RememberedDevice(context: Context, private val secure: SecureStore) {
    private val prefs = context.getSharedPreferences("tryit_native_device_vault", Context.MODE_PRIVATE)
    private val alias = "tryit_native_authenticated_device"
    fun prepareSystem() { key() }
    fun resetSystemKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    fun exists() = prefs.contains("token") || secure.get("remember_token").isNotBlank()
    fun clear() { prefs.edit().clear().commit(); secure.put("remember_token", "") }
    fun save(token: String, system: Boolean) {
        clear()
        if (!system) { secure.put("remember_token", token); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val raw = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("token", Base64.encodeToString(raw, Base64.NO_WRAP)).commit()
    }
    fun read(system: Boolean): String {
        if (!system) return secure.get("remember_token")
        val raw = prefs.getString("token", null) ?: return ""
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(60, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        else b.setUserAuthenticationValidityDurationSeconds(60)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(b.build()) }.generateKey()
    }
}
