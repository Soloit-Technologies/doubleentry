package com.soloit.doubleentry.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.soloit.doubleentry.AppCore
import com.soloit.doubleentry.data.InvoiceReminder
import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.desktop.platform.AppDirectories
import com.soloit.doubleentry.desktop.platform.BrowserSignIn
import com.soloit.doubleentry.desktop.platform.DesktopTokenStore
import com.soloit.doubleentry.desktop.platform.FileKeyValueStores
import com.soloit.doubleentry.desktop.platform.Notifications
import com.soloit.doubleentry.sync.SupabaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** The desktop app's dependencies; one instance lives for the whole process. */
class DesktopContainer(val appScope: CoroutineScope, dataDirectory: File = AppDirectories.data) {
    private val stores = FileKeyValueStores(File(dataDirectory, "preferences"))
    private val databases = File(dataDirectory, "databases").apply { mkdirs() }

    val core = AppCore(
        SupabaseConfig(
            url = BuildConfig.SUPABASE_URL,
            anonKey = BuildConfig.SUPABASE_ANON_KEY,
            featureEnabled = BuildConfig.CLOUD_SYNC_FEATURE,
            appVersionCode = BuildConfig.VERSION_CODE,
        ),
        stores,
        DesktopTokenStore(File(dataDirectory, "tokens.properties")),
        openDatabaseFile = { name ->
            AppDatabase.build(
                Room.databaseBuilder<AppDatabase>(File(databases, name).path)
                    .setDriver(BundledSQLiteDriver())
                    .setQueryCoroutineContext(Dispatchers.IO),
            )
        },
        deleteDatabaseFile = { name -> listOf("", "-wal", "-shm", "-journal").forEach { File(databases, name + it).delete() } },
        appScope,
    )

    /** Window size and position, and other settings only this computer uses. */
    val window = stores.open("window")

    val signIn = BrowserSignIn(core.sync)

    private val _openInvoices = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the user clicks the invoice reminder. */
    val openInvoices: SharedFlow<Unit> = _openInvoices.asSharedFlow()

    private val notifications = Notifications(onClick = { _openInvoices.tryEmit(Unit) })
    private val reminderState = stores.open("reminders")

    fun start() {
        core.start()
        core.sync.onForeground()
        appScope.launch { remindDaily() }
    }

    /** At start and then every day at 09:00 while the app runs: one notification about invoices that need paying. */
    private suspend fun remindDaily() {
        while (true) {
            core.startupComplete.first { it }
            runCatching { remind() }
            val now = LocalDateTime.now()
            val next = now.toLocalDate().plusDays(1).atTime(LocalTime.of(9, 0))
            delay(ChronoUnit.MILLIS.between(now, next).coerceAtLeast(60_000))
        }
    }

    private suspend fun remind() {
        val prefs = core.preferences.reminders.value
        val today = LocalDate.now()
        if (!prefs.enabled || reminderState.getString("lastShown") == today.toString()) return
        val due = InvoiceReminder.due(core.invoices, core.ledger, today, prefs.daysBefore)
        if (due.isEmpty()) return
        notifications.show(InvoiceReminder.title(due, today), InvoiceReminder.lines(due, today).take(3).joinToString("\n"))
        reminderState.edit { putString("lastShown", today.toString()) }
    }
}
