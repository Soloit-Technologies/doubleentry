package com.soloit.doubleentry.data

import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.data.local.InvoiceEntity
import com.soloit.doubleentry.data.local.nowMicros
import com.soloit.doubleentry.data.local.toDomain
import com.soloit.doubleentry.data.local.withTransaction
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Invoice
import com.soloit.doubleentry.domain.InvoicePayment
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.LedgerException
import com.soloit.doubleentry.domain.OutstandingDetector
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.domain.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Invoices layer the amount owed, dates and paid state on top of a normal balanced "booking"
 * transaction. Paying an invoice posts a settlement transaction, either for everything that is
 * left or for part of it (an instalment).
 *
 * Instalments are listed in a synced setting per invoice (`Invoice.Payments.<id>`, a JSON array
 * of transaction ids) rather than on the invoice row, which keeps the row identical to what the
 * .NET app reads. Their amounts are always read back from the live ledger, so deleting an
 * instalment's transaction simply makes that amount outstanding again.
 */
class InvoiceRepository(
    private val db: AppDatabase,
    private val ledger: LedgerRepository,
    private val settings: SettingsRepository,
    private val sync: SyncTrigger,
) {
    private val invoiceDao = db.invoices()

    val invoices: Flow<List<Invoice>> = db.ledgerChanges().map { liveInvoices() }

    suspend fun liveInvoices(): List<Invoice> {
        val payments = settings.livePrefix(SettingsRepository.Keys.INVOICE_PAYMENTS_PREFIX)
        return invoiceDao.live().map { resolve(it, payments[it.id]) }
    }

    suspend fun getInvoice(id: String): Invoice? = invoiceDao.liveById(id)?.let { resolve(it) }

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
            val paid = resolve(invoice).partiallyPaid
            if (amountDue < paid) {
                throw LedgerException("The amount due cannot be less than what has already been paid (${Money.format(paid)}).")
            }
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

    /**
     * Deletes the invoice and reverses its booking and payment transactions (instalments included).
     * Returns the deletion stamp for [LedgerRepository.restoreDeleted].
     */
    suspend fun deleteInvoice(id: String): Long {
        val now = nowMicros()
        db.withTransaction {
            val invoice = invoiceDao.liveById(id) ?: throw LedgerException("Invoice not found.")
            val key = SettingsRepository.Keys.INVOICE_PAYMENTS_PREFIX + id
            decodeIds(settings.get(key)).forEach { ledger.softDeleteTransaction(it, now) }
            settings.softDelete(key, now)
            invoice.paymentTransactionId?.let { ledger.softDeleteTransaction(it, now) }
            ledger.softDeleteTransaction(invoice.bookingTransactionId, now)
            invoiceDao.upsert(invoice.copy(deletedAt = now, updatedAt = now, isDirty = true))
        }
        sync.requestSync()
        return now
    }

    /** Pays everything that is still owed and marks the invoice as paid. */
    suspend fun markPaid(id: String, paymentAccountId: String, paidDate: LocalDate) = recordPayment(id, paymentAccountId, paidDate, amount = null)

    /**
     * Posts a payment of [amount] (everything still owed when null). A payment that settles the rest
     * marks the invoice as paid; a smaller one is recorded as an instalment. Returns the id of the
     * payment transaction.
     */
    suspend fun recordPayment(id: String, paymentAccountId: String, paidDate: LocalDate, amount: Long?): String {
        val paymentId = db.withTransaction {
            val invoice = invoiceDao.liveById(id) ?: throw LedgerException("Invoice not found.")
            if (invoice.isPaid) throw LedgerException("This invoice is already paid.")
            if (paymentAccountId == invoice.outstandingAccountId) {
                throw LedgerException("The payment account must differ from the outstanding account.")
            }
            val paymentAccount = ledger.getAccount(paymentAccountId) ?: throw LedgerException("The payment account no longer exists.")
            val resolved = resolve(invoice)
            val remaining = resolved.remaining
            val amount = amount ?: remaining
            if (amount <= 0) throw LedgerException("The payment must be a positive amount.")
            if (amount > remaining) throw LedgerException("The payment is more than the ${Money.format(remaining)} still owed.")
            val settles = amount == remaining

            // Payable: debit outstanding / credit cash. Receivable: debit cash / credit outstanding.
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
            val bookingTitle = ledger.getTransaction(invoice.bookingTransactionId)?.title
            val kind = when {
                !settles -> "Part payment"
                resolved.partialPayments.isNotEmpty() -> "Final payment"
                else -> "Payment"
            }
            val paymentId = ledger.insertTransaction(
                TransactionDraft(
                    date = paidDate,
                    title = if (bookingTitle.isNullOrBlank()) "Invoice ${kind.lowercase()}" else "$kind: $bookingTitle",
                    description = "Paid from ${paymentAccount.name} for invoice dated ${invoice.invoiceDate}.",
                    lines = lines,
                ),
            )
            if (!settles) {
                val key = SettingsRepository.Keys.INVOICE_PAYMENTS_PREFIX + id
                settings.put(key, encodeIds(decodeIds(settings.get(key)) + paymentId))
                return@withTransaction paymentId
            }
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
            paymentId
        }
        sync.requestSync()
        return paymentId
    }

    /**
     * Takes back a payment made with [recordPayment] (to undo it): deletes its transaction with
     * [stamp] and, if it settled the invoice, marks the invoice unpaid again. For use inside an outer
     * [AppDatabase.withTransaction].
     */
    internal suspend fun reversePayment(id: String, paymentTransactionId: String, stamp: Long) {
        ledger.softDeleteTransaction(paymentTransactionId, stamp)
        val invoice = invoiceDao.liveById(id) ?: return
        if (invoice.paymentTransactionId == paymentTransactionId) {
            invoiceDao.upsert(
                invoice.copy(isPaid = false, paidDate = null, paymentTransactionId = null, paidFromAccountId = null, updatedAt = stamp, isDirty = true),
            )
        } else {
            val key = SettingsRepository.Keys.INVOICE_PAYMENTS_PREFIX + id
            val ids = decodeIds(settings.get(key))
            if (paymentTransactionId in ids) settings.put(key, (ids - paymentTransactionId).takeIf { it.isNotEmpty() }?.let(::encodeIds))
        }
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

    private suspend fun resolve(entity: InvoiceEntity): Invoice =
        resolve(entity, settings.get(SettingsRepository.Keys.INVOICE_PAYMENTS_PREFIX + entity.id))

    /** Adds the live instalments listed in [rawPayments] (the invoice's payments setting). */
    private suspend fun resolve(entity: InvoiceEntity, rawPayments: String?): Invoice {
        val invoice = entity.toDomain()
        val ids = decodeIds(rawPayments)
        if (ids.isEmpty()) return invoice
        // The settling side of the outstanding account: debit for a payable, credit for a receivable.
        val settlingSide = if (entity.outstandingIsCredit) EntryType.Debit else EntryType.Credit
        val payments = ids.mapNotNull { txId ->
            val tx = ledger.getTransaction(txId) ?: return@mapNotNull null
            val amount = tx.entries.filter { it.account.id == entity.outstandingAccountId && it.type == settlingSide }.sumOf { it.amount }
            val from = tx.entries.firstOrNull { it.account.id != entity.outstandingAccountId }?.account?.id
            InvoicePayment(tx.id, tx.date, amount, from)
        }.sortedBy { it.date }
        return invoice.copy(partialPayments = payments)
    }

    private suspend fun detect(booking: TransactionDraft, amountDue: Long): OutstandingDetector.Outstanding {
        val types = booking.lines.map { it.accountId }.distinct()
            .mapNotNull { ledger.getAccount(it) }
            .associate { it.id to it.type }
        return OutstandingDetector.detect(booking.lines, types.withDefault { AccountType.Asset }, amountDue)
    }

    private fun decodeIds(raw: String?): List<String> =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(idList, it) }.getOrNull() }.orEmpty()

    private fun encodeIds(ids: List<String>): String = json.encodeToString(idList, ids.distinct())

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val idList = ListSerializer(String.serializer())
    }

    private fun validate(amountDue: Long, invoiceDate: LocalDate, dueDate: LocalDate, booking: TransactionDraft) {
        if (amountDue <= 0) throw LedgerException("Amount due must be a positive amount.")
        if (dueDate < invoiceDate) throw LedgerException("The due date cannot be before the invoice date.")
        if (booking.lines.isEmpty()) throw LedgerException("The invoice must have at least one bookkeeping row.")
    }
}
