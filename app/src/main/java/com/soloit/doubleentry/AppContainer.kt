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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Manual dependency container; one instance lives in [DoubleEntryApp]. */
class AppContainer(private val context: Context, val appScope: CoroutineScope) {
    val supabaseConfig = SupabaseConfig(
        url = BuildConfig.SUPABASE_URL,
        anonKey = BuildConfig.SUPABASE_ANON_KEY,
        featureEnabled = BuildConfig.CLOUD_SYNC_FEATURE,
        googleClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
    )

    val database: AppDatabase = AppDatabase.build(context)
    val preferences = AppPreferences(context)
    private val syncPreferences = SyncPreferences(context).also { ServerClock.offsetMicros = it.clockOffsetMicros }
    private val api = SupabaseApi(supabaseConfig, onClockOffset = { syncPreferences.clockOffsetMicros = it })

    val sync = SyncManager(
        supabaseConfig, syncPreferences, api, SyncEngine(database, api, syncPreferences), appScope,
        pendingChanges = database.syncStatus().pendingChanges(),
    )
    /** Google Play subscriptions for cloud backup; null in builds without cloud sync. */
    val billing: CloudBilling? = if (supabaseConfig.isAvailable) CloudBilling(context, sync, appScope) else null
    val ledger = LedgerRepository(database, sync)
    val assets = AssetRepository(database, ledger, sync)
    val settings = SettingsRepository(database, sync)
    val invoices = InvoiceRepository(database, ledger, settings, sync)
    val recurring = RecurringRepository(database, ledger, settings, sync)
    val reconciliations = ReconciliationRepository(database, ledger, settings)
    val statementImports = StatementImporter(database, ledger, invoices, sync)
    val backup = BackupService(database, ledger, settings, sync)
    val undo = UndoManager(appScope)
    val appLock = AppLock(preferences)

    private val _startupComplete = MutableStateFlow(false)

    /** False until the initial cloud pull (when enabled) has finished; used to delay first-run prompts. */
    val startupComplete: StateFlow<Boolean> = _startupComplete.asStateFlow()

    @OptIn(FlowPreview::class)
    fun start() {
        appScope.launch {
            runCatching { sync.pullIfNewer() }
            _startupComplete.value = true
            checkPurchases()
            // Non-critical: post any depreciation and recurring transactions that became due since the app last ran.
            runCatching { assets.processWriteoffs(LocalDate.now()) }
            runCatching { recurring.process(LocalDate.now()) }
        }
        // Keep the home-screen widget in step with the ledger and the lock setting.
        appScope.launch {
            combine(ledger.changes, preferences.appLock) { _, _ -> }
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
        appScope.launch {
            runCatching { assets.processWriteoffs(LocalDate.now()) }
            runCatching { recurring.process(LocalDate.now()) }
        }
        checkPurchases()
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
