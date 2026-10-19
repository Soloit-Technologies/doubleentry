package com.soloit.doubleentry.ui.settings

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.ui.LocalAppContainer
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.ConfirmDialog
import com.soloit.doubleentry.ui.components.GroupedItem
import com.soloit.doubleentry.ui.components.MessageDialog
import com.soloit.doubleentry.ui.components.SectionHeader
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private enum class Confirm { Import, DeleteFirst, DeleteSecond, Repair, Reset }

@Composable
fun DataManagementScreen(onBack: () -> Unit) {
    val vm = appViewModel { DataManagementViewModel(it.core) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val resetWouldLose by vm.resetWouldLose.collectAsStateWithLifecycle()
    val sync by LocalAppContainer.current.sync.status.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { vm.export { resolver.openOutputStream(it, "wt") } }
    }
    val transactionsCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { vm.exportTransactionsCsv { resolver.openOutputStream(it, "wt") } }
    }
    val balancesCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { vm.exportBalancesCsv { resolver.openOutputStream(it, "wt") } }
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
            onConfirm = { confirm = null; pendingImport?.let { uri -> vm.import { resolver.openInputStream(uri) } } },
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
