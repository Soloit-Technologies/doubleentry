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
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.SystemUpdate
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

/** App-wide strip above the navigation bar; opens the Cloud sync screen. */
@Composable
fun SyncBanner(status: SyncStatus, onOpen: () -> Unit, onDismissReplaced: () -> Unit, modifier: Modifier = Modifier) {
    val attention = status.attention ?: return
    val text = status.attentionText() ?: return
    val urgent = attention == SyncAttention.UpdateRequired || attention == SyncAttention.SignInAgain ||
        attention == SyncAttention.LostAccess || attention == SyncAttention.Failing
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (urgent) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (urgent) colors.onErrorContainer else colors.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = when (attention) {
                SyncAttention.UpdateRequired -> Icons.Rounded.SystemUpdate
                SyncAttention.ServicePaused -> Icons.Rounded.PauseCircleOutline
                SyncAttention.SignInAgain -> Icons.AutoMirrored.Rounded.Login
                SyncAttention.LostAccess -> Icons.Rounded.PersonOff
                SyncAttention.NotBackedUp -> Icons.Rounded.CloudOff
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
