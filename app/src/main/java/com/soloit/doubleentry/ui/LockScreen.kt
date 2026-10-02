package com.soloit.doubleentry.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withStateAtLeast
import com.soloit.doubleentry.system.AppLock
import com.soloit.doubleentry.ui.components.IconAvatar

/** Opaque cover shown while the app lock is engaged; asks for the screen lock right away. */
@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val activity = LocalActivity.current as FragmentActivity
    var error by remember { mutableStateOf<String?>(null) }
    val prompt = {
        error = null
        AppLock.authenticate(activity, "Unlock DoubleEntry") { message -> if (message == null) onUnlocked() else error = message }
    }
    // The prompt is a fragment, so wait until the activity is resumed before showing it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) { prompt() } }
    // Back leaves the app instead of reaching the screens underneath.
    BackHandler { activity.moveTaskToBack(true) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconAvatar(Icons.Rounded.Lock, MaterialTheme.colorScheme.primary, size = 72)
            Spacer(Modifier.height(24.dp))
            Text("DoubleEntry is locked", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                error ?: "Unlock with your fingerprint, face or screen lock.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = prompt) { Text("Unlock") }
        }
    }
}
