package com.soloit.doubleentry.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
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
    version = 1,
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

    /** Emits once immediately and again whenever any ledger table changes (local edit or sync pull). */
    fun ledgerChanges(): Flow<Unit> =
        invalidationTracker.createFlow(*TABLES).map { }

    companion object {
        val TABLES = arrayOf("accounts", "transactions", "transaction_entries", "assets", "invoices", "user_settings")

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "doubleentry.db").build()
    }
}
