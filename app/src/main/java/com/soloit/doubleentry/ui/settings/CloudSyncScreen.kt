package com.soloit.doubleentry.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.ui.LocalAppContainer
import com.soloit.doubleentry.ui.components.ConfirmDialog
import com.soloit.doubleentry.ui.components.GroupedItem
import com.soloit.doubleentry.ui.components.IconAvatar
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun CloudSyncScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val sync = container.sync
    val status by sync.status.collectAsStateWithLifecycle()
    // App-wide scope: a sign-in or sync keeps running if the user leaves this screen.
    val scope = container.appScope
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }

    fun signIn() {
        if (email.isBlank() || password.isBlank()) {
            error = "Email and password are required."
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                sync.signIn(email, password)
                password = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = com.soloit.doubleentry.sync.SupabaseApi.userMessage(e)
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cloud sync") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(contentPadding = PaddingValues(20.dp), containerColor = MaterialTheme.colorScheme.primaryContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconAvatar(Icons.Rounded.CloudSync, MaterialTheme.colorScheme.onPrimaryContainer, size = 48)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Sync across devices", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            "Changes are saved on this device first and sent to your Supabase project in the background.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            if (!status.configured) {
                NotConfiguredCard()
            } else if (!status.isSignedIn) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showPassword) "Hide password" else "Show password")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { signIn() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = ::signIn, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Sign in")
                }
                Text(
                    "Use the account you created in your Supabase project (Authentication → Users).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column {
                    GroupedItem(0, 2) { SettingRow(Icons.Rounded.Cloud, "Signed in", status.signedInEmail) }
                    GroupedItem(1, 2, onClick = { sync.setEnabled(!status.enabled) }) {
                        SettingRow(
                            Icons.Rounded.Sync,
                            "Automatic sync",
                            if (status.enabled) "On — syncs after every change and when the app opens" else "Paused",
                            trailing = { Switch(checked = status.enabled, onCheckedChange = sync::setEnabled) },
                        )
                    }
                }
                val finance = AppTheme.finance
                val statusText = when {
                    status.isSyncing -> "Syncing…"
                    status.lastError != null -> status.lastError!!
                    status.lastSyncAtMillis != null -> "Last synced ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(status.lastSyncAtMillis!!))}"
                    else -> "Not synced yet"
                }
                Text(statusText, color = if (status.lastError != null) finance.negative else MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(
                    onClick = { scope.launch { busy = true; try { sync.syncNow() } finally { busy = false } } },
                    enabled = !busy && !status.isSyncing && status.enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Sync, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sync now")
                }
                OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Sign out")
                }
            }
        }
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = "Sign out?",
            message = "Cloud sync will stop. Your local data is kept untouched.",
            confirmLabel = "Sign out",
            onConfirm = { confirmSignOut = false; scope.launch { sync.signOut() } },
            onDismiss = { confirmSignOut = false },
        )
    }
}

/** Shown in builds compiled without a Supabase project; explains how to add one. */
@Composable
private fun NotConfiguredCard() {
    SectionCard(contentPadding = PaddingValues(20.dp)) {
        Text("Not set up in this build", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "This build was compiled without a Supabase project. Add these two lines to local.properties " +
                "in the project folder, then rebuild and reinstall the app:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
            Text(
                "supabase.url=https://<project-ref>.supabase.co\nsupabase.anonKey=<anon key>",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(12.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Both values are in the Supabase dashboard under Project Settings → API.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
