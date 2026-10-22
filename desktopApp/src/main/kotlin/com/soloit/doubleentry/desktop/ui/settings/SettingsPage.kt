package com.soloit.doubleentry.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.data.ReminderPrefs
import com.soloit.doubleentry.data.ThemeMode
import com.soloit.doubleentry.desktop.BuildConfig
import com.soloit.doubleentry.desktop.platform.AppDirectories
import com.soloit.doubleentry.desktop.platform.FileDialogs
import com.soloit.doubleentry.desktop.ui.LocalApp
import com.soloit.doubleentry.desktop.ui.components.Card
import com.soloit.doubleentry.desktop.ui.components.DetailRow
import com.soloit.doubleentry.desktop.ui.components.Layer
import com.soloit.doubleentry.desktop.ui.components.rememberViewModel
import com.soloit.doubleentry.sync.SignInChoice
import com.soloit.doubleentry.sync.SignInQuestion
import com.soloit.doubleentry.sync.SupabaseApi
import com.soloit.doubleentry.ui.settings.DataManagementViewModel
import com.soloit.doubleentry.ui.settings.attentionText
import com.soloit.doubleentry.ui.settings.changesText
import com.soloit.doubleentry.ui.settings.prompt
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.ComboBox
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.RadioButton
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Switcher
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

