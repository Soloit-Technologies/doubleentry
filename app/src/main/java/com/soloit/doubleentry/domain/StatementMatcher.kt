package com.soloit.doubleentry.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

/** A statement line that is already in the ledger as [transaction]. */
data class StatementMatch(val line: StatementLine, val transaction: Transaction)

/** Lines for a new transaction, copied from [source]: the latest booking with the same text. */
data class VoucherGuess(val lines: List<DraftLine>, val source: Transaction)

/**
 * A statement line that is not in the ledger yet, with the booking guessed from earlier ones, or
 * the open [invoice] it pays (which takes precedence: the invoice already booked the cost).
 */
data class NewStatementLine(val line: StatementLine, val guess: VoucherGuess?, val invoice: Invoice? = null)

data class StatementAnalysis(
    val matched: List<StatementMatch>,
    val new: List<NewStatementLine>,
    /** Transactions on the account dated within the statement period that no line matched. */
    val missing: List<Transaction>,
    /** The statement period: its first and last line date. */
    val from: LocalDate,
    val to: LocalDate,
)

/**
 * Compares a bank statement with the ledger of the account it belongs to. A line and a transaction
 * match when the transaction moves the account by the same amount within a few days of the bank's
 * date (purchase vs booking date, weekends); each transaction matches at most one line, so two
 * equal coffees on the same day need two transactions.
 */
object StatementMatcher {
    /** How far a booked transaction's date may be from the bank's. */
    const val DATE_TOLERANCE_DAYS = 4L

    fun analyze(
        account: Account,
        lines: List<StatementLine>,
        transactions: List<Transaction>,
        invoices: List<Invoice> = emptyList(),
    ): StatementAnalysis {
        require(lines.isNotEmpty()) { "A statement needs at least one line." }
        val from = lines.minOf { it.date }
        val to = lines.maxOf { it.date }
        val effects = transactions.associate { it.id to cashEffect(it, account.id) }
        val onAccount = transactions.filter { effects.getValue(it.id) != 0L }
        val byAmount = onAccount
            .filter { it.date >= from.minusDays(DATE_TOLERANCE_DAYS) && it.date <= to.plusDays(DATE_TOLERANCE_DAYS) }
            .groupBy { effects.getValue(it.id) }

        // Closest dates first, then the most similar text, so the obvious pairs are taken before the doubtful ones.
        class Candidate(val line: StatementLine, val transaction: Transaction, val days: Long, val similarity: Double)
        val candidates = lines.flatMap { line ->
            byAmount[line.amount].orEmpty().mapNotNull { tx ->
                val days = ChronoUnit.DAYS.between(line.date, tx.date).absoluteValue
                if (days > DATE_TOLERANCE_DAYS) null else Candidate(line, tx, days, similarity(line.text, tx.title))
            }
        }.sortedWith(compareBy<Candidate>({ it.days }, { -it.similarity }, { it.line.row }))

        val matchedRows = HashSet<Int>()
        val matchedIds = HashSet<String>()
        val matched = mutableListOf<StatementMatch>()
        for (c in candidates) {
            if (c.line.row in matchedRows || c.transaction.id in matchedIds) continue
            matchedRows += c.line.row
            matchedIds += c.transaction.id
            matched += StatementMatch(c.line, c.transaction)
        }

        val history = VoucherHistory(account, transactions, matched)
        val newLines = lines.filter { it.row !in matchedRows }
        val titles = transactions.associate { it.id to it.title }
        val payments = matchInvoices(account, newLines, invoices, titles)
        return StatementAnalysis(
            matched = matched.sortedBy { it.line.row },
            new = newLines.map { NewStatementLine(it, history.guess(it), payments[it.row]) },
            missing = onAccount.filter { it.id !in matchedIds && it.date >= from && it.date <= to },
            from = from,
            to = to,
        )
    }

    /**
     * Open invoices paid by [lines]: a line that moves the account by exactly what is still owed, in
     * the paying direction, no earlier than a few days before the invoice date. Each invoice is paid
     * by at most one line: the one closest to its due date, then with the most similar text.
     */
    internal fun matchInvoices(account: Account, lines: List<StatementLine>, invoices: List<Invoice>, titles: Map<String, String>): Map<Int, Invoice> {
        class Candidate(val line: StatementLine, val invoice: Invoice, val days: Long, val similarity: Double)
        val candidates = lines.flatMap { line ->
            invoices.filter { invoice ->
                canPay(account.id, line.amount, invoice) && line.amount.absoluteValue == invoice.remaining &&
                    line.date >= invoice.invoiceDate.minusDays(DATE_TOLERANCE_DAYS)
            }.map { invoice ->
                val days = ChronoUnit.DAYS.between(invoice.dueDate, line.date).absoluteValue
                Candidate(line, invoice, days, similarity(line.text, titles[invoice.bookingTransactionId].orEmpty()))
            }
        }.sortedWith(compareBy<Candidate>({ it.days }, { -it.similarity }, { it.line.row }))

        val paid = HashMap<Int, Invoice>()
        val taken = HashSet<String>()
        for (c in candidates) {
            if (c.line.row in paid || c.invoice.id in taken) continue
            paid[c.line.row] = c.invoice
            taken += c.invoice.id
        }
        return paid
    }

