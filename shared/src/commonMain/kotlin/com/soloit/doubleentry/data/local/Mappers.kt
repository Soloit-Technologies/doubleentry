package com.soloit.doubleentry.data.local

import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.Asset
import com.soloit.doubleentry.domain.Entry
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Invoice
import com.soloit.doubleentry.domain.Transaction
import java.time.Instant

/** Epoch microseconds — the resolution of Postgres `timestamptz`. */
fun Instant.toMicros(): Long = Math.addExact(Math.multiplyExact(epochSecond, 1_000_000L), (nano / 1_000).toLong())

fun microsToInstant(micros: Long): Instant =
    Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L)

/** Now, by the server's clock as far as it is known (see [ServerClock]). Used for every sync stamp. */
fun nowMicros(): Long = Instant.now().toMicros() + ServerClock.offsetMicros

fun AccountEntity.toDomain() = Account(id, number, name, AccountType.fromOrdinal(type))

fun EntryWithAccount.toDomain() = Entry(
    id = id,
    account = Account(accountId, accountNumber, accountName, AccountType.fromOrdinal(accountType)),
    amount = amountCents,
    type = EntryType.fromOrdinal(entryType),
)

fun TransactionEntity.toDomain(entries: List<Entry>) = Transaction(id, date, title, description, entries)

fun AssetEntity.toDomain() = Asset(
    id = id,
    name = name,
    cost = costCents,
    activationDate = activationDate,
    usefulLifeMonths = usefulLifeMonths,
    debitAccountId = debitAccountId,
    creditAccountId = creditAccountId,
    lastWrittenOffMonth = lastWrittenOffMonth,
    amountWrittenOff = amountWrittenOffCents,
)

fun InvoiceEntity.toDomain() = Invoice(
    id = id,
    amountDue = amountDueCents,
    invoiceDate = invoiceDate,
    dueDate = dueDate,
    bookingTransactionId = bookingTransactionId,
    outstandingAccountId = outstandingAccountId,
    outstandingIsCredit = outstandingIsCredit,
    isPaid = isPaid,
    paidDate = paidDate,
    paymentTransactionId = paymentTransactionId,
    paidFromAccountId = paidFromAccountId,
)
