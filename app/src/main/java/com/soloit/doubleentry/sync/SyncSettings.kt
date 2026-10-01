package com.soloit.doubleentry.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Build-time Supabase project settings (see app/build.gradle.kts and README). */
data class SupabaseConfig(
    val url: String,
    val anonKey: String,
    val featureEnabled: Boolean,
) {
    val baseUrl: String get() = url.trimEnd('/')
    val isConfigured: Boolean get() = url.isNotBlank() && anonKey.isNotBlank()

    /** Cloud sync is available only when the feature flag is on and the project is configured. */
    val isAvailable: Boolean get() = featureEnabled && isConfigured
}

/** Tables in parent → child order; pushes and pulls follow this order to satisfy foreign keys. */
enum class SyncTable(val remoteName: String) {
    Accounts("accounts"),
    Transactions("transactions"),
    Entries("transaction_entries"),
    Assets("assets"),
    Invoices("invoices"),
    Settings("user_settings"),
}

/** Device-local sync state: account, token expiry and per-table pull watermarks. */
class SyncPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("supabase_sync", Context.MODE_PRIVATE)
    private val tokens = SecureTokenStore(context)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit { putBoolean("enabled", value) }

    var userId: String?
        get() = prefs.getString("userId", null)
        set(value) = prefs.edit { putString("userId", value) }

    var userEmail: String?
        get() = prefs.getString("userEmail", null)
        set(value) = prefs.edit { putString("userEmail", value) }

    var accessTokenExpiresAtMillis: Long?
        get() = prefs.getLong("expiresAt", 0L).takeIf { it > 0 }
        set(value) = prefs.edit { putLong("expiresAt", value ?: 0L) }

    var lastSyncAtMillis: Long?
        get() = prefs.getLong("lastSyncAt", 0L).takeIf { it > 0 }
        set(value) = prefs.edit { putLong("lastSyncAt", value ?: 0L) }

    var accessToken: String?
        get() = tokens.get("access")
        set(value) = tokens.put("access", value)

    var refreshToken: String?
        get() = tokens.get("refresh")
        set(value) = tokens.put("refresh", value)

    /** Highest server `updated_at` already pulled for [table] (ISO-8601), or null for a full pull. */
    fun watermark(table: SyncTable): String? = prefs.getString("watermark.${table.remoteName}", null)

    fun setWatermarks(values: Map<SyncTable, String>) = prefs.edit {
        values.forEach { (table, value) -> putString("watermark.${table.remoteName}", value) }
    }

    /** Clears the session and the watermarks so the next sign-in performs a full pull. */
    fun clearSession() {
        prefs.edit {
            remove("userId")
            remove("userEmail")
            remove("expiresAt")
            remove("lastSyncAt")
            SyncTable.entries.forEach { remove("watermark.${it.remoteName}") }
        }
        tokens.clear()
    }
}

/**
 * Stores tokens encrypted with an AES-GCM key held in the Android Keystore. Any failure (for
 * example a key lost after a device restore) simply yields `null`, which means "sign in again".
 */
private class SecureTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("supabase_tokens", Context.MODE_PRIVATE)

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
        }.getOrElse {
            prefs.edit { remove(name) }
            null
        }
    }

    fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) {
            prefs.edit { remove(name) }
            return
        }
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit { putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP)) }
        }.onFailure { prefs.edit { remove(name) } }
    }

    fun clear() = prefs.edit { clear() }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "doubleentry_supabase_tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
