package com.soloit.doubleentry.testing

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.platform.MemoryKeyValueStores
import kotlinx.coroutines.Dispatchers
import java.time.LocalDate

/**
 * One "device": an in-memory Room database with every repository wired as in AppContainer.
 * Several instances can share a [FakePostgrest] to test sync between devices.
 */
class TestLedger(val name: String = "device") : AutoCloseable {
    /** This device's own preferences, so sync watermarks don't leak between devices. */
    val stores = MemoryKeyValueStores()

    val db: AppDatabase = inMemoryDatabase()

    var syncRequests = 0
        private set
    private val sync = SyncTrigger { syncRequests++ }

    val ledger = LedgerRepository(db, sync)
    val settings = SettingsRepository(db, sync)
    val assets = AssetRepository(db, ledger, sync)
    val invoices = InvoiceRepository(db, ledger, settings, sync)
    val recurring = RecurringRepository(db, ledger, settings, sync)
    val reconciliations = ReconciliationRepository(db, ledger, settings)
    val backup = BackupService(db, ledger, settings, sync)
    val statementImports = StatementImporter(db, ledger, invoices, sync)

    suspend fun account(number: Int, name: String, type: AccountType): Account = ledger.createAccount(number, name, type)

    /** Posts "[debit] / [credit]" for [amount] cents. */
    suspend fun post(date: LocalDate, title: String, debit: Account, credit: Account, amount: Long): String =
        ledger.post(TransactionDraft(date, title, "", listOf(DraftLine(debit.id, amount, EntryType.Debit), DraftLine(credit.id, amount, EntryType.Credit))))

    suspend fun liveTransactionCount(): Int = ledger.getTransactions().size

    override fun close() = db.close()
}

/** An empty database in memory, on the SQLite build the desktop app ships. */
fun inMemoryDatabase(): AppDatabase =
    AppDatabase.build(Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO))
