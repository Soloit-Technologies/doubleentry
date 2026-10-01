package com.soloit.doubleentry.sync

import com.soloit.doubleentry.data.DecimalCentsSerializer
import com.soloit.doubleentry.data.local.AccountEntity
import com.soloit.doubleentry.data.local.AssetEntity
import com.soloit.doubleentry.data.local.EntryEntity
import com.soloit.doubleentry.data.local.InvoiceEntity
import com.soloit.doubleentry.data.local.TransactionEntity
import com.soloit.doubleentry.data.local.UserSettingEntity
import com.soloit.doubleentry.data.local.microsToInstant
import com.soloit.doubleentry.data.local.toMicros
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime

// JSON shapes of the PostgREST tables. Column names and types must match supabase/migrations.

/** Parses a Postgres `timestamptz` (e.g. `2026-10-01T12:34:56.123456+00:00`) to epoch micros. */
fun parseTimestamp(value: String): Long = OffsetDateTime.parse(value).toInstant().toMicros()

fun formatTimestamp(micros: Long): String = microsToInstant(micros).toString()

interface SyncRow {
    val id: String
    val updatedAt: String
    val updatedAtMicros: Long get() = parseTimestamp(updatedAt)
}

@Serializable
data class AccountRow(
    override val id: String,
    @SerialName("user_id") val userId: String = "",
    val number: Int,
    val name: String,
    val type: Int,
    @SerialName("updated_at") override val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) : SyncRow {
    fun toEntity() = AccountEntity(id, number, name, type, updatedAtMicros, deletedAt?.let(::parseTimestamp), isDirty = false)

    companion object {
        fun from(e: AccountEntity, userId: String) =
            AccountRow(e.id, userId, e.number, e.name, e.type, formatTimestamp(e.updatedAt), e.deletedAt?.let(::formatTimestamp))
    }
}

@Serializable
data class TransactionRow(
    override val id: String,
    @SerialName("user_id") val userId: String = "",
    val date: String,
    val title: String,
    val description: String? = null,
    @SerialName("updated_at") override val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) : SyncRow {
    fun toEntity() = TransactionEntity(
        id, LocalDate.parse(date), title, description.orEmpty(), updatedAtMicros, deletedAt?.let(::parseTimestamp), isDirty = false,
    )

    companion object {
        fun from(e: TransactionEntity, userId: String) = TransactionRow(
            e.id, userId, e.date.toString(), e.title, e.description, formatTimestamp(e.updatedAt), e.deletedAt?.let(::formatTimestamp),
        )
    }
}

@Serializable
data class EntryRow(
    override val id: String,
    @SerialName("user_id") val userId: String = "",
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("account_id") val accountId: String,
    @Serializable(with = DecimalCentsSerializer::class) val amount: Long,
    @SerialName("entry_type") val entryType: Int,
    @SerialName("updated_at") override val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) : SyncRow {
    fun toEntity() = EntryEntity(
        id, transactionId, accountId, amount, entryType, updatedAtMicros, deletedAt?.let(::parseTimestamp), isDirty = false,
    )

    companion object {
        fun from(e: EntryEntity, userId: String) = EntryRow(
            e.id, userId, e.transactionId, e.accountId, e.amountCents, e.entryType,
            formatTimestamp(e.updatedAt), e.deletedAt?.let(::formatTimestamp),
        )
    }
}

