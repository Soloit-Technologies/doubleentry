package com.soloit.doubleentry

import android.content.Context
import com.soloit.doubleentry.data.AppPreferences
import com.soloit.doubleentry.data.AssetRepository
import com.soloit.doubleentry.data.BackupService
import com.soloit.doubleentry.data.InvoiceRepository
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.sync.SupabaseApi
import com.soloit.doubleentry.sync.SupabaseConfig
import com.soloit.doubleentry.sync.SyncEngine
import com.soloit.doubleentry.sync.SyncManager
import com.soloit.doubleentry.sync.SyncPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Manual dependency container; one instance lives in [DoubleEntryApp]. */
class AppContainer(context: Context, val appScope: CoroutineScope) {
    val supabaseConfig = SupabaseConfig(
        url = BuildConfig.SUPABASE_URL,
        anonKey = BuildConfig.SUPABASE_ANON_KEY,
        featureEnabled = BuildConfig.CLOUD_SYNC_FEATURE,
    )

    val database: AppDatabase = AppDatabase.build(context)
    val preferences = AppPreferences(context)
    private val syncPreferences = SyncPreferences(context)
    private val api = SupabaseApi(supabaseConfig)

    val sync = SyncManager(supabaseConfig, syncPreferences, api, SyncEngine(database, api, syncPreferences), appScope)
    val ledger = LedgerRepository(database, sync)
    val assets = AssetRepository(database, ledger, sync)
    val invoices = InvoiceRepository(database, ledger, sync)
    val settings = SettingsRepository(database, sync)
    val backup = BackupService(database, ledger, settings, sync)

    private val _startupComplete = MutableStateFlow(false)

    /** False until the initial cloud pull (when enabled) has finished; used to delay first-run prompts. */
    val startupComplete: StateFlow<Boolean> = _startupComplete.asStateFlow()

    fun start() {
        appScope.launch {
            runCatching { sync.pullIfNewer() }
            _startupComplete.value = true
            // Non-critical: post any depreciation that became due since the app last ran.
            runCatching { assets.processWriteoffs(LocalDate.now()) }
        }
    }
}
