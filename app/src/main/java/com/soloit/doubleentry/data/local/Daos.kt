package com.soloit.doubleentry.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Sync bookkeeping shared by every table. `markClean` only clears the dirty flag when the row
 * was not modified again while the push was in flight (its `updated_at` is still `updatedAt`).
 */
interface SyncDao<T : SyncEntity> {
    suspend fun dirty(): List<T>
    suspend fun byIds(ids: List<String>): List<T>
    suspend fun upsertAll(entities: List<T>)
    suspend fun markClean(id: String, updatedAt: Long)

    /**
     * Drops a row the server refused and doesn't hold: deleted and clean, so it never uploads, and
     * kept as a tombstone so seeding doesn't add it again. Unless edited again meanwhile, like [markClean].
     */
    suspend fun discard(id: String, updatedAt: Long)

    /** Revives every row soft-deleted at exactly [stamp] (one delete operation shares one stamp). */
    suspend fun restoreDeletedAt(stamp: Long, now: Long): Int
}

private const val ENTRY_WITH_ACCOUNT = """
    SELECT e.id AS id, e.transaction_id AS transactionId, e.amount_cents AS amountCents,
           e.entry_type AS entryType, a.id AS accountId, a.number AS accountNumber,
           a.name AS accountName, a.type AS accountType
    FROM transaction_entries e
    INNER JOIN accounts a ON a.id = e.account_id
    INNER JOIN transactions t ON t.id = e.transaction_id
    WHERE t.deleted_at IS NULL
"""

private const val TOTALS = """
    SELECT e.account_id AS accountId,
           SUM(CASE WHEN e.entry_type = 0 THEN e.amount_cents ELSE 0 END) AS debits,
           SUM(CASE WHEN e.entry_type = 1 THEN e.amount_cents ELSE 0 END) AS credits
    FROM transaction_entries e
    INNER JOIN transactions t ON t.id = e.transaction_id
    WHERE t.deleted_at IS NULL
"""

@Dao
interface AccountDao : SyncDao<AccountEntity> {
    @Query("SELECT * FROM accounts WHERE deleted_at IS NULL ORDER BY number")
    fun observeLive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE deleted_at IS NULL ORDER BY number")
    suspend fun live(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<AccountEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM accounts WHERE deleted_at IS NULL AND number = :number AND id != :excludeId)")
    suspend fun numberInUse(number: Int, excludeId: String = ""): Boolean

    @Upsert
    suspend fun upsert(entity: AccountEntity)

    @Upsert
    override suspend fun upsertAll(entities: List<AccountEntity>)

    @Query("SELECT * FROM accounts WHERE is_dirty = 1")
    override suspend fun dirty(): List<AccountEntity>

    @Query("UPDATE accounts SET is_dirty = 0 WHERE id = :id AND updated_at = :updatedAt")
    override suspend fun markClean(id: String, updatedAt: Long)

    @Query("UPDATE accounts SET deleted_at = COALESCE(deleted_at, updated_at), is_dirty = 0 WHERE id = :id AND updated_at = :updatedAt")
    override suspend fun discard(id: String, updatedAt: Long)

    @Query("UPDATE accounts SET deleted_at = NULL, updated_at = :now, is_dirty = 1 WHERE deleted_at = :stamp")
    override suspend fun restoreDeletedAt(stamp: Long, now: Long): Int

    @Query("UPDATE accounts SET deleted_at = :now, updated_at = :now, is_dirty = 1 WHERE deleted_at IS NULL")
    suspend fun softDeleteAll(now: Long)
}

@Dao
interface TransactionDao : SyncDao<TransactionEntity> {
    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE deleted_at IS NULL AND date BETWEEN :from AND :to ORDER BY date, id")
    suspend fun liveInRange(from: LocalDate, to: LocalDate): List<TransactionEntity>

