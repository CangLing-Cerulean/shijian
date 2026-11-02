package schedule.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores strings only as authenticated ciphertext. Keystore/storage failures propagate to callers. */
class CredentialVault(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("schedule_vault", Context.MODE_PRIVATE)

    fun put(name: String, value: String?) = synchronized(lock) {
        val slot = slot(name)
        if (value.isNullOrEmpty()) {
            check(preferences.edit().remove(slot).commit()) { "Vault write failed" }
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(slot.toByteArray(Charsets.UTF_8))
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val encoded = encode(cipher.iv) + ":" + encode(ciphertext)
            check(preferences.edit().putString(slot, encoded).commit()) { "Vault write failed" }
        }
    }

    fun get(name: String): String? = synchronized(lock) {
        val slot = slot(name)
        val stored = preferences.getString(slot, null) ?: return@synchronized null
        val parts = stored.split(':')
        check(parts.size == 2) { "Invalid vault entry" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        cipher.updateAAD(slot.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    fun clear() = synchronized(lock) {
        check(preferences.edit().clear().commit()) { "Vault write failed" }
    }

    private fun slot(name: String): String {
        require(name.isNotBlank()) { "Vault name is required" }
        return encode(MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        val lock = Any()
        const val KEY_ALIAS = "schedule.credentials.aes.v1"
    }
}