    /**
     * Whether money moving [amount] on [accountId] can pay (part of) [invoice]: out for a bill, in
     * for a receivable, no more than is still owed, and not from the invoice's own outstanding account.
     */
    fun canPay(accountId: String, amount: Long, invoice: Invoice): Boolean =
        !invoice.isPaid && accountId != invoice.outstandingAccountId && amount != 0L &&
            (amount < 0) == invoice.outstandingIsCredit && amount.absoluteValue <= invoice.remaining

    /** How [transaction] moves the account as the bank sees it: debits minus credits (money in is positive). */
    fun cashEffect(transaction: Transaction, accountId: String): Long =
        transaction.entries.filter { it.account.id == accountId }.sumOf { it.signed() }

    /** A two-line booking of a statement [amount] on [accountId] against [counterAccountId]. */
    fun counterLines(accountId: String, amount: Long, counterAccountId: String): List<DraftLine> {
        val side = if (amount > 0) EntryType.Debit else EntryType.Credit
        return listOf(
            DraftLine(accountId, amount.absoluteValue, side),
            DraftLine(counterAccountId, amount.absoluteValue, side.opposite()),
        )
    }

    /**
     * The lines of [source] for a new [amount] on [accountId]: the lines on [bankAccountIds] become one
     * line on the account and the other lines are scaled to the new amount (a salary with tax keeps its
     * split). The sides flip when the money goes the other way (a refund). Null when it can't be done.
     */
    internal fun copyLines(source: Transaction, bankAccountIds: Set<String>, accountId: String, amount: Long): List<DraftLine>? {
        val (bank, others) = source.entries.partition { it.account.id in bankAccountIds }
        val old = bank.sumOf { it.signed() }
        if (old == 0L || others.isEmpty()) return null
        val flip = (old > 0) != (amount > 0)
        val counter = others.map { e ->
            val scaled = if (old.absoluteValue == amount.absoluteValue) {
                e.amount
            } else {
                BigDecimal.valueOf(e.amount).multiply(BigDecimal.valueOf(amount.absoluteValue))
                    .divide(BigDecimal.valueOf(old.absoluteValue), 0, RoundingMode.HALF_UP).toLong()
            }
            DraftLine(e.account.id, scaled, if (flip) e.type.opposite() else e.type)
        }.toMutableList()
        // Rounding can leave a cent over; settle it on the largest line so the entry balances.
        val diff = -amount - counter.sumOf { it.signed() }
        if (diff != 0L) {
            val i = counter.indices.maxBy { counter[it].amount }
            counter[i] = counter[i].let { it.copy(amount = it.amount + if (it.type == EntryType.Debit) diff else -diff) }
        }
        val side = if (amount > 0) EntryType.Debit else EntryType.Credit
        val lines = listOf(DraftLine(accountId, amount.absoluteValue, side)) + counter.filter { it.amount != 0L }
        return lines.takeIf { it.size >= 2 && it.all { l -> l.amount > 0 } }
    }

    /** Case and spacing don't matter: "ICA  Nära" is "ica nära". */
    internal fun textKey(text: String): String = text.trim().lowercase().replace(Regex("""\s+"""), " ")

    /** Letters only, for texts with varying dates or numbers: "Kortköp 231003 ICA" is "kortköp ica". */
    internal fun looseKey(text: String): String =
        text.lowercase().map { if (it.isLetter()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    private fun similarity(a: String, b: String): Double {
        val x = looseKey(a).split(' ').filter { it.isNotEmpty() }.toSet()
        val y = looseKey(b).split(' ').filter { it.isNotEmpty() }.toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return (x intersect y).size.toDouble() / (x union y).size
    }

    private fun Entry.signed(): Long = if (type == EntryType.Debit) amount else -amount
    private fun DraftLine.signed(): Long = if (type == EntryType.Debit) amount else -amount
    private fun EntryType.opposite(): EntryType = if (this == EntryType.Debit) EntryType.Credit else EntryType.Debit

    /**
     * The latest booking for every text: the ledger's transactions by title, plus the matched
     * statement lines by the bank's text (so "ICA NÄRA 1234" learns from a purchase booked as "Food").
     */
    private class VoucherHistory(private val account: Account, transactions: List<Transaction>, matches: List<StatementMatch>) {
        private class Source(val transaction: Transaction, val bankAccountIds: Set<String>)

        private val byText = HashMap<String, Source>()
        private val byLooseText = HashMap<String, Source>()

        init {
            val sources = transactions.map { it.title to it } + matches.map { it.line.text to it.transaction }
            // Oldest first, so later bookings replace earlier ones.
            for ((text, tx) in sources.sortedBy { it.second.date }) {
                val source = Source(tx, bankAccountIds(tx) ?: continue)
                byText[textKey(text)] = source
                looseKey(text).takeIf { it.isNotEmpty() }?.let { byLooseText[it] = source }
            }
        }

        fun guess(line: StatementLine): VoucherGuess? {
            val source = byText[textKey(line.text)] ?: looseKey(line.text).takeIf { it.isNotEmpty() }?.let(byLooseText::get) ?: return null
            val lines = copyLines(source.transaction, source.bankAccountIds, account.id, line.amount) ?: return null
            return VoucherGuess(lines, source.transaction)
        }

        /**
         * The lines that played the part of this account: its own, or else the only line on an account
         * of the same type (a purchase paid from another bank account or card).
         */
        private fun bankAccountIds(tx: Transaction): Set<String>? {
            if (tx.entries.any { it.account.id == account.id }) return setOf(account.id)
            return tx.entries.filter { it.account.type == account.type }.singleOrNull()?.let { setOf(it.account.id) }
        }
    }
}