    /** Most recent live transactions whose title contains [text] (`%`, `_` and `\` escaped by the caller). */
    @Query(
        """SELECT * FROM transactions WHERE deleted_at IS NULL AND title LIKE '%' || :text || '%' ESCAPE '\'
           ORDER BY date DESC, updated_at DESC LIMIT :limit""",
    )
    suspend fun liveWithTitleLike(text: String, limit: Int): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE deleted_at IS NULL AND description = :description AND id != :excludeId")
    suspend fun liveByDescription(description: String, excludeId: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE deleted_at IS NULL AND substr(description, -length(:suffix)) = :suffix")
    suspend fun liveByDescriptionSuffix(suffix: String): List<TransactionEntity>

    @Query(
        """SELECT COUNT(*) FROM transactions t WHERE t.deleted_at IS NULL AND EXISTS (
           SELECT 1 FROM transaction_entries e WHERE e.transaction_id = t.id AND e.account_id = :accountId)""",
    )
    suspend fun countForAccount(accountId: String): Int

    @Upsert
    suspend fun upsert(entity: TransactionEntity)

    @Upsert
    override suspend fun upsertAll(entities: List<TransactionEntity>)

    @Query("SELECT * FROM transactions WHERE is_dirty = 1")
    override suspend fun dirty(): List<TransactionEntity>

    @Query("UPDATE transactions SET is_dirty = 0 WHERE id = :id AND updated_at = :updatedAt")
    override suspend fun markClean(id: String, updatedAt: Long)

    @Query("UPDATE transactions SET deleted_at = COALESCE(deleted_at, updated_at), is_dirty = 0 WHERE id = :id AND updated_at = :updatedAt")
    override suspend fun discard(id: String, updatedAt: Long)

    @Query("UPDATE transactions SET deleted_at = NULL, updated_at = :now, is_dirty = 1 WHERE deleted_at = :stamp")
    override suspend fun restoreDeletedAt(stamp: Long, now: Long): Int

    @Query("UPDATE transactions SET deleted_at = :now, updated_at = :now, is_dirty = 1 WHERE id = :id AND deleted_at IS NULL")
    suspend fun softDelete(id: String, now: Long)

    @Query("UPDATE transactions SET deleted_at = :now, updated_at = :now, is_dirty = 1 WHERE deleted_at IS NULL")
    suspend fun softDeleteAll(now: Long)
}

@Dao
interface EntryDao {
    @Query("$ENTRY_WITH_ACCOUNT AND t.date BETWEEN :from AND :to ORDER BY e.transaction_id, e.entry_type, a.number")
    suspend fun liveInRange(from: LocalDate, to: LocalDate): List<EntryWithAccount>

    @Query("$ENTRY_WITH_ACCOUNT AND e.transaction_id = :transactionId ORDER BY e.entry_type, a.number")
    suspend fun liveOf(transactionId: String): List<EntryWithAccount>

    /** The lines of [transactionIds], deleted transactions included. */
    @Query("SELECT * FROM transaction_entries WHERE transaction_id IN (:transactionIds)")
    suspend fun allOf(transactionIds: List<String>): List<EntryEntity>

    @Query("$TOTALS AND t.date <= :asOf GROUP BY e.account_id")
    suspend fun totalsAsOf(asOf: LocalDate): List<AccountTotals>

    @Query("$TOTALS AND t.date BETWEEN :from AND :to GROUP BY e.account_id")
    suspend fun totalsBetween(from: LocalDate, to: LocalDate): List<AccountTotals>

    @Query(
        """SELECT e.account_id AS accountId, substr(t.date, 1, 7) AS month,
           SUM(CASE WHEN e.entry_type = 0 THEN e.amount_cents ELSE 0 END) AS debits,
           SUM(CASE WHEN e.entry_type = 1 THEN e.amount_cents ELSE 0 END) AS credits
           FROM transaction_entries e INNER JOIN transactions t ON t.id = e.transaction_id
           WHERE t.deleted_at IS NULL AND t.date BETWEEN :from AND :to
           GROUP BY e.account_id, substr(t.date, 1, 7)""",
    )
    suspend fun monthlyTotals(from: LocalDate, to: LocalDate): List<MonthlyAccountTotals>

    @Query(
        """SELECT e.account_id FROM transaction_entries e
           INNER JOIN transactions t ON t.id = e.transaction_id
           WHERE t.deleted_at IS NULL
           GROUP BY e.account_id ORDER BY COUNT(*) DESC LIMIT :limit""",
    )
    suspend fun mostUsedAccountIds(limit: Int): List<String>

    @Upsert
    suspend fun upsertAll(entities: List<EntryEntity>)

    @Query("DELETE FROM transaction_entries WHERE transaction_id IN (:transactionIds)")
    suspend fun deleteOf(transactionIds: List<String>)

    /** Replaces the lines of [transactionId]; the caller stamps the transaction itself as changed. */
    @Transaction
    suspend fun replaceOf(transactionId: String, lines: List<EntryEntity>) {
        deleteOf(listOf(transactionId))
        upsertAll(lines)
    }
}

@Dao
interface AssetDao : SyncDao<AssetEntity> {
    @Query("SELECT * FROM assets WHERE deleted_at IS NULL ORDER BY name COLLATE NOCASE")
    fun observeLive(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE deleted_at IS NULL ORDER BY name COLLATE NOCASE")
    suspend fun live(): List<AssetEntity>

    @Query("SELECT * FROM assets WHERE id = :id AND deleted_at IS NULL")
    suspend fun liveById(id: String): AssetEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM assets WHERE deleted_at IS NULL AND (debit_account_id = :accountId OR credit_account_id = :accountId))")
    suspend fun usesAccount(accountId: String): Boolean

