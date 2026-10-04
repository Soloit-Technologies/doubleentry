package com.soloit.doubleentry.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import java.time.LocalDate

/**
 * One "device": an in-memory Room database with every repository wired as in AppContainer.
 * Several instances can share a [FakePostgrest] to test sync between devices.
 */
class TestLedger(name: String = "device") : AutoCloseable {
    /** Separate SharedPreferences per device, so sync watermarks don't leak between them. */
    val context: Context = DeviceContext(ApplicationProvider.getApplicationContext(), name)

    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

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

    private class DeviceContext(base: Context, private val prefix: String) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("$prefix-$name", mode)
    }
}
