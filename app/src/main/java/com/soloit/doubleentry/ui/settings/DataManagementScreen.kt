package com.soloit.doubleentry.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.TableView
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.AppContainer
import com.soloit.doubleentry.data.CsvExport
import com.soloit.doubleentry.data.local.nowMicros
import com.soloit.doubleentry.ui.LocalAppContainer
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.ConfirmDialog
import com.soloit.doubleentry.ui.components.GroupedItem
import com.soloit.doubleentry.ui.components.MessageDialog
import com.soloit.doubleentry.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class DialogMessage(val title: String, val text: String)

class DataManagementViewModel(private val c: AppContainer) : ViewModel() {
    val busy = MutableStateFlow(false)
    val result = MutableStateFlow<DialogMessage?>(null)

    /** Changes a reset would lose because they couldn't be uploaded; asks before going ahead. */
    val resetWouldLose = MutableStateFlow<Int?>(null)

    /** Runs [block]; its message (if any) is shown when it finishes. */
    private fun run(title: String, block: suspend () -> String?) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            // Data operations must finish even if the user leaves the screen.
            withContext(NonCancellable) {
                result.value = try {
                    block()?.let { DialogMessage(title, it) }
                } catch (e: Exception) {
                    DialogMessage("$title failed", e.message ?: "Something went wrong.")
                } finally {
                    busy.value = false
                }
            }
        }
    }

    fun export(resolver: ContentResolver, uri: Uri) = run("Export") {
        resolver.openOutputStream(uri, "wt")?.use { c.backup.export(it) } ?: error("Could not open the selected file.")
        "Your data was exported."
    }

    fun exportTransactionsCsv(resolver: ContentResolver, uri: Uri) = run("Export") {
        val transactions = c.ledger.getTransactions()
        writeCsv(resolver, uri) { CsvExport.transactions(transactions, it) }
        "Exported ${transactions.size} transaction${if (transactions.size == 1) "" else "s"}."
    }

    fun exportBalancesCsv(resolver: ContentResolver, uri: Uri) = run("Export") {
        val today = LocalDate.now()
        val accounts = c.ledger.getAccounts()
        val balances = c.ledger.getBalances(today)
        writeCsv(resolver, uri) { CsvExport.balances(accounts, balances, today, it) }
        "Exported the balances of ${accounts.size} accounts."
    }

    private suspend fun writeCsv(resolver: ContentResolver, uri: Uri, write: (java.io.Writer) -> Unit) = withContext(Dispatchers.IO) {
        val stream = resolver.openOutputStream(uri, "wt") ?: error("Could not open the selected file.")
        stream.bufferedWriter(Charsets.UTF_8).use { writer ->
            // A byte order mark makes Excel read the file as UTF-8 (å, ä, ö …).
            writer.write("\uFEFF")
            write(writer)
        }
    }

    fun import(resolver: ContentResolver, uri: Uri) = run("Import") {
        val before = nowMicros()
        resolver.openInputStream(uri)?.use { c.backup.import(it) } ?: error("Could not open the selected file.")
        // Also replaces cloud rows this device never pulled (added on another device meanwhile).
        c.sync.replaceCloudLedger(before)
        "Your ledger has been replaced with the imported data."
    }

    fun repair() = run("Repair depreciation") {
        val repaired = c.assets.reconcileWriteoffs(LocalDate.now())
        if (repaired == 0) "No issues found. Your depreciation postings are up to date." else "Repaired $repaired depreciation posting(s)."
    }

    fun deleteAll() = run("Delete all data") {
        val before = nowMicros()
        c.ledger.deleteAllData()
        c.sync.replaceCloudLedger(before)
        "All data has been deleted."
    }

    /**
     * Signs out and wipes the local database without touching the cloud copy. Uploads first; when
     * changes are still left (offline, paused, signed out), asks before they are lost.
     */
    fun factoryReset(evenIfUnsynced: Boolean = false) = run("Reset device") {
        if (!evenIfUnsynced) {
            val left = c.sync.uploadBeforeReset()
            if (left > 0) {
                resetWouldLose.value = left
                return@run null
            }
        }
        c.sync.resetDevice()
        "All data on this device has been cleared. Sign in again to download your data from the cloud."
    }
}

private enum class Confirm { Import, DeleteFirst, DeleteSecond, Repair, Reset }

