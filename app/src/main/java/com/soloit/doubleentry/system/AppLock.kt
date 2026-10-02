package com.soloit.doubleentry.system

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.soloit.doubleentry.data.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional lock: when enabled, the app asks for the device screen lock (or a fingerprint/face)
 * on start and after it has been in the background for longer than [GRACE_MILLIS]. The grace
 * period covers short trips to the system file picker or the share sheet.
 */
class AppLock(private val prefs: AppPreferences) {
    private val _locked = MutableStateFlow(prefs.appLock.value)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundedAt: Long? = null

    fun onBackground() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (prefs.appLock.value && SystemClock.elapsedRealtime() - since > GRACE_MILLIS) _locked.value = true
    }

    fun unlock() {
        _locked.value = false
    }

    fun setEnabled(enabled: Boolean) {
        prefs.setAppLock(enabled)
        if (!enabled) _locked.value = false
    }

    companion object {
        const val GRACE_MILLIS = 30_000L
        private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

        /** True when the device has a screen lock (PIN, pattern, password or biometrics) to verify against. */
        fun isAvailable(context: Context): Boolean =
            BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

        /** Shows the system prompt; [onResult] receives null on success or a message to show. */
        fun authenticate(activity: FragmentActivity, title: String, onResult: (String?) -> Unit) {
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(null)

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(errString.toString())
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Use your screen lock to continue")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
            prompt.authenticate(info)
        }
    }
}
