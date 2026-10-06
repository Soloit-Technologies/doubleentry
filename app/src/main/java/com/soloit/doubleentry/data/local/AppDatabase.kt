package com.soloit.doubleentry.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "doubleentry.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /**
         * Lines lose their own sync bookkeeping: they now sync inside their transaction. Lines that
         * were replaced (tombstoned) are dropped; a deleted transaction keeps the lines it was
         * deleted with (they share its deletion stamp), so undoing the delete still works. A
         * transaction with unsynced line changes is queued for upload as a whole.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE transactions SET is_dirty = 1 WHERE id IN (SELECT transaction_id FROM transaction_entries WHERE is_dirty = 1)")
                db.execSQL(
                    """CREATE TABLE `transaction_entries_new` (`id` TEXT NOT NULL, `transaction_id` TEXT NOT NULL,
                       `account_id` TEXT NOT NULL, `amount_cents` INTEGER NOT NULL, `entry_type` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
                )
                db.execSQL(
                    """INSERT INTO transaction_entries_new (id, transaction_id, account_id, amount_cents, entry_type)
                       SELECT e.id, e.transaction_id, e.account_id, e.amount_cents, e.entry_type
                       FROM transaction_entries e LEFT JOIN transactions t ON t.id = e.transaction_id
                       WHERE e.deleted_at IS NULL OR e.deleted_at = t.deleted_at""",
                )
                db.execSQL("DROP TABLE transaction_entries")
                db.execSQL("ALTER TABLE transaction_entries_new RENAME TO transaction_entries")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_entries_transaction_id` ON `transaction_entries` (`transaction_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transaction_entries_account_id` ON `transaction_entries` (`account_id`)")
            }
        }
    }
}
