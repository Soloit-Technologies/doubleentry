package com.soloit.doubleentry.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * Every synced row carries the same bookkeeping columns as its Supabase counterpart:
 * - [updatedAt]: epoch microseconds of the last change (server-stamped after a push).
 * - [deletedAt]: tombstone for soft deletes so deletions replicate to other devices.
 * - [isDirty]: local-only flag marking rows that still need to be pushed.
 */
interface SyncEntity {
    val id: String
    val updatedAt: Long
    val deletedAt: Long?
    val isDirty: Boolean
}

@Entity(tableName = "accounts", indices = [Index("number")])
data class AccountEntity(
    @PrimaryKey override val id: String,
    val number: Int,
    val name: String,
    /** [com.soloit.doubleentry.domain.AccountType] ordinal. */
    val type: Int,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo(name = "is_dirty") override val isDirty: Boolean = true,
) : SyncEntity

@Entity(tableName = "transactions", indices = [Index("date")])
data class TransactionEntity(
    @PrimaryKey override val id: String,
    val date: LocalDate,
    val title: String,
    val description: String,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo(name = "is_dirty") override val isDirty: Boolean = true,
) : SyncEntity

// No foreign keys on purpose: sync may deliver rows in any state and integrity is enforced by
// the server schema (supabase/migrations) and the repositories.
@Entity(
    tableName = "transaction_entries",
    indices = [Index("transaction_id"), Index("account_id")],
)
data class EntryEntity(
    @PrimaryKey override val id: String,
    @ColumnInfo(name = "transaction_id") val transactionId: String,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    /** [com.soloit.doubleentry.domain.EntryType] ordinal. */
    @ColumnInfo(name = "entry_type") val entryType: Int,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo(name = "is_dirty") override val isDirty: Boolean = true,
) : SyncEntity

@Entity(tableName = "assets")
data class AssetEntity(
    @PrimaryKey override val id: String,
    val name: String,
    @ColumnInfo(name = "cost_cents") val costCents: Long,
    @ColumnInfo(name = "activation_date") val activationDate: LocalDate,
    @ColumnInfo(name = "useful_life_months") val usefulLifeMonths: Int,
    @ColumnInfo(name = "debit_account_id") val debitAccountId: String,
    @ColumnInfo(name = "credit_account_id") val creditAccountId: String,
    @ColumnInfo(name = "last_written_off_month") val lastWrittenOffMonth: LocalDate?,
    @ColumnInfo(name = "amount_written_off_cents") val amountWrittenOffCents: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo(name = "is_dirty") override val isDirty: Boolean = true,
) : SyncEntity

@Entity(tableName = "invoices")
data class InvoiceEntity(
    @PrimaryKey override val id: String,
    @ColumnInfo(name = "amount_due_cents") val amountDueCents: Long,
    @ColumnInfo(name = "invoice_date") val invoiceDate: LocalDate,
    @ColumnInfo(name = "due_date") val dueDate: LocalDate,
    @ColumnInfo(name = "booking_transaction_id") val bookingTransactionId: String,
    @ColumnInfo(name = "outstanding_account_id") val outstandingAccountId: String,
    @ColumnInfo(name = "outstanding_is_credit") val outstandingIsCredit: Boolean,
    @ColumnInfo(name = "is_paid") val isPaid: Boolean,
    @ColumnInfo(name = "paid_date") val paidDate: LocalDate?,
    @ColumnInfo(name = "payment_transaction_id") val paymentTransactionId: String?,
    @ColumnInfo(name = "paid_from_account_id") val paidFromAccountId: String?,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo(name = "is_dirty") override val isDirty: Boolean = true,
) : SyncEntity

/** Key/value settings that sync across devices (report groups, invoice defaults …). */
@Entity(tableName = "user_settings", indices = [Index(value = ["key"], unique = true)])
data class UserSettingEntity(
    @PrimaryKey override val id: String,
    val key: String,
    val value: String,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") override val deletedAt: Long? = null,
    @ColumnInfo(name = "is_dirty") override val isDirty: Boolean = true,
) : SyncEntity

/** Joined projection of an entry with its account. */
data class EntryWithAccount(
    val id: String,
    val transactionId: String,
    val amountCents: Long,
    val entryType: Int,
    val accountId: String,
    val accountNumber: Int,
    val accountName: String,
    val accountType: Int,
)

/** Raw debit/credit totals for one account. */
data class AccountTotals(
    val accountId: String,
    val debits: Long,
    val credits: Long,
)

/** Raw debit/credit totals for one account in one month (`yyyy-MM`). */
data class MonthlyAccountTotals(
    val accountId: String,
    val month: String,
    val debits: Long,
    val credits: Long,
)
