package com.soloit.doubleentry

import android.content.Context
import androidx.room.Room
import com.soloit.doubleentry.billing.CloudBilling
import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.sync.SupabaseConfig
import com.soloit.doubleentry.system.AppLock
import com.soloit.doubleentry.system.InvoiceReminders
import com.soloit.doubleentry.system.KeystoreTokenStore
import com.soloit.doubleentry.system.NetWorthWidget
import com.soloit.doubleentry.system.SharedPreferencesStores
import com.soloit.doubleentry.system.Shortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** Manual dependency container; one instance lives in [DoubleEntryApp]. */
class AppContainer(private val context: Context, val appScope: CoroutineScope) {
    /** What every app wires the same way; the properties below are shortcuts into it. */
    val core = AppCore(
        SupabaseConfig(
            url = BuildConfig.SUPABASE_URL,
            anonKey = BuildConfig.SUPABASE_ANON_KEY,
            featureEnabled = BuildConfig.CLOUD_SYNC_FEATURE,
            googleClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
            appVersionCode = BuildConfig.VERSION_CODE,
        ),
        SharedPreferencesStores(context),
        KeystoreTokenStore(context),
        openDatabaseFile = { name -> AppDatabase.build(Room.databaseBuilder(context, AppDatabase::class.java, name)) },
        deleteDatabaseFile = { name -> context.deleteDatabase(name) },
        appScope,
    )

    val supabaseConfig get() = core.supabaseConfig
    val preferences get() = core.preferences
    val sync get() = core.sync

    /** The ledgers on this device; everything below works on the open one. */
    val sessions get() = core.sessions

    /** Google Play subscriptions for cloud backup; null in builds without cloud sync. */
    val billing: CloudBilling? = if (supabaseConfig.isAvailable) CloudBilling(context, sync, appScope) else null

    val database get() = core.database
    val ledger get() = core.ledger
    val assets get() = core.assets
    val settings get() = core.settings
    val invoices get() = core.invoices
    val recurring get() = core.recurring
    val reconciliations get() = core.reconciliations
    val statementImports get() = core.statementImports
    val backup get() = core.backup
    val undo get() = core.undo
    val appLock = AppLock(preferences)

    /** False until the initial cloud pull (when enabled) has finished; used to delay first-run prompts. */
    val startupComplete get() = core.startupComplete

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start() {
        core.start(afterStartup = { checkPurchases() })
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
        val started = startupComplete.value
        core.onForeground()
        // The first start checks purchases after the initial cloud pull.
        if (started) checkPurchases()
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
