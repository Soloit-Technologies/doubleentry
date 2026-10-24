package com.soloit.doubleentry

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.soloit.doubleentry.data.ThemeMode
import com.soloit.doubleentry.system.Launch
import com.soloit.doubleentry.system.LaunchRequest
import com.soloit.doubleentry.ui.DoubleEntryRoot
import com.soloit.doubleentry.ui.LockScreen
import com.soloit.doubleentry.ui.theme.DoubleEntryTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The ViewModels of the open ledger's screens. Opening another ledger clears them (their screens are
 * rebuilt on the other ledger's database); rotating the screen keeps them.
 */
class LedgerViewModelStores : ViewModel() {
    private var session: LedgerSession? = null
    private var store = ViewModelStore()

    fun storeFor(session: LedgerSession): ViewModelStore {
        if (session !== this.session) {
            store.clear()
            store = ViewModelStore()
            this.session = session
        }
        return store
    }

    override fun onCleared() = store.clear()
}

// FragmentActivity (a ComponentActivity) because the biometric prompt is hosted in a fragment.
class MainActivity : FragmentActivity() {
    /** A shortcut, notification or widget tap waiting to be routed by the UI. */
    private val launchRequest = MutableStateFlow<LaunchRequest?>(null)
    private val ledgerStores: LedgerViewModelStores by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        drawEdgeToEdge()
        val container = (application as DoubleEntryApp).container
        if (savedInstanceState == null) launchRequest.value = Launch.parse(intent)

        setContent {
            val appearance by container.preferences.appearance.collectAsStateWithLifecycle()
            val lockEnabled by container.preferences.appLock.collectAsStateWithLifecycle()
            val locked by container.appLock.locked.collectAsStateWithLifecycle()
            val launch by launchRequest.collectAsStateWithLifecycle()
            val session by container.sessions.active.collectAsStateWithLifecycle()
            val dark = when (appearance.themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            DisposableEffect(dark) {
                WindowInsetsControllerCompat(window, window.decorView).run {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
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
                        // Another ledger opened: start over on its screens, with their own ViewModels.
                        key(session) {
                            val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ledgerStores.storeFor(session) } }
                            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                                DoubleEntryRoot(container, launch, onLaunchHandled = { launchRequest.value = null })
                            }
                        }
                    }
                    if (locked) LockScreen(onUnlocked = container.appLock::unlock)
                }
            }
        }
    }

    /**
     * What `enableEdgeToEdge()` does for fully transparent bars, without the calls Android 15
     * deprecated (Play flags those even behind SDK checks): the theme makes the bars transparent and
     * sets the cutout mode for API 28-29, and the bar icons follow the app theme in [onCreate].
     */
    private fun drawEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // No scrim behind three-button navigation. The status bar's contrast isn't enforced by default.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Launch.parse(intent)?.let { launchRequest.value = it }
    }
}