@Serializable
data class AssetRow(
    override val id: String,
    @SerialName("user_id") val userId: String = "",
    val name: String,
    @Serializable(with = DecimalCentsSerializer::class) val cost: Long,
    @SerialName("activation_date") val activationDate: String,
    @SerialName("useful_life_months") val usefulLifeMonths: Int,
    @SerialName("debit_account_id") val debitAccountId: String,
    @SerialName("credit_account_id") val creditAccountId: String,
    @SerialName("last_written_off_month") val lastWrittenOffMonth: String? = null,
    @Serializable(with = DecimalCentsSerializer::class)
    @SerialName("amount_written_off") val amountWrittenOff: Long,
    @SerialName("updated_at") override val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) : SyncRow {
    fun toEntity() = AssetEntity(
        id = id,
        name = name,
        costCents = cost,
        activationDate = LocalDate.parse(activationDate),
        usefulLifeMonths = usefulLifeMonths,
        debitAccountId = debitAccountId,
        creditAccountId = creditAccountId,
        lastWrittenOffMonth = lastWrittenOffMonth?.let(LocalDate::parse),
        amountWrittenOffCents = amountWrittenOff,
        updatedAt = updatedAtMicros,
        deletedAt = deletedAt?.let(::parseTimestamp),
        isDirty = false,
    )

    companion object {
        fun from(e: AssetEntity, userId: String) = AssetRow(
            id = e.id,
            userId = userId,
            name = e.name,
            cost = e.costCents,
            activationDate = e.activationDate.toString(),
            usefulLifeMonths = e.usefulLifeMonths,
            debitAccountId = e.debitAccountId,
            creditAccountId = e.creditAccountId,
            lastWrittenOffMonth = e.lastWrittenOffMonth?.toString(),
            amountWrittenOff = e.amountWrittenOffCents,
            updatedAt = formatTimestamp(e.updatedAt),
            deletedAt = e.deletedAt?.let(::formatTimestamp),
        )
    }
}

@Serializable
data class InvoiceRow(
    override val id: String,
    @SerialName("user_id") val userId: String = "",
    @Serializable(with = DecimalCentsSerializer::class)
    @SerialName("amount_due") val amountDue: Long,
    @SerialName("invoice_date") val invoiceDate: String,
    @SerialName("due_date") val dueDate: String,
    @SerialName("booking_transaction_id") val bookingTransactionId: String,
    @SerialName("outstanding_account_id") val outstandingAccountId: String,
    @SerialName("outstanding_is_credit") val outstandingIsCredit: Boolean,
    @SerialName("is_paid") val isPaid: Boolean,
    @SerialName("paid_date") val paidDate: String? = null,
    @SerialName("payment_transaction_id") val paymentTransactionId: String? = null,
    @SerialName("paid_from_account_id") val paidFromAccountId: String? = null,
    @SerialName("updated_at") override val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) : SyncRow {
    fun toEntity() = InvoiceEntity(
        id = id,
        amountDueCents = amountDue,
        invoiceDate = LocalDate.parse(invoiceDate),
        dueDate = LocalDate.parse(dueDate),
        bookingTransactionId = bookingTransactionId,
        outstandingAccountId = outstandingAccountId,
        outstandingIsCredit = outstandingIsCredit,
        isPaid = isPaid,
        paidDate = paidDate?.let(LocalDate::parse),
        paymentTransactionId = paymentTransactionId,
        paidFromAccountId = paidFromAccountId,
        updatedAt = updatedAtMicros,
        deletedAt = deletedAt?.let(::parseTimestamp),
        isDirty = false,
    )

    companion object {
        fun from(e: InvoiceEntity, userId: String) = InvoiceRow(
            id = e.id,
            userId = userId,
            amountDue = e.amountDueCents,
            invoiceDate = e.invoiceDate.toString(),
            dueDate = e.dueDate.toString(),
            bookingTransactionId = e.bookingTransactionId,
            outstandingAccountId = e.outstandingAccountId,
            outstandingIsCredit = e.outstandingIsCredit,
            isPaid = e.isPaid,
            paidDate = e.paidDate?.toString(),
            paymentTransactionId = e.paymentTransactionId,
            paidFromAccountId = e.paidFromAccountId,
            updatedAt = formatTimestamp(e.updatedAt),
            deletedAt = e.deletedAt?.let(::formatTimestamp),
        )
    }
}

@Serializable
data class UserSettingRow(
    override val id: String,
    @SerialName("user_id") val userId: String = "",
    val key: String,
    val value: String,
    @SerialName("updated_at") override val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) : SyncRow {
    fun toEntity() = UserSettingEntity(id, key, value, updatedAtMicros, deletedAt?.let(::parseTimestamp), isDirty = false)

    companion object {
        fun from(e: UserSettingEntity, userId: String) =
            UserSettingRow(e.id, userId, e.key, e.value, formatTimestamp(e.updatedAt), e.deletedAt?.let(::formatTimestamp))
    }
}
