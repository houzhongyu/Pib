package com.houxinzhe.pib.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * User-supplied API keys (BYOK). Values are AES-GCM encrypted with a non-exportable
 * Android Keystore key, so the plaintext never touches disk and is not part of backups.
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("pib_secrets", Context.MODE_PRIVATE)

    fun get(name: String): String {
        val stored = prefs.getString(name, null) ?: return ""
        return runCatching {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_LEN)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun put(name: String, value: String) {
        val v = value.trim()
        if (v.isEmpty()) { prefs.edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(v.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
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

    companion object {
        private const val ALIAS = "pib_byok_v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        const val LLM_KEY = "llm_api_key"
        const val AMAP_KEY = "amap_web_key"
        const val BRIDGE_TOKEN = "bridge_token"
    }
}
