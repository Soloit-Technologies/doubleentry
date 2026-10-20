package com.soloit.doubleentry.data

import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.data.local.withTransaction
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.LedgerException
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.Reconciliation
import com.soloit.doubleentry.domain.ReconciliationStatus
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.domain.formatMedium
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.math.absoluteValue

/**
 * Statement reconciliation. The user confirms that an account's balance on a date matched their
 * bank statement; the mark is stored as a synced setting (`Reconciliation.<accountId>`) and checked
 * against the ledger on every change, so later edits to that period show up as drift.
 */
class ReconciliationRepository(
    private val db: AppDatabase,
    private val ledger: LedgerRepository,
    private val settings: SettingsRepository,
) {
    /** Every reconciled account's status, keyed by account id. */
    val statuses: Flow<Map<String, ReconciliationStatus>> =
        combine(settings.observePrefix(PREFIX), ledger.changes) { values, _ -> values }.mapLatest { values ->
            val marks = values.mapNotNull { (accountId, raw) -> decode(accountId, raw) }
            val balancesByDate = marks.map { it.date }.distinct().associateWith { ledger.getBalances(it) }
            marks.associate { it.accountId to ReconciliationStatus(it, balancesByDate.getValue(it.date)[it.accountId] ?: 0L) }
        }

    suspend fun getStatus(accountId: String): ReconciliationStatus? {
        val mark = settings.get(PREFIX + accountId)?.let { decode(accountId, it) } ?: return null
        return ReconciliationStatus(mark, bookBalance(accountId, mark.date))
    }

    /** The account's balance on its normal side as of [date] (inclusive). */
    suspend fun bookBalance(accountId: String, date: LocalDate): Long = ledger.getBalances(date)[accountId] ?: 0L

    /** Marks the account as matching [statementBalance] on [date]. The ledger must agree. */
    suspend fun reconcile(accountId: String, date: LocalDate, statementBalance: Long) {
        ledger.getAccount(accountId) ?: throw LedgerException("Account not found.")
        val book = bookBalance(accountId, date)
        if (book != statementBalance) {
            throw LedgerException("The ledger shows ${Money.format(book)} on ${date.formatMedium()}, ${Money.format((book - statementBalance).absoluteValue)} away from the statement.")
        }
        settings.put(PREFIX + accountId, encode(Reconciliation(accountId, date, statementBalance)))
    }

    /**
     * Posts a transaction for the difference between the ledger and the statement against
     * [counterAccountId] (e.g. bank fees or interest), then marks the account as reconciled.
     */
    suspend fun adjustAndReconcile(accountId: String, date: LocalDate, statementBalance: Long, counterAccountId: String, title: String) {
        val account = ledger.getAccount(accountId) ?: throw LedgerException("Account not found.")
        if (counterAccountId == accountId) throw LedgerException("Choose a different account for the adjustment.")
        db.withTransaction {
            val difference = statementBalance - bookBalance(accountId, date)
            if (difference != 0L) ledger.insertTransaction(adjustment(account, date, difference, counterAccountId, title))
            settings.put(PREFIX + accountId, encode(Reconciliation(accountId, date, statementBalance)))
        }
    }

    suspend fun clear(accountId: String) = settings.put(PREFIX + accountId, null)

    @Serializable
    internal data class ReconciliationJson(
        @SerialName("Date") val date: String,
        @SerialName("StatementBalance") val statementBalance: Long,
    )

    companion object {
        const val PREFIX = SettingsRepository.Keys.RECONCILIATION_PREFIX

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * A transaction that moves [account]'s balance by [difference] (on its normal side),
         * balanced against [counterAccountId].
         */
        internal fun adjustment(account: Account, date: LocalDate, difference: Long, counterAccountId: String, title: String): TransactionDraft {
            val increase = difference > 0
            // Increasing a debit-normal account is a debit; increasing a credit-normal one is a credit.
            val accountSide = if (account.type.isDebitNormal == increase) EntryType.Debit else EntryType.Credit
            val counterSide = if (accountSide == EntryType.Debit) EntryType.Credit else EntryType.Debit
            val amount = difference.absoluteValue
            return TransactionDraft(
                date = date,
                title = title.ifBlank { "Reconciliation adjustment" },
                description = "Adjusts ${account.name} to the statement balance on ${date.formatMedium()}.",
                lines = listOf(DraftLine(account.id, amount, accountSide), DraftLine(counterAccountId, amount, counterSide)),
            )
        }

        internal fun encode(mark: Reconciliation): String =
            json.encodeToString(ReconciliationJson.serializer(), ReconciliationJson(mark.date.toString(), mark.statementBalance))

        internal fun decode(accountId: String, raw: String): Reconciliation? = runCatching {
            val r = json.decodeFromString(ReconciliationJson.serializer(), raw)
            Reconciliation(accountId, LocalDate.parse(r.date), r.statementBalance)
        }.getOrNull()
    }
}
