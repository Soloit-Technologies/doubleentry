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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import com.soloit.doubleentry.sync.SignInChoice
import com.soloit.doubleentry.sync.SignInQuestion
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
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf<SignInQuestion?>(null) }

    fun signIn(again: Boolean) {
        if ((!again && email.isBlank()) || password.isBlank()) {
            error = if (again) "Enter your password." else "Email and password are required."
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                if (again) sync.signInAgain(password) else question = sync.signIn(email, password)
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
                            "Use the same ledger on your phone, tablet and other devices. A copy is kept in the cloud.",
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
                PasswordField(password, { password = it }, onDone = { signIn(again = false) })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { signIn(again = false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Sign in")
                }
                Text(
                    "Sign in with the email and password of your cloud account.",
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
                            if (status.enabled) "On — every change is uploaded right away" else "Paused — new changes stay on this device until you turn it back on",
                            trailing = { Switch(checked = status.enabled, onCheckedChange = sync::setEnabled) },
                        )
                    }
                }

                if (status.needsSignIn) {
                    SectionCard(contentPadding = PaddingValues(20.dp), containerColor = MaterialTheme.colorScheme.errorContainer) {
                        Text("Sign in again", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Your session has ended, so nothing is syncing. Enter the password for ${status.signedInEmail} to continue." +
                                if (status.pendingChanges > 0) " ${changesText(status.pendingChanges)} on this device will then be uploaded." else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(12.dp))
                        PasswordField(password, { password = it }, onDone = { signIn(again = true) })
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { signIn(again = true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else Text("Sign in again")
                        }
                    }
                }

                if (status.replacedChanges > 0) {
                    SectionCard(contentPadding = PaddingValues(20.dp), containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(status.attentionTextForReplaced(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The same items had also been changed on another device, and those changes were newer. " +
                                "Check them and edit again if needed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        TextButton(onClick = sync::dismissReplaced, modifier = Modifier.align(Alignment.End)) { Text("Got it") }
                    }
                }

                val finance = AppTheme.finance
                val waiting = if (status.pendingChanges > 0) "${changesText(status.pendingChanges)} waiting to upload" else "Everything is uploaded"
                val statusText = when {
                    status.isSyncing -> "Syncing…"
                    status.lastError != null -> "${status.lastError} $waiting."
                    status.lastSyncAtMillis != null ->
                        "Last synced ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(status.lastSyncAtMillis!!))} · $waiting"
                    else -> "Not synced yet · $waiting"
                }
                val problem = status.lastError != null && !status.offline
                Text(statusText, color = if (problem) finance.negative else MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(
                    onClick = { scope.launch { busy = true; try { sync.syncNow() } finally { busy = false } } },
                    enabled = !busy && !status.isSyncing && status.enabled && !status.needsSignIn,
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
                HowSyncWorksCard()
            }
        }
    }

    question?.let { q ->
        SignInQuestionDialog(
            question = q,
            onChoose = { choice ->
                question = null
                busy = true
                scope.launch {
                    try {
                        sync.completeSignIn(choice)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = com.soloit.doubleentry.sync.SupabaseApi.userMessage(e)
                    } finally {
                        busy = false
                    }
                }
            },
            onCancel = {
                question = null
                sync.cancelSignIn()
            },
        )
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = "Sign out?",
            message = "Cloud sync will stop. The data stays on this device." +
                if (status.pendingChanges > 0) {
                    " ${changesText(status.pendingChanges)} haven't been uploaded yet; you'll be asked about them when you sign in again."
                } else {
                    ""
                },
            confirmLabel = "Sign out",
            onConfirm = { confirmSignOut = false; scope.launch { sync.signOut() } },
            onDismiss = { confirmSignOut = false },
        )
    }
}

private fun com.soloit.doubleentry.sync.SyncStatus.attentionTextForReplaced(): String =
    "${changesText(replacedChanges)} made on this device ${if (replacedChanges == 1) "was" else "were"} replaced" +
        (replacedAtMillis?.let { " during the sync on ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}" } ?: "") + "."

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, onDone: () -> Unit) {
    var showPassword by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showPassword) "Hide password" else "Show password")
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Asks how this device's data and the account's go together before the first sync. */
@Composable
private fun SignInQuestionDialog(question: SignInQuestion, onChoose: (SignInChoice) -> Unit, onCancel: () -> Unit) {
    val title: String
    val message: String
    val keepDevice: String
    val useCloud: String
    when (question) {
        is SignInQuestion.ChangesWhileSignedOut -> {
            title = "Changes made while signed out"
            message = "This device has ${changesText(question.changes)} that ${if (question.changes == 1) "was" else "were"} made while you were signed out" +
                (if (question.deletions > 0) ", including ${question.deletions} deleted item${if (question.deletions == 1) "" else "s"}" else "") +
                ". Upload them to ${question.email}, or discard them and use the data in the cloud?" +
                (if (question.deletions > 0) "\n\nUploading also deletes those items on your other devices." else "")
            keepDevice = "Upload my changes"
            useCloud = "Discard them, use cloud data"
        }
        is SignInQuestion.TwoLedgers -> {
            title = "Which data do you want to keep?"
            message = "This device has its own ledger, and ${question.email} already has one in the cloud. " +
                "Only one can be kept; combining them would duplicate accounts and transactions.\n\n" +
                "• Use cloud data: the ledger on this device is deleted and replaced with the one from the cloud.\n" +
                "• Use this device's data: the ledger in the cloud is replaced, on all your devices.\n\n" +
                "Not sure? Cancel and export a backup first (Settings → Data management)."
            keepDevice = "Use this device's data"
            useCloud = "Use cloud data"
        }
        is SignInQuestion.OtherAccount -> {
            val previous = question.previousEmail ?: "another account"
            title = "Switch to ${question.email}?"
            message = "The ledger on this device belongs to $previous, which keeps its copy in the cloud either way.\n\n" +
                (if (question.accountHasData) {
                    "• Use ${question.email}'s ledger: this device switches to the ledger already in that account.\n" +
                        "• Copy this ledger: it replaces the ledger in ${question.email}, on all devices signed in to it."
                } else {
                    "• Start empty: ${question.email} starts with a new, empty ledger.\n" +
                        "• Copy this ledger: ${question.email} gets a copy of it."
                }) +
                if (question.unsyncedChanges > 0) {
                    "\n\n${changesText(question.unsyncedChanges)} on this device never reached $previous and will only be kept if you copy."
                } else {
                    ""
                }
            keepDevice = "Copy this ledger"
            useCloud = if (question.accountHasData) "Use ${question.email}'s ledger" else "Start empty"
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onChoose(SignInChoice.UseCloud) }) { Text(useCloud) }
                TextButton(onClick = { onChoose(SignInChoice.UseThisDevice) }) { Text(keepDevice) }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

/** Sets expectations in plain words, including what happens when two devices edit the same item. */
@Composable
private fun HowSyncWorksCard() {
    SectionCard(contentPadding = PaddingValues(20.dp)) {
        Text("How sync works", style = MaterialTheme.typography.titleSmall)
        listOf(
            "Every change is saved on this device first and uploaded when there's a connection. Changes made offline upload later.",
            "Your other devices pick up changes when they're opened, and right away while they're open.",
            "If the same item is changed on two devices before they've synced, only the most recent change is kept, " +
                "and a transaction is kept whole, with its own lines. " +
                "You'll see a notice when a change made on this device is replaced.",
            "Signing out stops syncing. The data on this device stays, and you're asked about any changes made meanwhile when you sign in again.",
        ).forEach { line ->
            Text(
                "•  $line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
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
