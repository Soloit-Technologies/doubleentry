package com.soloit.doubleentry.ui.settings

import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PhonelinkSetup
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.sync.ServiceBlock
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
fun CloudSyncScreen(onBack: () -> Unit, onOpenLedgers: () -> Unit) {
    val container = LocalAppContainer.current
    val sync = container.sync
    val status by sync.status.collectAsStateWithLifecycle()
    val sharedOnDevice by container.sessions.sharedOnDevice.collectAsStateWithLifecycle()
    // App-wide scope: a sign-in or sync keeps running if the user leaves this screen.
    val scope = container.appScope
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf<SignInQuestion?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val context = LocalActivity.current ?: LocalContext.current

    /**
     * Renews an expired session with Google; first sign-ins go through [AccountForms]. Accounts made
     * with a password sign in with Google as well: it links to the account with the same email.
     */
    fun signInAgain() {
        busy = true
        error = null
        scope.launch {
            try {
                val result = signInWithGoogle(context, sync, sync.googleClientId)
                if (result == null) error = CANCELLED_HINT else result.question?.let { question = it }
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
                title = { Text("Cloud backup & sync") },
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
                    IconAvatar(Icons.Rounded.CloudDone, MaterialTheme.colorScheme.onPrimaryContainer, size = 48)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Your books, safe in the cloud", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            "Every change is backed up to the server as you make it. " +
                                "If your phone is lost, stolen or broken, sign in on the new one and your ledger is back.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            if (status.configured && !status.isSignedIn) BenefitsCard()

            if (!status.configured) {
                NotConfiguredCard()
            } else if (!status.isSignedIn) {
                AccountForms(sync, scope, sync.googleClientId, onQuestion = { question = it })
            } else {
                Column {
                    GroupedItem(0, 3) { SettingRow(Icons.Rounded.Cloud, "Signed in", status.signedInEmail) }
                    GroupedItem(1, 3, onClick = onOpenLedgers) {
                        SettingRow(
                            Icons.Rounded.Group,
                            "Ledgers & sharing",
                            status.ledger?.let { "Open: ${it.name}" } ?: "Share this ledger, or join someone else's",
                            chevron = true,
                        )
                    }
                    GroupedItem(2, 3, onClick = { sync.setEnabled(!status.enabled) }) {
                        SettingRow(
                            Icons.Rounded.Sync,
                            "Automatic sync",
                            if (status.enabled) "On — every change is backed up right away" else "Paused — new changes are only on this device and aren't backed up",
                            trailing = { Switch(checked = status.enabled, onCheckedChange = sync::setEnabled) },
                        )
                    }
                }

                if (status.lostLedgerAccess) {
                    LostAccessCard(onRemove = { status.ledgerId?.let { id -> scope.launch { container.sessions.removeFromDevice(id) } } })
                }

                status.serviceBlock?.let { ServiceBlockCard(it, packageName = context.packageName) }

                BackupPlanCard(status.access, container.billing)

                if (status.needsSignIn) {
                    SectionCard(contentPadding = PaddingValues(20.dp), containerColor = MaterialTheme.colorScheme.errorContainer) {
                        Text("Sign in again", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Your session has ended, so nothing is syncing. " +
                                "Sign in with Google as ${status.signedInEmail} to continue." +
                                if (status.pendingChanges > 0) " ${changesText(status.pendingChanges)} on this device will then be uploaded." else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(12.dp))
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
                        GoogleButton(busy, label = "Sign in with Google again", onClick = ::signInAgain)
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
                val waiting = when {
                    status.pendingChanges == 0 && !status.notBackedUp -> "Everything is backed up"
                    status.pendingChanges == 0 -> "No new changes"
                    else -> "${changesText(status.pendingChanges)} not backed up yet"
                }
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
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Delete account", color = MaterialTheme.colorScheme.error)
                }
                error?.takeIf { !status.needsSignIn }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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

    if (confirmDelete) {
        val access = status.access
        DeleteAccountDialog(
            email = status.signedInEmail,
            paidThroughPlay = access?.source == "google_play" && access.active && access.autoRenewing,
            onConfirm = {
                confirmDelete = false
                error = null
                scope.launch {
                    try {
                        container.sessions.deleteAccount()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = com.soloit.doubleentry.sync.SupabaseApi.userMessage(e)
                    }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = "Sign out?",
            message = "Backup and sync will stop. The data stays on this device, and your cloud copy is kept." +
                (if (status.pendingChanges > 0 && !status.sharedLedger) {
                    " ${changesText(status.pendingChanges)} haven't been uploaded yet; you'll be asked about them when you sign in again."
                } else {
                    ""
                }) +
                if (sharedOnDevice.isNotEmpty()) {
                    "\n\nLedgers shared with you (${sharedOnDevice.joinToString { it.name }}) are removed from this device" +
                        (if (status.sharedLedger && status.pendingChanges > 0) ", with ${changesText(status.pendingChanges)} not uploaded yet" else "") +
                        ". They come back when you sign in again."
                } else {
                    ""
                },
            confirmLabel = "Sign out",
            onConfirm = { confirmSignOut = false; scope.launch { container.sessions.signOut() } },
            onDismiss = { confirmSignOut = false },
        )
    }
}

private fun com.soloit.doubleentry.sync.SyncStatus.attentionTextForReplaced(): String =
    "${changesText(replacedChanges)} made on this device ${if (replacedChanges == 1) "was" else "were"} replaced" +
        (replacedAtMillis?.let { " during the sync on ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}" } ?: "") + "."

/** Asks how this device's data and the account's go together before the first sync. */
@Composable
private fun SignInQuestionDialog(question: SignInQuestion, onChoose: (SignInChoice) -> Unit, onCancel: () -> Unit) {
    val (title, message, keepDevice, useCloud) = question.prompt()
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

/** What signing in gets you, backup first: losing the phone is a stronger reason than a second device. */
@Composable
private fun BenefitsCard() {
    SectionCard(contentPadding = PaddingValues(20.dp)) {
        listOf(
            Triple(Icons.Rounded.CloudDone, "Automatic backup", "No files to remember to export. Your ledger is copied to the server as you work."),
            Triple(Icons.Rounded.PhonelinkSetup, "Restore on a new phone", "Lost, broken or upgraded phone? Sign in and everything is back: accounts, transactions, invoices and assets."),
            Triple(Icons.Rounded.Devices, "Every device up to date", "Use the same ledger on your phone and tablet. Changes show up on the others right away."),
        ).forEachIndexed { i, (icon, title, text) ->
            Row(Modifier.padding(top = if (i == 0) 0.dp else 16.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Sets expectations in plain words, including what happens when two devices edit the same item. */
@Composable
private fun HowSyncWorksCard() {
    SectionCard(contentPadding = PaddingValues(20.dp)) {
        Text("How sync works", style = MaterialTheme.typography.titleSmall)
        listOf(
            "Every change is saved on this device first and backed up to the server when there's a connection. Changes made offline upload later.",
            "On a new or reset phone, sign in with the same account to download your whole ledger.",
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

/** The server has stopped sync: why, and for an outdated build, the way to the update. */
@Composable
private fun ServiceBlockCard(block: ServiceBlock, packageName: String) {
    val uri = LocalUriHandler.current
    SectionCard(contentPadding = PaddingValues(20.dp), containerColor = MaterialTheme.colorScheme.errorContainer) {
        Text(
            if (block.updateRequired) "Update required" else "Sync is paused",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(4.dp))
        Text(block.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        if (block.updateRequired) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = { uri.openUri("https://play.google.com/store/apps/details?id=$packageName") }, modifier = Modifier.fillMaxWidth()) {
                Text("Update in Google Play")
            }
        }
    }
}