@Composable
fun SettingsPage() {
    Layer(Modifier.fillMaxSize().padding(end = 8.dp, bottom = 8.dp)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Settings", style = FluentTheme.typography.title)
                CloudSection()
                AppearanceSection()
                RemindersSection()
                DataSection()
                AboutSection()
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = FluentTheme.typography.bodyStrong)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
private fun Secondary(text: String) = Text(text, color = FluentTheme.colors.text.text.secondary)

@Composable
private fun CloudSection() {
    val app = LocalApp.current
    val sync = app.core.sync
    val status by sync.status.collectAsState()
    val scope = rememberCoroutineScope()
    var signingIn by remember { mutableStateOf<Job?>(null) }
    var question by remember { mutableStateOf<SignInQuestion?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var withEmail by remember { mutableStateOf(false) }

    Section("Cloud backup") {
        when {
            !status.available -> Secondary("Cloud backup isn't set up in this build. Your ledger stays on this computer.")
            !status.isSignedIn -> {
                Secondary("Sign in to back up your ledger and use it on your other devices. Accounts are created with Google.")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (signingIn == null) {
                        AccentButton(onClick = {
                            signingIn = scope.launch {
                                try {
                                    question = app.container.signIn.signIn()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    app.showError("Sign in", e.message)
                                } finally {
                                    signingIn = null
                                }
                            }
                        }) { Text("Sign in with Google") }
                    } else {
                        ProgressRing(size = 20.dp)
                        Text("Continue in your browser…")
                        Button(onClick = { signingIn?.cancel() }) { Text("Cancel") }
                    }
                }
                if (withEmail) {
                    EmailSignIn(onQuestion = { question = it })
                } else {
                    SubtleButton(onClick = { withEmail = true }) { Text("Have a password? Sign in with email") }
                }
            }
            else -> {
                DetailRow("Signed in as", status.signedInEmail.orEmpty())
                status.ledger?.let { DetailRow("Ledger", it.name) }
                DetailRow("Last sync", status.lastSyncAtMillis?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "Not yet")
                DetailRow("Waiting to upload", changesText(status.pendingChanges))
                status.attentionText()?.let { Text(it, color = FluentTheme.colors.system.caution) }
                if (status.notBackedUp && !status.sharedLedger) {
                    Secondary("Uploading needs a cloud backup subscription. Subscribe in the DoubleEntry Android app; it covers this computer too. Downloading is always free.")
                }
                if (status.ledgers.size > 1) {
                    val index = status.ledgers.indexOfFirst { it.id == status.ledgerId }
                    ComboBox(
                        header = "Open ledger",
                        items = status.ledgers.map { it.name + if (it.role != "owner") " (shared by ${it.ownerEmail ?: "someone"})" else "" },
                        selected = index.takeIf { it >= 0 },
                        onSelectionChange = { i, _ -> scope.launch { runCatching { app.core.sessions.open(status.ledgers[i].id) }.onFailure { app.showError("Open ledger", it.message) } } },
                        modifier = Modifier.width(360.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        syncing = true
                        scope.launch { try { sync.syncNow() } finally { syncing = false } }
                    }, disabled = syncing || status.isSyncing) { Text(if (syncing || status.isSyncing) "Syncing…" else "Sync now") }
                    if (status.needsSignIn && status.signedInWithGoogle) {
                        AccentButton(onClick = { scope.launch { runCatching { question = app.container.signIn.signIn() }.onFailure { app.showError("Sign in", it.message) } } }) { Text("Sign in again") }
                    }
                    Button(onClick = { scope.launch { app.core.sessions.signOut() } }) { Text("Sign out") }
                }
                if (status.needsSignIn && !status.signedInWithGoogle) {
                    EmailSignIn(onQuestion = { it?.let { q -> question = q } }, email = status.signedInEmail.orEmpty(), label = "Sign in again")
                }
            }
        }
    }

    question?.let { q ->
        val p = q.prompt()
        ContentDialog(
            title = p.title,
            visible = true,
            content = { Text(p.message) },
            primaryButtonText = p.keepDevice,
            secondaryButtonText = p.useCloud,
            closeButtonText = "Cancel",
            onButtonClick = { button ->
                question = null
                when (button) {
                    ContentDialogButton.Primary -> scope.launch { runCatching { sync.completeSignIn(SignInChoice.UseThisDevice) }.onFailure { app.showError("Sign in", it.message) } }
                    ContentDialogButton.Secondary -> scope.launch { runCatching { sync.completeSignIn(SignInChoice.UseCloud) }.onFailure { app.showError("Sign in", it.message) } }
                    ContentDialogButton.Close -> sync.cancelSignIn()
                }
            },
            size = io.github.composefluent.component.DialogSize.Max,
        )
    }
}

/**
 * Signing in with email and password, for the few accounts made by hand in the dashboard (people
 * who can't use Google, app reviewers); nobody can sign up this way. [email] fills in the address,
 * as when a session is renewed. [onQuestion] receives the sign-in question, if any.
 */
@Composable
private fun EmailSignIn(onQuestion: (SignInQuestion?) -> Unit, email: String = "", label: String = "Sign in") {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(email) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val ready = !busy && address.isNotBlank() && password.isNotEmpty()

    fun signIn() {
        if (!ready) return
        busy = true
        scope.launch {
            try {
                onQuestion(app.core.sync.signIn(address, password))
                password = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.showError("Sign in", SupabaseApi.userMessage(e))
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(
            address, { address = it }, Modifier.fillMaxWidth(), singleLine = true, header = { Text("Email") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        TextField(
            password, { password = it }, Modifier.fillMaxWidth(), singleLine = true, header = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { signIn() }),
        )
        AccentButton(onClick = ::signIn, disabled = !ready) { Text(if (busy) "Signing in…" else label) }
    }
}

@Composable
private fun AppearanceSection() {
    val preferences = LocalApp.current.core.preferences
    val appearance by preferences.appearance.collectAsState()
    Section("Appearance") {
        listOf(ThemeMode.System to "Use the system setting", ThemeMode.Light to "Light", ThemeMode.Dark to "Dark").forEach { (mode, label) ->
            RadioButton(selected = appearance.themeMode == mode, onClick = { preferences.setThemeMode(mode) }, label = label)
        }
    }
}

@Composable
private fun RemindersSection() {
    val preferences = LocalApp.current.core.preferences
    val reminders by preferences.reminders.collectAsState()
    val days = listOf(1, 3, 7, 14)
    Section("Invoice reminders") {
        Switcher(reminders.enabled, { preferences.setReminders(reminders.copy(enabled = it)) }, text = "Notify me about unpaid invoices")
        ComboBox(
            header = "Due within",
            items = days.map { if (it == 1) "1 day" else "$it days" },
            selected = days.indexOf(reminders.daysBefore).takeIf { it >= 0 },
            onSelectionChange = { i, _ -> preferences.setReminders(ReminderPrefs(reminders.enabled, days[i])) },
            disabled = !reminders.enabled,
            modifier = Modifier.width(200.dp),
        )
        Secondary("Shown once a day, while DoubleEntry is open. Overdue invoices are always included.")
    }
}

private enum class Confirm { Import, Delete }

@Composable
private fun DataSection() {
    val app = LocalApp.current
    val vm = rememberViewModel { DataManagementViewModel(app.core) }
    val busy by vm.busy.collectAsState()
    val result by vm.result.collectAsState()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var importFile by remember { mutableStateOf<java.io.File?>(null) }
    val today = LocalDate.now()

    Section("Data") {
        Secondary("Backups are JSON files that the Android app reads too. CSV files open in Excel.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(disabled = busy, onClick = {
                FileDialogs.save(app.window, "Export backup", "DoubleEntry-$today.json")?.let { file -> vm.export { FileOutputStream(file) } }
            }) { Text("Export backup") }
            Button(disabled = busy, onClick = {
                FileDialogs.open(app.window, "Import backup", listOf("json"))?.let { importFile = it; confirm = Confirm.Import }
            }) { Text("Import backup") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(disabled = busy, onClick = {
                FileDialogs.save(app.window, "Export transactions", "Transactions-$today.csv")?.let { file -> vm.exportTransactionsCsv { FileOutputStream(file) } }
            }) { Text("Export transactions (CSV)") }
            Button(disabled = busy, onClick = {
                FileDialogs.save(app.window, "Export balances", "Balances-$today.csv")?.let { file -> vm.exportBalancesCsv { FileOutputStream(file) } }
            }) { Text("Export balances (CSV)") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(disabled = busy, onClick = vm::repair) { Text("Repair depreciation") }
            Button(disabled = busy, onClick = { confirm = Confirm.Delete }) { Text("Delete all data") }
        }
        if (busy) ProgressRing(size = 20.dp)
    }

    result?.let { message ->
        ContentDialog(title = message.title, visible = true, content = { Text(message.text) }, primaryButtonText = "OK", onButtonClick = { vm.result.value = null })
    }
    when (confirm) {
        Confirm.Import -> ContentDialog(
            title = "Replace your ledger?",
            visible = true,
            content = { Text("Everything in this ledger is replaced with the backup, also in the cloud and on your other devices.") },
            primaryButtonText = "Replace",
            closeButtonText = "Cancel",
            onButtonClick = { button ->
                confirm = null
                val file = importFile
                if (button == ContentDialogButton.Primary && file != null) vm.import { FileInputStream(file) }
            },
        )
        Confirm.Delete -> ContentDialog(
            title = "Delete all data?",
            visible = true,
            content = { Text("Every account, transaction, invoice and asset in this ledger is deleted, also in the cloud and on your other devices. Export a backup first if you might need it.") },
            primaryButtonText = "Delete everything",
            closeButtonText = "Cancel",
            onButtonClick = { button ->
                confirm = null
                if (button == ContentDialogButton.Primary) vm.deleteAll()
            },
        )
        null -> Unit
    }
}

@Composable
private fun AboutSection() {
    Section("About") {
        DetailRow("Version", BuildConfig.VERSION_NAME)
        DetailRow("Data folder", AppDirectories.data.path)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { runCatching { Desktop.getDesktop().open(AppDirectories.data) } }) { Text("Open data folder") }
            Button(onClick = { runCatching { Desktop.getDesktop().browse(java.net.URI("https://github.com/Soloit-Technologies/doubleentry")) } }) { Text("Source code") }
        }
        Secondary("DoubleEntry is free software under the Mozilla Public License 2.0.")
    }
}
