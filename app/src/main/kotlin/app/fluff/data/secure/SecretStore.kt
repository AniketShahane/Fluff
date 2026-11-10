package app.fluff.data.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.secretPrefs: DataStore<Preferences> by preferencesDataStore(name = "fluff_secrets")

/** Encrypts values with an AES-256-GCM key that never leaves the Android Keystore. */
interface Cryptor {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

class KeystoreCryptor(private val alias: String = "fluff_secrets_v1") : Cryptor {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt()
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return c.doFinal(blob, 1 + ivLen, blob.size - 1 - ivLen)
    }
}

enum class Secret(val key: String) {
    CLAUDE_API_KEY("claude_api_key"),
    WHOOP_CLIENT_ID("whoop_client_id"),
    WHOOP_CLIENT_SECRET("whoop_client_secret"),
    WHOOP_TOKENS("whoop_tokens"),
    WHOOP_PENDING_STATE("whoop_pending_state"),
}

class SecretStore(private val context: Context, private val cryptor: Cryptor = KeystoreCryptor()) {

    fun observe(secret: Secret): Flow<String?> = context.secretPrefs.data.map { p -> p[stringPreferencesKey(secret.key)]?.let(::decode) }

    suspend fun get(secret: Secret): String? = observe(secret).first()

    suspend fun put(secret: Secret, value: String?) {
        context.secretPrefs.edit { p ->
            val k = stringPreferencesKey(secret.key)
            if (value.isNullOrEmpty()) p.remove(k) else p[k] = Base64.encodeToString(cryptor.encrypt(value.toByteArray()), Base64.NO_WRAP)
        }
    }

    private fun decode(stored: String): String? = runCatching {
        String(cryptor.decrypt(Base64.decode(stored, Base64.NO_WRAP)))
    }.getOrNull() // A wiped keystore (e.g. after restore) makes old values unreadable: treat as missing.
}
