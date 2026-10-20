package com.soloit.doubleentry.data

import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.Transaction
import java.io.Writer
import java.time.LocalDate

/**
 * CSV exports for spreadsheets and tax returns: RFC 4180 (comma separated, double-quoted when
 * needed, CRLF line endings) with amounts as plain decimals using a dot (`1234.50`), which every
 * spreadsheet imports regardless of locale.
 */
object CsvExport {
    /** One row per transaction line, so filtering by account in a spreadsheet just works. */
    fun transactions(transactions: List<Transaction>, out: Writer) {
        out.row("Date", "Transaction", "Title", "Description", "Account number", "Account name", "Account type", "Debit", "Credit")
        for (tx in transactions.sortedWith(compareBy({ it.date }, { it.id }))) {
            for (e in tx.entries.sortedWith(compareBy({ it.type }, { it.account.number }))) {
                out.row(
                    tx.date.toString(),
                    tx.id,
                    tx.title,
                    tx.description,
                    e.account.number.toString(),
                    e.account.name,
                    e.account.type.name,
                    if (e.type == EntryType.Debit) amount(e.amount) else "",
                    if (e.type == EntryType.Credit) amount(e.amount) else "",
                )
            }
        }
        out.flush()
    }

    /** Every account's balance on [asOf], on its normal side (assets and expenses positive when debited). */
    fun balances(accounts: List<Account>, balances: Map<String, Long>, asOf: LocalDate, out: Writer) {
        out.row("As of", "Account number", "Account name", "Account type", "Balance")
        for (a in accounts.sortedBy { it.number }) {
            out.row(asOf.toString(), a.number.toString(), a.name, a.type.name, amount(balances[a.id] ?: 0L))
        }
        out.flush()
    }

    internal fun amount(cents: Long): String = Money.toDecimal(cents).toPlainString()

    internal fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field

    private fun Writer.row(vararg fields: String) {
        write(fields.joinToString(",") { escape(it) })
        write("\r\n")
    }
}
