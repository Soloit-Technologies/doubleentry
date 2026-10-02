package com.soloit.doubleentry

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.data.ThemeMode
import com.soloit.doubleentry.system.Launch
import com.soloit.doubleentry.system.LaunchRequest
import com.soloit.doubleentry.ui.DoubleEntryRoot
import com.soloit.doubleentry.ui.LockScreen
import com.soloit.doubleentry.ui.theme.DoubleEntryTheme
import kotlinx.coroutines.flow.MutableStateFlow

// FragmentActivity (a ComponentActivity) because the biometric prompt is hosted in a fragment.
class MainActivity : FragmentActivity() {
    /** A shortcut, notification or widget tap waiting to be routed by the UI. */
    private val launchRequest = MutableStateFlow<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val container = (application as DoubleEntryApp).container
        if (savedInstanceState == null) launchRequest.value = Launch.parse(intent)

        setContent {
            val appearance by container.preferences.appearance.collectAsStateWithLifecycle()
            val lockEnabled by container.preferences.appLock.collectAsStateWithLifecycle()
            val locked by container.appLock.locked.collectAsStateWithLifecycle()
            val launch by launchRequest.collectAsStateWithLifecycle()
            val dark = when (appearance.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            // With the lock on, keep balances out of the recent-apps thumbnail.
            LaunchedEffect(lockEnabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!lockEnabled)
            }
            DoubleEntryTheme(darkTheme = dark, dynamicColor = appearance.dynamicColor) {
                Box {
                    // The app stays composed under the lock so navigation and unsaved edits survive it,
                    // but it is hidden from accessibility services while locked.
                    Box(if (locked) Modifier.clearAndSetSemantics {} else Modifier) {
                        DoubleEntryRoot(container, launch, onLaunchHandled = { launchRequest.value = null })
                    }
                    if (locked) LockScreen(onUnlocked = container.appLock::unlock)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Launch.parse(intent)?.let { launchRequest.value = it }
    }
}
