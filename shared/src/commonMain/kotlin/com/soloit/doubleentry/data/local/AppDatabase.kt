package com.soloit.doubleentry.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.execSQL
import androidx.room.immediateTransaction
import androidx.room.migration.Migration
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class Converters {
    @TypeConverter
    fun dateToString(date: LocalDate?): String? = date?.toString()

    @TypeConverter
    fun stringToDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
}

@Database(
    entities = [
        AccountEntity::class,
        TransactionEntity::class,
        EntryEntity::class,
        AssetEntity::class,
        InvoiceEntity::class,
        UserSettingEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun transactions(): TransactionDao
    abstract fun entries(): EntryDao
    abstract fun assets(): AssetDao
    abstract fun invoices(): InvoiceDao
    abstract fun settings(): UserSettingDao
    abstract fun syncStatus(): SyncStatusDao

    /** Emits once immediately and again whenever any ledger table changes (local edit or sync pull). */
    fun ledgerChanges(): Flow<Unit> =
        invalidationTracker.createFlow(*TABLES).map { }

    companion object {
        val TABLES = arrayOf("accounts", "transactions", "transaction_entries", "assets", "invoices", "user_settings")

        /** The device's main database, which holds the account's own ledger (or the only one, without an account). */
        const val MAIN = "doubleentry.db"

        /** The database of a ledger shared with the account, [ledgerId]. */
        fun sharedLedgerName(ledgerId: String) = "ledger-$ledgerId.db"

        /** Finishes a platform's [builder] (file location, SQLite driver) with what every database needs. */
        fun build(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase =
            builder.addMigrations(MIGRATION_1_2).build()

        /**
         * Lines lose their own sync bookkeeping: they now sync inside their transaction. Lines that
         * were replaced (tombstoned) are dropped; a deleted transaction keeps the lines it was
         * deleted with (they share its deletion stamp), so undoing the delete still works. A
         * transaction with unsynced line changes is queued for upload as a whole.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("UPDATE transactions SET is_dirty = 1 WHERE id IN (SELECT transaction_id FROM transaction_entries WHERE is_dirty = 1)")
                connection.execSQL(
                    """CREATE TABLE `transaction_entries_new` (`id` TEXT NOT NULL, `transaction_id` TEXT NOT NULL,
                       `account_id` TEXT NOT NULL, `amount_cents` INTEGER NOT NULL, `entry_type` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
                )
                connection.execSQL(
                    """INSERT INTO transaction_entries_new (id, transaction_id, account_id, amount_cents, entry_type)
                       SELECT e.id, e.transaction_id, e.account_id, e.amount_cents, e.entry_type
                       FROM transaction_entries e LEFT JOIN transactions t ON t.id = e.transaction_id
                       WHERE e.deleted_at IS NULL OR e.deleted_at = t.deleted_at""",
                )
                connection.execSQL("DROP TABLE transaction_entries")
                connection.execSQL("ALTER TABLE transaction_entries_new RENAME TO transaction_entries")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_entries_transaction_id` ON `transaction_entries` (`transaction_id`)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_entries_account_id` ON `transaction_entries` (`account_id`)")
            }
        }
    }
}

/** Room generates the actual object for each platform. */
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/** Runs [block] in one write transaction; a call inside another transaction joins it. */
suspend fun <R> AppDatabase.withTransaction(block: suspend () -> R): R =
    useWriterConnection { connection -> connection.immediateTransaction { block() } }

/** Deletes every row of every table, in one transaction. */
suspend fun AppDatabase.deleteAllRows() =
    useWriterConnection { connection ->
        connection.immediateTransaction { AppDatabase.TABLES.forEach { execSQL("DELETE FROM `$it`") } }
    }
