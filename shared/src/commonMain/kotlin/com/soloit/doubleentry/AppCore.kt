package com.soloit.doubleentry

import com.soloit.doubleentry.data.AppPreferences
import com.soloit.doubleentry.data.AssetRepository
import com.soloit.doubleentry.data.BackupService
import com.soloit.doubleentry.data.InvoiceRepository
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.ReconciliationRepository
import com.soloit.doubleentry.data.RecurringRepository
import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.data.StatementImporter
import com.soloit.doubleentry.data.UndoManager
import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.data.local.ServerClock
import com.soloit.doubleentry.platform.KeyValueStores
import com.soloit.doubleentry.sync.SupabaseApi
import com.soloit.doubleentry.sync.SupabaseConfig
import com.soloit.doubleentry.sync.SyncEngine
import com.soloit.doubleentry.sync.SyncManager
import com.soloit.doubleentry.sync.SyncPreferences
import com.soloit.doubleentry.sync.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * What every app wires the same way: the ledgers on this device and the open one's repositories,
 * cloud sync, and the work done at startup. Each platform supplies its storage.
 */
class AppCore(
    val supabaseConfig: SupabaseConfig,
    private val stores: KeyValueStores,
    private val tokens: TokenStore,
    /** Opens the database file [name], creating it the first time. */
    private val openDatabaseFile: (name: String) -> AppDatabase,
    private val deleteDatabaseFile: (name: String) -> Unit,
    val appScope: CoroutineScope,
) {
    val preferences = AppPreferences(stores)
    private val syncPreferences = SyncPreferences(stores, tokens).also { ServerClock.offsetMicros = it.clockOffsetMicros }
    private val api = SupabaseApi(supabaseConfig, onClockOffset = { syncPreferences.clockOffsetMicros = it })

    private fun prefsFor(slot: String?) = if (slot == null) syncPreferences else SyncPreferences(stores, tokens, slot)

    private fun openDatabase(slot: String?) = openDatabaseFile(slot?.let(AppDatabase::sharedLedgerName) ?: AppDatabase.MAIN)

    // The ledger open when the app was last used; shared ledgers only exist while signed in.
    private val initialSlot = syncPreferences.activeLedgerSlot
        ?.takeIf { slot -> syncPreferences.userId != null && syncPreferences.localLedgers.any { it.id == slot } }
    private val initialDatabase = openDatabase(initialSlot)

    val sync = SyncManager(
        supabaseConfig, prefsFor(initialSlot), api, SyncEngine(initialDatabase, api, prefsFor(initialSlot)), appScope,
        pendingChanges = initialDatabase.syncStatus().pendingChanges(),
    )

    /** The ledgers on this device; everything below works on the open one. */
    val sessions = LedgerSessions(
        sync, api, appScope, ::prefsFor, ::openDatabase,
        deleteDatabase = { slot -> deleteDatabaseFile(AppDatabase.sharedLedgerName(slot)) },
        initial = LedgerSession(initialSlot, initialDatabase, sync),
        onOpened = { processDue() },
    )

    private val session: LedgerSession get() = sessions.active.value
    val database: AppDatabase get() = session.database
    val ledger: LedgerRepository get() = session.ledger
    val assets: AssetRepository get() = session.assets
    val settings: SettingsRepository get() = session.settings
    val invoices: InvoiceRepository get() = session.invoices
    val recurring: RecurringRepository get() = session.recurring
    val reconciliations: ReconciliationRepository get() = session.reconciliations
    val statementImports: StatementImporter get() = session.statementImports
    val backup: BackupService get() = session.backup
    val undo = UndoManager(appScope)

    private val _startupComplete = MutableStateFlow(false)

    /** False until the initial cloud pull (when enabled) has finished; used to delay first-run prompts. */
    val startupComplete: StateFlow<Boolean> = _startupComplete.asStateFlow()

    /** Pulls from the cloud, then posts what became due; [afterStartup] runs once that's done. */
    fun start(afterStartup: suspend () -> Unit = {}) {
        appScope.launch {
            runCatching { sync.pullIfNewer() }
            _startupComplete.value = true
            afterStartup()
            processDue()
        }
    }

    /** Runs when the app returns to the foreground: catches up on postings that became due while it was away. */
    fun onForeground() {
        // The first start is handled by start(), after the initial cloud pull.
        if (!_startupComplete.value) return
        appScope.launch { processDue() }
    }

    /** Non-critical: posts the depreciation and recurring transactions of the open ledger that became due since it was last open. */
    private suspend fun processDue() {
        val session = session
        runCatching { session.assets.processWriteoffs(LocalDate.now()) }
        runCatching { session.recurring.process(LocalDate.now()) }
    }
}
