package com.soloit.doubleentry.data

import com.soloit.doubleentry.data.local.AccountEntity
import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.data.local.EntryEntity
import com.soloit.doubleentry.data.local.TransactionEntity
import com.soloit.doubleentry.data.local.UserSettingEntity
import com.soloit.doubleentry.data.local.nowMicros
import com.soloit.doubleentry.data.local.toDomain
import com.soloit.doubleentry.data.local.withTransaction
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.DefaultAccountPlan
import com.soloit.doubleentry.domain.Depreciation
import com.soloit.doubleentry.domain.DeterministicIds
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.LedgerException
import com.soloit.doubleentry.domain.Transaction
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.domain.TransactionValidator
import com.soloit.doubleentry.domain.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth

/** Notified after every local mutation so the change can be pushed to the cloud. */
fun interface SyncTrigger {
    fun requestSync()
}

/**
 * Accounts, transactions and balances. All deletes are soft (tombstones) so they replicate; every
 * mutation stamps `updated_at`/`is_dirty` and asks the sync layer to push.
 */
class LedgerRepository(
    private val db: AppDatabase,
    private val sync: SyncTrigger,
) {
    private val accountDao = db.accounts()
    private val transactionDao = db.transactions()
    private val entryDao = db.entries()
    private val periods = CostPeriods(db, SettingsRepository(db, sync))

    /** Emits whenever any ledger table changes, including changes pulled from the cloud. */
    val changes: Flow<Unit> = db.ledgerChanges()

    val accounts: Flow<List<Account>> = accountDao.observeLive().map { list -> list.map { it.toDomain() } }

    suspend fun getAccounts(): List<Account> = accountDao.live().map { it.toDomain() }

    suspend fun getAccount(id: String): Account? = accountDao.byId(id)?.takeIf { it.deletedAt == null }?.toDomain()

    suspend fun createAccount(number: Int, name: String, type: AccountType): Account {
        validateAccount(number, name)
        val account = db.withTransaction {
            if (accountDao.numberInUse(number)) throw LedgerException("An account with number $number already exists.")
            AccountEntity(newId(), number, name.trim(), type.ordinal, nowMicros()).also { accountDao.upsert(it) }
        }
        sync.requestSync()
        return account.toDomain()
    }

    /**
     * Seeds the default chart of accounts, its report groups and its cost period accounts. Ids are deterministic, so accounts
     * that already exist (for example pulled from another device) are left untouched and numbers in
     * use are skipped. Report settings are only written where the user has none, live or deleted.
     */
    suspend fun seedDefaultPlan(): Int {
        var created = 0
        var seededSettings = false
        db.withTransaction {
            for (entry in DefaultAccountPlan.accounts) {
                if (accountDao.byId(entry.id) != null) continue
                if (accountDao.numberInUse(entry.number)) continue
                // Oldest possible timestamp: if the cloud already holds this account (renamed or
                // deleted elsewhere), the cloud version wins instead of the default name.
                accountDao.upsert(AccountEntity(entry.id, entry.number, entry.name, entry.type.ordinal, updatedAt = SEED_TIMESTAMP))
                created++
            }
            // A cost period account is only chosen when the number is the plan's own account, not
            // an unrelated account the user already had under that number.
            val costPeriodAccounts = SettingsRepository.costPeriodAccountEntries(DefaultAccountPlan.costPeriodAccounts)
                .mapNotNull { (key, value) ->
                    val number = value ?: return@mapNotNull null
                    val entry = DefaultAccountPlan.accounts.firstOrNull { it.number.toString() == number } ?: return@mapNotNull null
                    val account = accountDao.byId(entry.id)?.takeIf { it.deletedAt == null && it.number == entry.number }
                    if (account != null) key to number else null
                }
            val defaults = SettingsRepository.balanceSheetEntries(DefaultAccountPlan.balanceSheetSettings) +
                SettingsRepository.incomeStatementEntries(DefaultAccountPlan.incomeStatementSettings) +
                costPeriodAccounts
            for ((key, value) in defaults) {
                if (db.settings().byKey(key) != null) continue
                // Same oldest timestamp as the accounts: settings already in the cloud win.
                db.settings().upsert(UserSettingEntity(DeterministicIds.settingId(key), key, value, updatedAt = SEED_TIMESTAMP))
                seededSettings = true
            }
        }
        if (created > 0 || seededSettings) sync.requestSync()
        return created
    }

    suspend fun updateAccount(id: String, number: Int, name: String, type: AccountType): Account {
        validateAccount(number, name)
        val updated = db.withTransaction {
            val existing = accountDao.byId(id)?.takeIf { it.deletedAt == null } ?: throw LedgerException("Account not found.")
            if (number != existing.number && accountDao.numberInUse(number, excludeId = id)) {
                throw LedgerException("An account with number $number already exists.")
            }
            val changed = existing.copy(number = number, name = name.trim(), type = type.ordinal)
            if (changed == existing) return@withTransaction existing
            changed.copy(updatedAt = nowMicros(), isDirty = true).also { accountDao.upsert(it) }
        }
        sync.requestSync()
        return updated.toDomain()
    }

    suspend fun accountHasTransactions(accountId: String): Boolean = transactionDao.countForAccount(accountId) > 0

    /** Soft-deletes an account; returns the deletion stamp for [restoreDeleted]. */
    suspend fun deleteAccount(id: String): Long {
        val now = nowMicros()
        db.withTransaction {
            val existing = accountDao.byId(id)?.takeIf { it.deletedAt == null } ?: throw LedgerException("Account not found.")
            if (db.assets().usesAccount(id)) {
                throw LedgerException("This account is used by an asset's depreciation and cannot be deleted. Delete the asset first.")
            }
            if (transactionDao.countForAccount(id) > 0) {
                throw LedgerException("This account has transactions. Delete them before deleting the account.")
            }
            accountDao.upsert(existing.copy(deletedAt = now, updatedAt = now, isDirty = true))
            db.settings().softDeleteWithPrefix(SettingsRepository.Keys.RECONCILIATION_PREFIX + id, now)
        }
        sync.requestSync()
        return now
    }

    /** Balance of every live account as of [asOf] (inclusive), on each account's normal side. */
    suspend fun getBalances(asOf: LocalDate): Map<String, Long> =
        signed(entryDao.totalsAsOf(asOf).associate { it.accountId to (it.debits to it.credits) })

    /** Change of every live account's balance between [from] and [to] (inclusive). */
    suspend fun getMovements(from: LocalDate, to: LocalDate): Map<String, Long> =
        signed(entryDao.totalsBetween(from, to).associate { it.accountId to (it.debits to it.credits) })

    /** Date of the earliest revenue or expense line, i.e. where the income statement's history starts. */
    suspend fun firstIncomeStatementDate(): LocalDate? {
        val ids = accountDao.live().filter {
            AccountType.fromOrdinal(it.type).let { t -> t == AccountType.Revenue || t == AccountType.Expense }
        }.map { it.id }
        return if (ids.isEmpty()) null else entryDao.firstDateOn(ids)
    }

    /** Per account, the balance change of each month between [from] and [to]. */
    suspend fun getMonthlyMovements(from: YearMonth, to: YearMonth): Map<String, Map<YearMonth, Long>> {
        val types = accountDao.live().associate { it.id to AccountType.fromOrdinal(it.type) }
        return entryDao.monthlyTotals(from.atDay(1), to.atEndOfMonth())
            .filter { it.accountId in types }
            .groupBy { it.accountId }
            .mapValues { (accountId, rows) ->
                val debitNormal = types.getValue(accountId).isDebitNormal
                rows.associate { YearMonth.parse(it.month) to if (debitNormal) it.debits - it.credits else it.credits - it.debits }
            }
    }

    private suspend fun signed(totals: Map<String, Pair<Long, Long>>): Map<String, Long> =
        accountDao.live().associate { a ->
            val (debits, credits) = totals[a.id] ?: (0L to 0L)
            a.id to a.toDomain().signedBalance(debits, credits)
        }

    suspend fun mostUsedAccountIds(limit: Int = 8): List<String> = entryDao.mostUsedAccountIds(limit)

    /**
     * The most recent transaction for each distinct title containing [text], newest first. Used to
     * suggest earlier transactions (and their lines) while typing a title.
     */
    suspend fun suggestByTitle(text: String, limit: Int = 5): List<Transaction> {
        val needle = text.trim()
        if (needle.isEmpty()) return emptyList()
        val escaped = needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return transactionDao.liveWithTitleLike(escaped, limit = 100)
            .distinctBy { it.title.trim().lowercase() }
            .take(limit)
            .map { it.toDomain(entryDao.liveOf(it.id).map { e -> e.toDomain() }) }
    }

    suspend fun getTransaction(id: String): Transaction? {
        val tx = transactionDao.byId(id)?.takeIf { it.deletedAt == null } ?: return null
        return tx.toDomain(entryDao.liveOf(id).map { it.toDomain() })
    }

    /** Transactions ordered by date, optionally restricted to those touching [accountId]. */
    suspend fun getTransactions(
        from: LocalDate = LocalDate.of(1, 1, 1),
        to: LocalDate = LocalDate.of(9999, 12, 31),
        accountId: String? = null,
    ): List<Transaction> {
        val transactions = transactionDao.liveInRange(from, to)
        val entries = entryDao.liveInRange(from, to).groupBy({ it.transactionId }, { it.toDomain() })
        return transactions
            .map { it.toDomain(entries[it.id].orEmpty()) }
            .filter { accountId == null || it.entries.any { e -> e.account.id == accountId } }
    }

    /**
     * The lines of [tx] as they were entered: lines spread over a period come back as one line with
     * its [com.soloit.doubleentry.domain.CostPeriod] instead of the parts it was posted as.
     */
    suspend fun editableLines(tx: Transaction): List<DraftLine> {
        val entries = tx.entries.map { DraftLine(it.account.id, it.amount, it.type) }
        return periods.editableLines(tx.id, entries) ?: entries
    }

    /** How [tx]'s lines are spread over periods, or null when none are. */
    suspend fun getCostPeriods(tx: Transaction): CostPeriodSummary? = periods.summary(tx.id, tx.date)

    /** The transaction a period posting belongs to; null for any other transaction. */
    suspend fun periodSourceOf(transactionId: String): String? = periods.sourceOf(transactionId)

    /** Validates and stores a new transaction; returns its id. */
    suspend fun post(draft: TransactionDraft): String {
        val id = db.withTransaction { insertTransaction(draft) }
        sync.requestSync()
        return id
    }

    /** For use inside an outer [AppDatabase.withTransaction] by other repositories. */
    internal suspend fun insertTransaction(draft: TransactionDraft): String {
        TransactionValidator.validate(draft)
        requireLiveAccounts(draft)
        val now = nowMicros()
        val id = draft.id ?: newId()
        if (transactionDao.byId(id) != null) throw LedgerException("A transaction with this id already exists.")
        val plan = periods.plan(id, draft)
        transactionDao.upsert(TransactionEntity(id, draft.date, draft.title.trim(), draft.description.trim(), now))
        entryDao.upsertAll(plan.sourceLines.map { EntryEntity(it.id ?: newId(), id, it.accountId, it.amount, it.type.ordinal) })
        periods.apply(id, plan, now)
        return id
    }

    /** Replaces the header and lines of an existing transaction; they sync as one. */
    suspend fun updateTransaction(id: String, draft: TransactionDraft) {
        if (periodSourceOf(id) != null) throw LedgerException(PERIOD_POSTING_MESSAGE)
        db.withTransaction { replaceTransaction(id, draft) }
        sync.requestSync()
    }

    internal suspend fun replaceTransaction(id: String, draft: TransactionDraft) {
        TransactionValidator.validate(draft)
        requireLiveAccounts(draft)
        val existing = transactionDao.byId(id)?.takeIf { it.deletedAt == null } ?: throw LedgerException("Transaction not found.")
        val now = nowMicros()
        val plan = periods.plan(id, draft)
        transactionDao.upsert(
            existing.copy(date = draft.date, title = draft.title.trim(), description = draft.description.trim(), updatedAt = now, isDirty = true),
        )
        entryDao.replaceOf(id, plan.sourceLines.map { EntryEntity(newId(), id, it.accountId, it.amount, it.type.ordinal) })
        periods.apply(id, plan, now)
    }

    /** True when [transactionId] is an automatic depreciation posting of a live asset. */
    suspend fun isDepreciationTransaction(transactionId: String): Boolean =
        db.assets().live().any { transactionId in Depreciation.writeoffIds(it.toDomain()) }

    /**
     * Deletes a transaction. Automatic depreciation postings are owned by their asset and can only
     * be removed by deleting the asset.
     */
    suspend fun deleteTransaction(id: String): Long {
        if (isDepreciationTransaction(id)) {
            throw LedgerException(
                "This is an automatic depreciation posting and cannot be deleted directly. Delete the asset to remove its depreciation.",
            )
        }
        if (periodSourceOf(id) != null) throw LedgerException(PERIOD_POSTING_MESSAGE)
        val now = nowMicros()
        db.withTransaction { softDeleteTransaction(id, now) }
        sync.requestSync()
        return now
    }

    /**
     * Soft-deletes a transaction, and the postings of its period lines, with [now] as the deletion
     * stamp. It keeps its lines, so undo brings them back.
     */
    internal suspend fun softDeleteTransaction(id: String, now: Long = nowMicros()) {
        transactionDao.softDelete(id, now)
        periods.remove(id, now)
    }

    /**
     * Undoes a delete: every row in every table soft-deleted at exactly [stamp] is revived (a delete
     * operation stamps all rows it removes with the same value). Returns the number of rows restored.
     */
    suspend fun restoreDeleted(stamp: Long): Int {
        val restored = db.withTransaction {
            val now = nowMicros()
            accountDao.restoreDeletedAt(stamp, now) +
                transactionDao.restoreDeletedAt(stamp, now) +
                db.assets().restoreDeletedAt(stamp, now) +
                db.invoices().restoreDeletedAt(stamp, now) +
                db.settings().restoreDeletedAt(stamp, now)
        }
        if (restored > 0) sync.requestSync()
        return restored
    }

    /** Soft-deletes everything except report and invoice settings; the deletions replicate to the cloud. */
    suspend fun deleteAllData() {
        db.withTransaction {
            val now = nowMicros()
            db.invoices().softDeleteAll(now)
            db.assets().softDeleteAll(now)
            transactionDao.softDeleteAll(now)
            accountDao.softDeleteAll(now)
            SettingsRepository.Keys.DATA_PREFIXES.forEach { db.settings().softDeleteWithPrefix(it, now) }
        }
        sync.requestSync()
    }

    private suspend fun requireLiveAccounts(draft: TransactionDraft) {
        val ids = draft.lines.map { it.accountId }.distinct()
        val live = accountDao.byIds(ids).filter { it.deletedAt == null }.map { it.id }.toSet()
        if (ids.any { it !in live }) throw LedgerException("One of the selected accounts no longer exists.")
    }

    private fun validateAccount(number: Int, name: String) {
        if (number <= 0) throw LedgerException("Account number must be a positive integer.")
        if (name.isBlank()) throw LedgerException("Account name is required.")
    }

    companion object {
        /** `updated_at` of seeded default-plan rows (1 µs after the epoch): any server row is newer. */
        const val SEED_TIMESTAMP = 1L

        private const val PERIOD_POSTING_MESSAGE =
            "This posting moves a share of a transaction spread over a period. Edit or delete that transaction instead."
    }
}
