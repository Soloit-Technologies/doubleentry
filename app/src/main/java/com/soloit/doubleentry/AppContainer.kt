package com.soloit.doubleentry

import android.content.Context
import com.soloit.doubleentry.billing.CloudBilling
import com.soloit.doubleentry.data.AppPreferences
import com.soloit.doubleentry.data.AssetRepository
import com.soloit.doubleentry.data.BackupService
import com.soloit.doubleentry.data.InvoiceRepository
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.ReconciliationRepository
import com.soloit.doubleentry.data.RecurringRepository
import com.soloit.doubleentry.data.StatementImporter
import com.soloit.doubleentry.data.UndoManager
import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.data.local.ServerClock
import com.soloit.doubleentry.sync.SupabaseApi
import com.soloit.doubleentry.sync.SupabaseConfig
import com.soloit.doubleentry.sync.SyncEngine
import com.soloit.doubleentry.sync.SyncManager
import com.soloit.doubleentry.sync.SyncPreferences
import com.soloit.doubleentry.system.AppLock
import com.soloit.doubleentry.system.InvoiceReminders
import com.soloit.doubleentry.system.NetWorthWidget
import com.soloit.doubleentry.system.Shortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Manual dependency container; one instance lives in [DoubleEntryApp]. */
class AppContainer(private val context: Context, val appScope: CoroutineScope) {
    val supabaseConfig = SupabaseConfig(
        url = BuildConfig.SUPABASE_URL,
        anonKey = BuildConfig.SUPABASE_ANON_KEY,
        featureEnabled = BuildConfig.CLOUD_SYNC_FEATURE,
        googleClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
        appVersionCode = BuildConfig.VERSION_CODE,
    )

    val preferences = AppPreferences(context)
    private val syncPreferences = SyncPreferences(context).also { ServerClock.offsetMicros = it.clockOffsetMicros }
    private val api = SupabaseApi(supabaseConfig, onClockOffset = { syncPreferences.clockOffsetMicros = it })

    private fun prefsFor(slot: String?) = if (slot == null) syncPreferences else SyncPreferences(context, ledgerSlot = slot)

    private fun openDatabase(slot: String?) = AppDatabase.build(context, slot?.let(AppDatabase::sharedLedgerName) ?: AppDatabase.MAIN)

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
        deleteDatabase = { slot -> context.deleteDatabase(AppDatabase.sharedLedgerName(slot)) },
        initial = LedgerSession(initialSlot, initialDatabase, sync),
        onOpened = { processDue() },
    )

    /** Google Play subscriptions for cloud backup; null in builds without cloud sync. */
    val billing: CloudBilling? = if (supabaseConfig.isAvailable) CloudBilling(context, sync, appScope) else null

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
    val appLock = AppLock(preferences)

    private val _startupComplete = MutableStateFlow(false)

    /** False until the initial cloud pull (when enabled) has finished; used to delay first-run prompts. */
    val startupComplete: StateFlow<Boolean> = _startupComplete.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start() {
        appScope.launch {
            runCatching { sync.pullIfNewer() }
            _startupComplete.value = true
            checkPurchases()
            processDue()
        }
        // Keep the home-screen widget in step with the open ledger and the lock setting.
        appScope.launch {
            combine(sessions.active.flatMapLatest { it.ledger.changes }, preferences.appLock) { _, _ -> }
                .debounce(1_000)
                .collect { runCatching { NetWorthWidget.updateAll(context) } }
        }
        InvoiceReminders.schedule(context, preferences.reminders.value)
        Shortcuts.publish(context)
    }

    /** Runs when the app returns to the foreground: catches up on postings that became due while it was away. */
    fun onForeground() {
        // The first start is handled by start(), after the initial cloud pull.
        if (!_startupComplete.value) return
        appScope.launch { processDue() }
        checkPurchases()
    }

    /** Non-critical: posts the depreciation and recurring transactions of the open ledger that became due since it was last open. */
    private suspend fun processDue() {
        val session = session
        runCatching { session.assets.processWriteoffs(LocalDate.now()) }
        runCatching { session.recurring.process(LocalDate.now()) }
    }

    /**
     * Sends Google Play purchases the server may have missed (the app closed mid-purchase, a
     * reinstall) to the server, so a paid subscription is never left unused or refunded.
     */
    private fun checkPurchases() {
        val billing = billing ?: return
        appScope.launch {
            sync.refreshAccess()
            runCatching { billing.restore(everything = sync.status.value.notBackedUp) }
        }
    }
}
