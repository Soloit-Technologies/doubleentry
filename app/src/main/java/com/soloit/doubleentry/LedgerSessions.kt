package com.soloit.doubleentry

import com.soloit.doubleentry.data.AssetRepository
import com.soloit.doubleentry.data.BackupService
import com.soloit.doubleentry.data.InvoiceRepository
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.data.ReconciliationRepository
import com.soloit.doubleentry.data.RecurringRepository
import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.data.StatementImporter
import com.soloit.doubleentry.data.SyncTrigger
import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.sync.LocalLedger
import com.soloit.doubleentry.sync.SupabaseApi
import com.soloit.doubleentry.sync.SyncEngine
import com.soloit.doubleentry.sync.SyncException
import com.soloit.doubleentry.sync.SyncManager
import com.soloit.doubleentry.sync.SyncPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/** One ledger's database with every repository on it, wired as the app uses them. */
class LedgerSession(
    /** Null for the device's main database (the account's own ledger); else the shared ledger's id. */
    val slot: String?,
    val database: AppDatabase,
    sync: SyncTrigger,
) {
    val ledger = LedgerRepository(database, sync)
    val assets = AssetRepository(database, ledger, sync)
    val settings = SettingsRepository(database, sync)
    val invoices = InvoiceRepository(database, ledger, settings, sync)
    val recurring = RecurringRepository(database, ledger, settings, sync)
    val reconciliations = ReconciliationRepository(database, ledger, settings)
    val statementImports = StatementImporter(database, ledger, invoices, sync)
    val backup = BackupService(database, ledger, settings, sync)
}

/**
 * The ledgers on this device and the one that's open. The account's own ledger lives in the main
 * database; a ledger shared with the account gets a database of its own the first time it's
 * opened, and loses it when the account leaves the ledger or signs out. Shared ledgers are copies
 * of the cloud's, so removing one from the device loses only changes that hadn't been uploaded.
 */
class LedgerSessions(
    private val sync: SyncManager,
    private val api: SupabaseApi,
    private val scope: CoroutineScope,
    /** The sync state of a ledger's database ([SyncPreferences.ledgerSlot]). */
    private val prefsFor: (slot: String?) -> SyncPreferences,
    private val openDatabase: (slot: String?) -> AppDatabase,
    private val deleteDatabase: (slot: String) -> Unit,
    initial: LedgerSession,
    /** Runs after a ledger was opened and synced, e.g. to post what became due in it. */
    private val onOpened: suspend (LedgerSession) -> Unit = {},
) {
    private val account = prefsFor(null)
    private val lock = Mutex()
    private val sessions = mutableMapOf(initial.slot to initial)
    private val _active = MutableStateFlow(initial)

    /** The open ledger. Screens are rebuilt when it changes. */
    val active: StateFlow<LedgerSession> = _active.asStateFlow()

    private val _shared = MutableStateFlow(account.localLedgers)

    /** Ledgers shared with the account that have a copy on this device. */
    val sharedOnDevice: StateFlow<List<LocalLedger>> = _shared.asStateFlow()

    /** Opens [ledgerId], one of the account's ledgers, downloading it the first time. */
    suspend fun open(ledgerId: String) = lock.withLock {
        val slot = slotOf(ledgerId)
        if (slot == _active.value.slot) return@withLock
        // Upload what's waiting in the ledger being left; otherwise it goes up when that one is next open.
        sync.syncNow()
        if (slot != null && account.localLedgers.none { it.id == slot }) {
            val name = account.ledgers.firstOrNull { it.id == slot }?.name ?: "Shared ledger"
            setShared(account.localLedgers + LocalLedger(slot, name))
        }
        activate(slot)
        sync.syncNow()
        onOpened(_active.value)
    }

    /** Joins the ledger [code] invites to and opens it. Returns its id. */
    suspend fun join(code: String): String {
        val ledgerId = sync.acceptInvite(code)
        open(ledgerId)
        return ledgerId
    }

    /** Leaves a ledger shared with the account, and removes it from this device. */
    suspend fun leave(ledgerId: String) {
        val me = sync.userId ?: throw SyncException("Please sign in again.")
        sync.removeMember(ledgerId, me)
        removeFromDevice(ledgerId)
    }

    /** Removes a shared ledger's copy from this device (after the account lost access to it), opening the account's own if it was open. */
    suspend fun removeFromDevice(ledgerId: String) = lock.withLock { drop(ledgerId) }

    /** Signs out; shared ledgers are removed from this device, the account's own stays. */
    suspend fun signOut() = lock.withLock {
        dropShared()
        sync.signOut()
    }

    /** Deletes the account for good; shared ledgers are removed from this device, the own one stays here. */
    suspend fun deleteAccount() = lock.withLock {
        // Deleting the account forgets which shared ledgers are here, so note them first.
        val shared = account.localLedgers
        sync.deleteAccount()
        dropShared(shared)
    }

    /**
     * Before a device reset: tries to upload everything, then returns how many changes would still
     * be lost. The account's own ledger is opened for it; shared ones were uploaded when they were left.
     */
    suspend fun uploadBeforeReset(): Int = lock.withLock {
        if (_active.value.slot != null) {
            sync.syncNow()
            activate(null)
        }
        sync.uploadBeforeReset()
    }

    /** Signs out and deletes every ledger on this device, without touching the cloud. */
    suspend fun resetDevice() = lock.withLock {
        dropShared()
        sync.resetDevice()
    }

    private fun slotOf(ledgerId: String): String? = if (ledgerId == account.ownLedgerId) null else ledgerId

    private suspend fun activate(slot: String?) {
        val session = sessions.getOrPut(slot) { LedgerSession(slot, openDatabase(slot), sync) }
        val prefs = prefsFor(slot)
        sync.use(prefs, SyncEngine(session.database, api, prefs), session.database.syncStatus().pendingChanges())
        account.activeLedgerSlot = slot
        _active.value = session
    }

    private fun setShared(ledgers: List<LocalLedger>) {
        account.localLedgers = ledgers
        _shared.value = ledgers
    }

    private suspend fun dropShared(shared: List<LocalLedger> = account.localLedgers) {
        shared.forEach { drop(it.id) }
        if (_active.value.slot != null) activate(null)
    }

    private suspend fun drop(slot: String) {
        if (_active.value.slot == slot) activate(null)
        prefsFor(slot).clearLedger()
        setShared(account.localLedgers.filter { it.id != slot })
        val database = sessions.remove(slot)?.database
        deleteDatabase(slot)
        // Screens of the ledger may still be finishing a query; their database closes once they're gone.
        if (database != null) scope.launch { delay(10.seconds); database.close() }
    }
}