    @Query("SELECT * FROM assets WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<AssetEntity>

    @Upsert
    suspend fun upsert(entity: AssetEntity)

    @Upsert
    override suspend fun upsertAll(entities: List<AssetEntity>)

    @Query("SELECT * FROM assets WHERE is_dirty = 1")
    override suspend fun dirty(): List<AssetEntity>

    @Query("UPDATE assets SET is_dirty = 0 WHERE id = :id AND updated_at = :updatedAt")
    override suspend fun markClean(id: String, updatedAt: Long)

    @Query("UPDATE assets SET deleted_at = COALESCE(deleted_at, updated_at), is_dirty = 0 WHERE id = :id AND updated_at = :updatedAt")
    override suspend fun discard(id: String, updatedAt: Long)

    @Query("UPDATE assets SET deleted_at = NULL, updated_at = :now, is_dirty = 1 WHERE deleted_at = :stamp")
    override suspend fun restoreDeletedAt(stamp: Long, now: Long): Int

    @Query("UPDATE assets SET deleted_at = :now, updated_at = :now, is_dirty = 1 WHERE deleted_at IS NULL")
    suspend fun softDeleteAll(now: Long)
}

@Dao
interface InvoiceDao : SyncDao<InvoiceEntity> {
    @Query("SELECT * FROM invoices WHERE deleted_at IS NULL ORDER BY due_date, invoice_date")
    fun observeLive(): Flow<List<InvoiceEntity>>

    @Query("SELECT * FROM invoices WHERE id = :id AND deleted_at IS NULL")
    suspend fun liveById(id: String): InvoiceEntity?

    @Query("SELECT * FROM invoices WHERE deleted_at IS NULL ORDER BY due_date, invoice_date")
    suspend fun live(): List<InvoiceEntity>

    @Query("SELECT * FROM invoices WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<InvoiceEntity>

    @Upsert
    suspend fun upsert(entity: InvoiceEntity)

    @Upsert
    override suspend fun upsertAll(entities: List<InvoiceEntity>)

    @Query("SELECT * FROM invoices WHERE is_dirty = 1")
    override suspend fun dirty(): List<InvoiceEntity>

    @Query("UPDATE invoices SET is_dirty = 0 WHERE id = :id AND updated_at = :updatedAt")
    override suspend fun markClean(id: String, updatedAt: Long)

    @Query("UPDATE invoices SET deleted_at = COALESCE(deleted_at, updated_at), is_dirty = 0 WHERE id = :id AND updated_at = :updatedAt")
    override suspend fun discard(id: String, updatedAt: Long)

    @Query("UPDATE invoices SET deleted_at = NULL, updated_at = :now, is_dirty = 1 WHERE deleted_at = :stamp")
    override suspend fun restoreDeletedAt(stamp: Long, now: Long): Int

    @Query("UPDATE invoices SET deleted_at = :now, updated_at = :now, is_dirty = 1 WHERE deleted_at IS NULL")
    suspend fun softDeleteAll(now: Long)
}

@Dao
interface UserSettingDao : SyncDao<UserSettingEntity> {
    @Query("SELECT * FROM user_settings WHERE deleted_at IS NULL")
    fun observeLive(): Flow<List<UserSettingEntity>>

    @Query("SELECT * FROM user_settings WHERE deleted_at IS NULL AND substr(`key`, 1, length(:prefix)) = :prefix")
    suspend fun liveWithPrefix(prefix: String): List<UserSettingEntity>

    @Query(
        """UPDATE user_settings SET deleted_at = :now, updated_at = :now, is_dirty = 1
           WHERE deleted_at IS NULL AND substr(`key`, 1, length(:prefix)) = :prefix""",
    )
    suspend fun softDeleteWithPrefix(prefix: String, now: Long): Int

    @Query("SELECT * FROM user_settings WHERE `key` = :key")
    suspend fun byKey(key: String): UserSettingEntity?

    @Query("SELECT * FROM user_settings WHERE id IN (:ids)")
    override suspend fun byIds(ids: List<String>): List<UserSettingEntity>

    @Query("DELETE FROM user_settings WHERE `key` = :key AND id != :keepId")
    suspend fun deleteOtherWithKey(key: String, keepId: String)

    @Upsert
    suspend fun upsert(entity: UserSettingEntity)

    @Upsert
    override suspend fun upsertAll(entities: List<UserSettingEntity>)

    @Query("SELECT * FROM user_settings WHERE is_dirty = 1")
    override suspend fun dirty(): List<UserSettingEntity>

    @Query("UPDATE user_settings SET is_dirty = 0 WHERE id = :id AND updated_at = :updatedAt")
    override suspend fun markClean(id: String, updatedAt: Long)