@Composable
fun DataManagementScreen(onBack: () -> Unit) {
    val vm = appViewModel { DataManagementViewModel(it) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val resetWouldLose by vm.resetWouldLose.collectAsStateWithLifecycle()
    val sync by LocalAppContainer.current.sync.status.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.export(resolver, it) }
    }
    val transactionsCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { vm.exportTransactionsCsv(resolver, it) }
    }
    val balancesCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { vm.exportBalancesCsv(resolver, it) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingImport = uri
            confirm = Confirm.Import
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Data management") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                item { SectionHeader("Backup") }
                item {
                    Column {
                        GroupedItem(0, 2, onClick = {
                            if (!busy) exportLauncher.launch("doubleentry-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.json")
                        }) {
                            SettingRow(Icons.Rounded.Download, "Export to JSON", "Save a full backup file (compatible with the previous app)")
                        }
                        GroupedItem(1, 2, onClick = { if (!busy) importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                            SettingRow(Icons.Rounded.Upload, "Import from JSON", "Replace all data with a backup file")
                        }
                    }
                }
                item { SectionHeader("Spreadsheets") }
                item {
                    val stamp = LocalDate.now().toString()
                    Column {
                        GroupedItem(0, 2, onClick = { if (!busy) transactionsCsvLauncher.launch("doubleentry-transactions-$stamp.csv") }) {
                            SettingRow(Icons.Rounded.TableView, "Export transactions (CSV)", "Every transaction line with date, account, debit and credit")
                        }
                        GroupedItem(1, 2, onClick = { if (!busy) balancesCsvLauncher.launch("doubleentry-balances-$stamp.csv") }) {
                            SettingRow(Icons.Rounded.TableView, "Export balances (CSV)", "Each account's balance as of today")
                        }
                    }
                }
                item { SectionHeader("Maintenance") }
                item {
                    GroupedItem(0, 1, onClick = { if (!busy) confirm = Confirm.Repair }) {
                        SettingRow(Icons.Rounded.Build, "Repair depreciation", "Restore missing or accidentally deleted write-offs")
                    }
                }
                item { SectionHeader("Danger zone") }
                item {
                    // Resetting only makes sense with a cloud copy to come back to.
                    val count = if (sync.isSignedIn) 2 else 1
                    Column {
                        GroupedItem(0, count, onClick = { if (!busy) confirm = Confirm.DeleteFirst }) {
                            SettingRow(
                                Icons.Rounded.DeleteForever,
                                if (sync.isSignedIn) "Delete all data (including cloud)" else "Delete all data",
                                "Permanently removes accounts, transactions, assets and invoices",
                            )
                        }
                        if (sync.isSignedIn) {
                            GroupedItem(1, count, onClick = { if (!busy) confirm = Confirm.Reset }) {
                                SettingRow(Icons.Rounded.RestartAlt, "Reset this device", "Sign out and clear the data on this device. Cloud data is kept.")
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Backups contain accounts, transactions, assets, invoices, recurring transactions, reconciliations and report settings. " +
                            "CSV files are for spreadsheets and can't be imported.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, start = 4.dp),
                    )
                }
            }
        }
    }

    when (confirm) {
        Confirm.Import -> ConfirmDialog(
            title = "Import from JSON?",
            message = when {
                sync.isSignedIn -> "All accounts, transactions, assets and invoices will be replaced with the contents of the file — " +
                    "on this device and in the cloud, so all your synced devices will show the imported data. This cannot be undone."
                sync.dataOwnerEmail != null -> "All accounts, transactions, assets and invoices on this device will be replaced with the contents of the file. " +
                    "This cannot be undone. Your cloud copy isn't changed now; when you sign in again you'll be asked whether to replace it too."
                else -> "All current accounts, transactions, assets and invoices will be replaced with the contents of the file. This cannot be undone."
            },
            confirmLabel = "Replace data",
            destructive = true,
            onConfirm = { confirm = null; pendingImport?.let { vm.import(resolver, it) } },
            onDismiss = { confirm = null },
        )
        Confirm.Repair -> ConfirmDialog(
            title = "Repair depreciation?",
            message = "Checks every asset and restores any missing or deleted depreciation postings.",
            confirmLabel = "Repair",
            onConfirm = { confirm = null; vm.repair() },
            onDismiss = { confirm = null },
        )
        Confirm.DeleteFirst -> ConfirmDialog(
            title = if (sync.isSignedIn) "Delete all data, including the cloud?" else "Delete all data?",
            message = when {
                sync.isSignedIn -> "This permanently deletes all accounts and transactions from this device, from the cloud and from all your synced devices."
                sync.dataOwnerEmail != null -> "This permanently deletes all accounts and transactions on this device. " +
                    "Your cloud copy isn't changed now; when you sign in again you'll be asked whether to delete it there too."
                else -> "This permanently deletes all accounts and transactions."
            },
            confirmLabel = "Continue",
            destructive = true,
            onConfirm = { confirm = Confirm.DeleteSecond },
            onDismiss = { confirm = null },
        )
        Confirm.DeleteSecond -> ConfirmDialog(
            title = "Are you absolutely sure?",
            message = "There is no undo. All your financial data will be gone.",
            confirmLabel = "Delete everything",
            destructive = true,
            onConfirm = { confirm = null; vm.deleteAll() },
            onDismiss = { confirm = null },
        )
        Confirm.Reset -> ConfirmDialog(
            title = "Reset this device?",
            message = "Any remaining changes are uploaded first. Then you're signed out and the data on this device is deleted. " +
                "Your cloud data is not touched, so you can sign in again to download it.",
            confirmLabel = "Reset",
            destructive = true,
            onConfirm = { confirm = null; vm.factoryReset() },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }
    resetWouldLose?.let { count ->
        ConfirmDialog(
            title = "Changes not uploaded",
            message = "${changesText(count)} on this device couldn't be uploaded, so resetting now deletes " +
                "${if (count == 1) "it" else "them"} for good.\n\n" +
                (sync.lastError ?: if (!sync.enabled) "Sync is paused." else "") +
                " Fix sync and try again, or reset anyway.",
            confirmLabel = "Reset anyway",
            destructive = true,
            onConfirm = { vm.resetWouldLose.value = null; vm.factoryReset(evenIfUnsynced = true) },
            onDismiss = { vm.resetWouldLose.value = null },
        )
    }
    result?.let { MessageDialog(it.title, it.text) { vm.result.value = null } }
}
