package com.soloit.doubleentry.system

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.soloit.doubleentry.sync.SyncException
import java.security.MessageDigest
import java.security.SecureRandom

/** A Google ID token and the raw nonce whose SHA-256 it carries; Supabase checks the two match. */
class GoogleIdToken(val idToken: String, val nonce: String, val email: String)

/**
 * Sign in with Google through Credential Manager: Google's account sheet, no browser. The token is
 * issued for [webClientId] (the OAuth web client Supabase accepts). Returns null when the user
 * closes the sheet.
 */
object GoogleSignIn {
    suspend fun idToken(context: Context, webClientId: String): GoogleIdToken? {
        val nonce = Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val hashed = MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray()).joinToString("") { "%02x".format(it) }
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).setNonce(hashed).build())
            .build()
        val credential = try {
            CredentialManager.create(context).getCredential(context, request).credential
        } catch (e: GetCredentialCancellationException) {
            return null
        } catch (e: NoCredentialException) {
            throw SyncException("Add a Google account to this device (Settings → Accounts), then try again.", e)
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Sign in with Google failed", e)
            throw SyncException("Sign in with Google didn't work. Please try again.", e)
        }
        if (credential !is CustomCredential || credential.type !in GOOGLE_TYPES) {
            throw SyncException("Sign in with Google didn't work. Please try again.")
        }
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        return GoogleIdToken(google.idToken, nonce, google.email.orEmpty())
    }

    private val GOOGLE_TYPES = setOf(
        GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
        GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL,
    )
    private const val TAG = "GoogleSignIn"
}