    @Query("UPDATE user_settings SET deleted_at = COALESCE(deleted_at, updated_at), is_dirty = 0 WHERE id = :id AND updated_at = :updatedAt")
    override suspend fun discard(id: String, updatedAt: Long)

    @Query("UPDATE user_settings SET deleted_at = NULL, updated_at = :now, is_dirty = 1 WHERE deleted_at = :stamp")
    override suspend fun restoreDeletedAt(stamp: Long, now: Long): Int
}

@Dao
interface SyncStatusDao {
    /** Changes not yet pushed (a transaction with its lines is one change). */
    @Query(
        """SELECT (SELECT COUNT(*) FROM accounts WHERE is_dirty = 1)
                + (SELECT COUNT(*) FROM transactions WHERE is_dirty = 1)
                + (SELECT COUNT(*) FROM assets WHERE is_dirty = 1)
                + (SELECT COUNT(*) FROM invoices WHERE is_dirty = 1)
                + (SELECT COUNT(*) FROM user_settings WHERE is_dirty = 1)""",
    )
    fun pendingChanges(): Flow<Int>

    /** [pendingChanges] without untouched default-plan rows (stamped [seedStamp]), which never override cloud data. */
    @Query(
        """SELECT (SELECT COUNT(*) FROM accounts WHERE is_dirty = 1 AND updated_at > :seedStamp)
                + (SELECT COUNT(*) FROM transactions WHERE is_dirty = 1)
                + (SELECT COUNT(*) FROM assets WHERE is_dirty = 1)
                + (SELECT COUNT(*) FROM invoices WHERE is_dirty = 1)
                + (SELECT COUNT(*) FROM user_settings WHERE is_dirty = 1 AND updated_at > :seedStamp)""",
    )
    suspend fun userChanges(seedStamp: Long): Int

    /** Unsynced deletions of things the user sees (accounts, transactions, assets, invoices). */
    @Query(
        """SELECT (SELECT COUNT(*) FROM accounts WHERE is_dirty = 1 AND deleted_at IS NOT NULL)
                + (SELECT COUNT(*) FROM transactions WHERE is_dirty = 1 AND deleted_at IS NOT NULL)
                + (SELECT COUNT(*) FROM assets WHERE is_dirty = 1 AND deleted_at IS NOT NULL)
                + (SELECT COUNT(*) FROM invoices WHERE is_dirty = 1 AND deleted_at IS NOT NULL)""",
    )
    suspend fun userDeletions(): Int

    /** True when the ledger holds anything besides an untouched default plan. */
    @Query(
        """SELECT EXISTS (SELECT 1 FROM accounts WHERE deleted_at IS NULL AND updated_at > :seedStamp)
               OR EXISTS (SELECT 1 FROM transactions WHERE deleted_at IS NULL)
               OR EXISTS (SELECT 1 FROM assets WHERE deleted_at IS NULL)
               OR EXISTS (SELECT 1 FROM invoices WHERE deleted_at IS NULL)""",
    )
    suspend fun hasUserData(seedStamp: Long): Boolean

    // Queue every live row for upload, e.g. when this device's ledger moves to another account.
    @Query("UPDATE accounts SET is_dirty = 1 WHERE deleted_at IS NULL") suspend fun markAccountsDirty()
    @Query("UPDATE transactions SET is_dirty = 1 WHERE deleted_at IS NULL") suspend fun markTransactionsDirty()
    @Query("UPDATE assets SET is_dirty = 1 WHERE deleted_at IS NULL") suspend fun markAssetsDirty()
    @Query("UPDATE invoices SET is_dirty = 1 WHERE deleted_at IS NULL") suspend fun markInvoicesDirty()
    @Query("UPDATE user_settings SET is_dirty = 1 WHERE deleted_at IS NULL") suspend fun markSettingsDirty()

    // Restamp every live row last edited before `at` as edited at `at`, and queue it for upload: when
    // this device's ledger replaces the cloud's as of `at`, the rows it still holds must survive.
    @Query("UPDATE accounts SET updated_at = :at, is_dirty = 1 WHERE deleted_at IS NULL AND updated_at < :at") suspend fun restampAccounts(at: Long)
    @Query("UPDATE transactions SET updated_at = :at, is_dirty = 1 WHERE deleted_at IS NULL AND updated_at < :at") suspend fun restampTransactions(at: Long)
    @Query("UPDATE assets SET updated_at = :at, is_dirty = 1 WHERE deleted_at IS NULL AND updated_at < :at") suspend fun restampAssets(at: Long)
    @Query("UPDATE invoices SET updated_at = :at, is_dirty = 1 WHERE deleted_at IS NULL AND updated_at < :at") suspend fun restampInvoices(at: Long)
    @Query("UPDATE user_settings SET updated_at = :at, is_dirty = 1 WHERE deleted_at IS NULL AND updated_at < :at") suspend fun restampSettings(at: Long)
}
