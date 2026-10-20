package com.soloit.doubleentry.data

import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.data.local.nowMicros
import com.soloit.doubleentry.data.local.withTransaction
import com.soloit.doubleentry.domain.TransactionDraft
import java.time.LocalDate

/** A statement row booked as a payment of an invoice rather than as a plain transaction. */
data class StatementInvoicePayment(val invoiceId: String, val date: LocalDate, val amount: Long)

/** What an import created, so it can be undone. */
data class StatementImportResult(
    val transactionIds: List<String>,
    /** Invoice id to the id of the payment transaction posted for it. */
    val payments: List<Pair<String, String>>,
) {
    val count: Int get() = transactionIds.size + payments.size
}

/** Books the rows of a bank statement: all of them or, if one fails, none. */
class StatementImporter(
    private val db: AppDatabase,
    private val ledger: LedgerRepository,
    private val invoices: InvoiceRepository,
    private val sync: SyncTrigger,
) {
    /** Posts [transactions] and pays the invoices in [payments] from [accountId]. */
    suspend fun import(accountId: String, transactions: List<TransactionDraft>, payments: List<StatementInvoicePayment>): StatementImportResult {
        val result = db.withTransaction {
            val ids = transactions.map { ledger.insertTransaction(it) }
            // Oldest first, so several instalments of one invoice are recorded in order.
            val paid = payments.sortedBy { it.date }.map { it.invoiceId to invoices.recordPayment(it.invoiceId, accountId, it.date, it.amount) }
            StatementImportResult(ids, paid)
        }
        sync.requestSync()
        return result
    }

    /** Deletes what [result] posted and reopens the invoices it paid. */
    suspend fun undo(result: StatementImportResult) {
        val stamp = nowMicros()
        db.withTransaction {
            result.transactionIds.forEach { ledger.softDeleteTransaction(it, stamp) }
            result.payments.forEach { (invoiceId, transactionId) -> invoices.reversePayment(invoiceId, transactionId, stamp) }
        }
        sync.requestSync()
    }
}
