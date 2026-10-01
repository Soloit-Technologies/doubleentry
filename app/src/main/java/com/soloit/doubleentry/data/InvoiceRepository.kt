package com.soloit.doubleentry.data

import androidx.room.withTransaction
import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.data.local.InvoiceEntity
import com.soloit.doubleentry.data.local.nowMicros
import com.soloit.doubleentry.data.local.toDomain
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Invoice
import com.soloit.doubleentry.domain.LedgerException
import com.soloit.doubleentry.domain.OutstandingDetector
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.domain.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Invoices layer the amount owed, dates and paid state on top of a normal balanced "booking"
 * transaction. Paying an invoice posts a settlement transaction for the full amount due.
 */
class InvoiceRepository(
    private val db: AppDatabase,
    private val ledger: LedgerRepository,
    private val sync: SyncTrigger,
) {
    private val invoiceDao = db.invoices()

    val invoices: Flow<List<Invoice>> = invoiceDao.observeLive().map { list -> list.map { it.toDomain() } }

    suspend fun getInvoice(id: String): Invoice? = invoiceDao.liveById(id)?.toDomain()

    suspend fun createInvoice(amountDue: Long, invoiceDate: LocalDate, dueDate: LocalDate, booking: TransactionDraft): Invoice {
        validate(amountDue, invoiceDate, dueDate, booking)
        val entity = db.withTransaction {
            val outstanding = detect(booking, amountDue)
            val bookingId = ledger.insertTransaction(booking)
            InvoiceEntity(
                id = newId(),
                amountDueCents = amountDue,
                invoiceDate = invoiceDate,
                dueDate = dueDate,
                bookingTransactionId = bookingId,
                outstandingAccountId = outstanding.accountId,
                outstandingIsCredit = outstanding.isCredit,
                isPaid = false,
                paidDate = null,
                paymentTransactionId = null,
                paidFromAccountId = null,
                updatedAt = nowMicros(),
            ).also { invoiceDao.upsert(it) }
        }
        sync.requestSync()
        return entity.toDomain()
    }

    suspend fun updateInvoice(id: String, amountDue: Long, invoiceDate: LocalDate, dueDate: LocalDate, booking: TransactionDraft) {
        validate(amountDue, invoiceDate, dueDate, booking)
        db.withTransaction {
            val invoice = invoiceDao.liveById(id) ?: throw LedgerException("Invoice not found.")
            if (invoice.isPaid) throw LedgerException("A paid invoice cannot be edited.")
            val outstanding = detect(booking, amountDue)
            ledger.replaceTransaction(invoice.bookingTransactionId, booking)
            invoiceDao.upsert(
                invoice.copy(
                    amountDueCents = amountDue,
                    invoiceDate = invoiceDate,
                    dueDate = dueDate,
                    outstandingAccountId = outstanding.accountId,
                    outstandingIsCredit = outstanding.isCredit,
                    updatedAt = nowMicros(),
                    isDirty = true,
                ),
            )
        }
        sync.requestSync()
    }

    /** Deletes the invoice and reverses its booking (and payment) transactions. */
    suspend fun deleteInvoice(id: String) {
        db.withTransaction {
            val invoice = invoiceDao.liveById(id) ?: throw LedgerException("Invoice not found.")
            invoice.paymentTransactionId?.let { ledger.softDeleteTransaction(it) }
            ledger.softDeleteTransaction(invoice.bookingTransactionId)
            val now = nowMicros()
            invoiceDao.upsert(invoice.copy(deletedAt = now, updatedAt = now, isDirty = true))
        }
        sync.requestSync()
    }

    suspend fun markPaid(id: String, paymentAccountId: String, paidDate: LocalDate) {
        db.withTransaction {
            val invoice = invoiceDao.liveById(id) ?: throw LedgerException("Invoice not found.")
            if (invoice.isPaid) throw LedgerException("This invoice is already paid.")
            if (paymentAccountId == invoice.outstandingAccountId) {
                throw LedgerException("The payment account must differ from the outstanding account.")
            }
            val paymentAccount = ledger.getAccount(paymentAccountId) ?: throw LedgerException("The payment account no longer exists.")

            // Payable: debit outstanding / credit cash. Receivable: debit cash / credit outstanding.
            val amount = invoice.amountDueCents
            val lines = if (invoice.outstandingIsCredit) {
                listOf(
                    DraftLine(invoice.outstandingAccountId, amount, EntryType.Debit),
                    DraftLine(paymentAccountId, amount, EntryType.Credit),
                )
            } else {
                listOf(
                    DraftLine(paymentAccountId, amount, EntryType.Debit),
                    DraftLine(invoice.outstandingAccountId, amount, EntryType.Credit),
                )
            }
            val paymentId = ledger.insertTransaction(
                TransactionDraft(
                    date = paidDate,
                    title = "Invoice payment",
                    description = "Payment from ${paymentAccount.name} for invoice dated ${invoice.invoiceDate}.",
                    lines = lines,
                ),
            )
            invoiceDao.upsert(
                invoice.copy(
                    isPaid = true,
                    paidDate = paidDate,
                    paymentTransactionId = paymentId,
                    paidFromAccountId = paymentAccountId,
                    updatedAt = nowMicros(),
                    isDirty = true,
                ),
            )
        }
        sync.requestSync()
    }

    /** Changes the payment date of a paid invoice, keeping the settlement transaction in sync. */
    suspend fun updatePaidDate(id: String, paidDate: LocalDate) {
        db.withTransaction {
            val invoice = invoiceDao.liveById(id) ?: throw LedgerException("Invoice not found.")
            if (!invoice.isPaid) throw LedgerException("This invoice is not paid.")
            val now = nowMicros()
            invoice.paymentTransactionId
                ?.let { db.transactions().byId(it) }
                ?.takeIf { it.deletedAt == null }
                ?.let { db.transactions().upsert(it.copy(date = paidDate, updatedAt = now, isDirty = true)) }
            invoiceDao.upsert(invoice.copy(paidDate = paidDate, updatedAt = now, isDirty = true))
        }
        sync.requestSync()
    }

    private suspend fun detect(booking: TransactionDraft, amountDue: Long): OutstandingDetector.Outstanding {
        val types = booking.lines.map { it.accountId }.distinct()
            .mapNotNull { ledger.getAccount(it) }
            .associate { it.id to it.type }
        return OutstandingDetector.detect(booking.lines, types.withDefault { AccountType.Asset }, amountDue)
    }

    private fun validate(amountDue: Long, invoiceDate: LocalDate, dueDate: LocalDate, booking: TransactionDraft) {
        if (amountDue <= 0) throw LedgerException("Amount due must be a positive amount.")
        if (dueDate < invoiceDate) throw LedgerException("The due date cannot be before the invoice date.")
        if (booking.lines.isEmpty()) throw LedgerException("The invoice must have at least one bookkeeping row.")
    }
}
