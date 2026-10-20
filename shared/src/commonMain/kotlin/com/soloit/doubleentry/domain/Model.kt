package com.soloit.doubleentry.domain

import java.time.LocalDate

/**
 * Account categories. The ordinal is persisted (locally and in Supabase as `smallint`)
 * and must stay in sync with the original .NET enum: Asset=0 … Expense=4.
 */
enum class AccountType {
    Asset,
    Liability,
    Equity,
    Revenue,
    Expense;

    /** Assets and expenses grow on the debit side; everything else on the credit side. */
    val isDebitNormal: Boolean get() = this == Asset || this == Expense

    val pluralLabel: String
        get() = when (this) {
            Asset -> "Assets"
            Liability -> "Liabilities"
            Equity -> "Equity"
            Revenue -> "Revenue"
            Expense -> "Expenses"
        }

    companion object {
        fun fromOrdinal(value: Int): AccountType = entries.getOrElse(value) { Asset }
    }
}

/** Entry side. Ordinal persisted: Debit=0, Credit=1. */
enum class EntryType {
    Debit,
    Credit;

    companion object {
        fun fromOrdinal(value: Int): EntryType = entries.getOrElse(value) { Debit }
    }
}

data class Account(
    val id: String,
    val number: Int,
    val name: String,
    val type: AccountType,
) {
    val display: String get() = "$number $name"

    /** Converts a raw debit/credit total into a balance on this account's normal side. */
    fun signedBalance(debits: Long, credits: Long): Long =
        if (type.isDebitNormal) debits - credits else credits - debits
}

/** A single debit or credit line. [amount] is in minor units (cents). */
data class Entry(
    val id: String,
    val account: Account,
    val amount: Long,
    val type: EntryType,
)

data class Transaction(
    val id: String,
    val date: LocalDate,
    val title: String,
    val description: String,
    val entries: List<Entry>,
) {
    val totalDebits: Long get() = entries.filter { it.type == EntryType.Debit }.sumOf { it.amount }
    val totalCredits: Long get() = entries.filter { it.type == EntryType.Credit }.sumOf { it.amount }

    /** Net effect on [accountId], expressed on that account's normal side. */
    fun signedAmountFor(account: Account): Long = entries
        .filter { it.account.id == account.id }
        .sumOf { e ->
            val debit = e.type == EntryType.Debit
            if (account.type.isDebitNormal == debit) e.amount else -e.amount
        }
}

/** A line of a transaction that has not been persisted yet. */
data class DraftLine(
    val accountId: String,
    val amount: Long,
    val type: EntryType,
    val id: String? = null,
    /** Spreads an expense or income line over these days instead of the transaction date (see [Periodization]). */
    val period: CostPeriod? = null,
)

data class TransactionDraft(
    val date: LocalDate,
    val title: String,
    val description: String,
    val lines: List<DraftLine>,
    val id: String? = null,
)

/** A capital asset depreciated by automatic monthly write-off transactions. */
data class Asset(
    val id: String,
    val name: String,
    val cost: Long,
    val activationDate: LocalDate,
    val usefulLifeMonths: Int,
    val debitAccountId: String,
    val creditAccountId: String,
    /** First day of the most recent month that has been written off, if any. */
    val lastWrittenOffMonth: LocalDate?,
    val amountWrittenOff: Long,
) {
    val currentValue: Long get() = cost - amountWrittenOff
    val writtenOffFraction: Float get() = if (cost <= 0) 0f else (amountWrittenOff.toFloat() / cost).coerceIn(0f, 1f)
}

/** A bill received or issued, tracked until it is paid. */
data class Invoice(
    val id: String,
    val amountDue: Long,
    val invoiceDate: LocalDate,
    val dueDate: LocalDate,
    val bookingTransactionId: String,
    val outstandingAccountId: String,
    /** True when the outstanding account was credited (a payable). */
    val outstandingIsCredit: Boolean,
    val isPaid: Boolean,
    val paidDate: LocalDate?,
    val paymentTransactionId: String?,
    val paidFromAccountId: String?,
    /** Instalments recorded before the invoice was fully paid (live payment transactions only). */
    val partialPayments: List<InvoicePayment> = emptyList(),
) {
    val partiallyPaid: Long get() = partialPayments.sumOf { it.amount }

    /** What is still owed. */
    val remaining: Long get() = if (isPaid) 0 else (amountDue - partiallyPaid).coerceAtLeast(0)
}

/** One instalment of an invoice: a settlement transaction for part of the amount due. */
data class InvoicePayment(
    val transactionId: String,
    val date: LocalDate,
    val amount: Long,
    val accountId: String?,
)

/** The user confirmed that [accountId] matched a statement balance of [statementBalance] on [date]. */
data class Reconciliation(
    val accountId: String,
    val date: LocalDate,
    val statementBalance: Long,
)

/** A reconciliation checked against the ledger as it is now. */
data class ReconciliationStatus(
    val reconciliation: Reconciliation,
    /** The account's balance as of the reconciled date, computed from the current ledger. */
    val bookBalance: Long,
) {
    /** Non-zero when transactions on or before the reconciled date were added, edited or deleted since. */
    val drift: Long get() = bookBalance - reconciliation.statementBalance
    val inSync: Boolean get() = drift == 0L
}

/** A named group of accounts matched by account-number prefixes (used by reports). */
data class AccountGroup(
    val name: String,
    val prefixes: List<String>,
)

/** Raised for user-facing validation failures; [message] is safe to show in the UI. */
class LedgerException(message: String) : Exception(message)
