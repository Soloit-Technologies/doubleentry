package com.soloit.doubleentry.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.sync.SyncAttention
import com.soloit.doubleentry.sync.SyncStatus

fun changesText(count: Int): String = if (count == 1) "1 change" else "$count changes"

/** One line on what needs the user's attention, or null when sync is fine. */
fun SyncStatus.attentionText(): String? = when (attention) {
    SyncAttention.SignInAgain -> "Sign in again to keep syncing" +
        if (pendingChanges > 0) " · ${changesText(pendingChanges)} only on this device" else ""
    SyncAttention.Failing -> "Sync isn't working · ${changesText(pendingChanges)} not uploaded yet"
    SyncAttention.Offline -> "Offline · ${changesText(pendingChanges)} will upload when you're back online"
    SyncAttention.Paused -> "Sync is paused · ${changesText(pendingChanges)} only on this device"
    SyncAttention.Replaced -> "${changesText(replacedChanges)} from this device ${if (replacedChanges == 1) "was" else "were"} " +
        "replaced by newer edits from another device"
    null -> null
}

/** App-wide strip above the navigation bar; opens the Cloud sync screen. */
@Composable
fun SyncBanner(status: SyncStatus, onOpen: () -> Unit, onDismissReplaced: () -> Unit, modifier: Modifier = Modifier) {
    val attention = status.attention ?: return
    val text = status.attentionText() ?: return
    val urgent = attention == SyncAttention.SignInAgain || attention == SyncAttention.Failing
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (urgent) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (urgent) colors.onErrorContainer else colors.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = when (attention) {
                SyncAttention.SignInAgain -> Icons.AutoMirrored.Rounded.Login
                SyncAttention.Failing -> Icons.Rounded.ErrorOutline
                SyncAttention.Offline -> Icons.Rounded.CloudOff
                SyncAttention.Paused -> Icons.Rounded.PauseCircleOutline
                SyncAttention.Replaced -> Icons.Rounded.SyncProblem
            }
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (attention == SyncAttention.Replaced) {
                IconButton(onClick = onDismissReplaced) { Icon(Icons.Rounded.Close, "Dismiss") }
            } else {
                IconButton(onClick = onOpen) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open cloud sync") }
            }
        }
    }
}
