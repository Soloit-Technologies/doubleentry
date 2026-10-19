package com.soloit.doubleentry.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Proof Key for Code Exchange (RFC 7636), for signing in through the system browser. */
object Pkce {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** A new random code verifier: 43 characters from the URL-safe alphabet. */
    fun verifier(): String = encoder.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

    /** The S256 challenge of [verifier], sent when the sign-in starts. */
    fun challenge(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}
