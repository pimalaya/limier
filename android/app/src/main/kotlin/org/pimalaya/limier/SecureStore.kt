package org.pimalaya.limier

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import org.pimalaya.limier.client.Account
import org.pimalaya.limier.client.Sasl

/**
 * Caches the single IMAP account locally, encrypted with an
 * AES-GCM key held in the Android Keystore. The password never
 * touches disk in clear, and no Jetpack Security / Tink dependency is
 * pulled in (smallest APK).
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The cached account, or null on first launch. */
    fun load(): Account? {
        val payload = prefs.getString(KEY_PAYLOAD, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP))
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), spec)

        val plaintext = cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP))
        return decode(String(plaintext, Charsets.UTF_8))
    }

    /** Encrypts and persists [account], replacing any previous one. */
    fun save(account: Account) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())

        val ciphertext = cipher.doFinal(encode(account).toByteArray(Charsets.UTF_8))

        prefs
            .edit()
            .putString(KEY_PAYLOAD, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    /** Returns the Keystore AES key, generating it on first use. */
    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encode(account: Account): String =
        JSONObject()
            .put("domain", account.domain)
            .put("port", account.port)
            .put("sasl", account.sasl.name)
            .put("login", account.login)
            .put("password", account.password)
            .toString()

    private fun decode(json: String): Account {
        val obj = JSONObject(json)
        return Account(
            domain = obj.getString("domain"),
            port = obj.getInt("port"),
            sasl = Sasl.valueOf(obj.getString("sasl")),
            login = obj.getString("login"),
            password = obj.getString("password"),
        )
    }

    private companion object {
        const val PREFS = "limier.account"
        const val KEY_PAYLOAD = "payload"
        const val KEY_IV = "iv"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "limier.account.key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
