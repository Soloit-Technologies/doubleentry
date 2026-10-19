package com.soloit.doubleentry.desktop.platform

import com.soloit.doubleentry.sync.TokenStore
import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Session tokens in [file], encrypted. On Windows with DPAPI, which ties them to the Windows user
 * account; elsewhere with an AES key in a file only the user can read. Like the Android Keystore
 * store, anything that can't be decrypted reads as null, which means "sign in again".
 */
class DesktopTokenStore(private val file: File) : TokenStore {
    private val cipher: TokenCipher = if (isWindows) DpapiCipher else KeyFileCipher(File(file.parentFile, "tokens.key"))

    @Synchronized
    override fun get(name: String): String? {
        val stored = load().getProperty(name) ?: return null
        return runCatching { String(cipher.decrypt(Base64.getDecoder().decode(stored)), Charsets.UTF_8) }.getOrElse {
            edit { remove(name) }
            null
        }
    }

    @Synchronized
    override fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) {
            edit { remove(name) }
            return
        }
        runCatching { Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8))) }
            .onSuccess { encrypted -> edit { setProperty(name, encrypted) } }
            .onFailure { edit { remove(name) } }
    }

    @Synchronized
    override fun clear() {
        file.delete()
    }

    private fun load() = Properties().apply { if (file.exists()) runCatching { file.inputStream().use { load(it) } } }

    private fun edit(block: Properties.() -> Unit) {
        val properties = load().apply(block)
        file.parentFile.mkdirs()
        file.outputStream().use { properties.store(it, null) }
        restrictToOwner(file)
    }

    private interface TokenCipher {
        fun encrypt(plain: ByteArray): ByteArray
        fun decrypt(encrypted: ByteArray): ByteArray
    }

    private object DpapiCipher : TokenCipher {
        override fun encrypt(plain: ByteArray): ByteArray = Crypt32Util.cryptProtectData(plain)
        override fun decrypt(encrypted: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(encrypted)
    }

    private class KeyFileCipher(private val keyFile: File) : TokenCipher {
        private val key by lazy {
            val bytes = if (keyFile.exists()) {
                keyFile.readBytes()
            } else {
                ByteArray(32).also(SecureRandom()::nextBytes).also { keyFile.writeBytes(it); restrictToOwner(keyFile) }
            }
            SecretKeySpec(bytes, "AES")
        }

        override fun encrypt(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return cipher.iv + cipher.doFinal(plain)
        }

        override fun decrypt(encrypted: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted, 0, 12)) }
            return cipher.doFinal(encrypted, 12, encrypted.size - 12)
        }
    }

    private companion object {
        fun restrictToOwner(file: File) {
            if (!isWindows) runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
        }
    }
}
