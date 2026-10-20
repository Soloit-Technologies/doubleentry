package com.soloit.doubleentry.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.AppCore
import com.soloit.doubleentry.data.CsvExport
import com.soloit.doubleentry.data.local.nowMicros
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate

data class DialogMessage(val title: String, val text: String)

class DataManagementViewModel(private val c: AppCore) : ViewModel() {
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

    /** [open] opens the file the user chose, or returns null when it can't be opened. */
    fun export(open: () -> OutputStream?) = run("Export") {
        withContext(Dispatchers.IO) { open() }?.use { c.backup.export(it) } ?: error("Could not open the selected file.")
        "Your data was exported."
    }

    fun exportTransactionsCsv(open: () -> OutputStream?) = run("Export") {
        val transactions = c.ledger.getTransactions()
        writeCsv(open) { CsvExport.transactions(transactions, it) }
        "Exported ${transactions.size} transaction${if (transactions.size == 1) "" else "s"}."
    }

    fun exportBalancesCsv(open: () -> OutputStream?) = run("Export") {
        val today = LocalDate.now()
        val accounts = c.ledger.getAccounts()
        val balances = c.ledger.getBalances(today)
        writeCsv(open) { CsvExport.balances(accounts, balances, today, it) }
        "Exported the balances of ${accounts.size} accounts."
    }

    private suspend fun writeCsv(open: () -> OutputStream?, write: (java.io.Writer) -> Unit) = withContext(Dispatchers.IO) {
        val stream = open() ?: error("Could not open the selected file.")
        stream.bufferedWriter(Charsets.UTF_8).use { writer ->
            // A byte order mark makes Excel read the file as UTF-8 (å, ä, ö …).
            writer.write("\uFEFF")
            write(writer)
        }
    }

    fun import(open: () -> InputStream?) = run("Import") {
        if (c.sync.status.value.sharedLedger) return@run SHARED_LEDGER
        val before = nowMicros()
        withContext(Dispatchers.IO) { open() }?.use { c.backup.import(it) } ?: error("Could not open the selected file.")
        // Also replaces cloud rows this device never pulled (added on another device meanwhile).
        c.sync.replaceCloudLedger(before)
        "Your ledger has been replaced with the imported data."
    }

    fun repair() = run("Repair depreciation") {
        val repaired = c.assets.reconcileWriteoffs(LocalDate.now())
        if (repaired == 0) "No issues found. Your depreciation postings are up to date." else "Repaired $repaired depreciation posting(s)."
    }

    fun deleteAll() = run("Delete all data") {
        if (c.sync.status.value.sharedLedger) return@run SHARED_LEDGER
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
            val left = c.sessions.uploadBeforeReset()
            if (left > 0) {
                resetWouldLose.value = left
                return@run null
            }
        }
        c.sessions.resetDevice()
        "All data on this device has been cleared. Sign in again to download your data from the cloud."
    }
}

/** Replacing a whole ledger would do it for everyone in it, so it's left to the owner. */
private const val SHARED_LEDGER = "This ledger is shared with you, so only its owner can replace or delete all of it."
