package com.soloit.doubleentry.ui.components

import com.soloit.doubleentry.domain.CostPeriod
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Money

/** Editable state of one bookkeeping line. Exactly one of [debit]/[credit] should hold an amount. */
data class LineInput(
    val key: Long,
    val accountId: String? = null,
    val debit: String = "",
    val credit: String = "",
    /** Managed by the app (e.g. the fixed invoice credit line); account and amount can't be edited. */
    val locked: Boolean = false,
    /** Days the line's cost or income belongs to, when they differ from the transaction date. */
    val period: CostPeriod? = null,
) {
    val debitCents: Long? get() = Money.parse(debit)?.takeIf { it > 0 }
    val creditCents: Long? get() = Money.parse(credit)?.takeIf { it > 0 }
    val hasAmount: Boolean get() = debitCents != null || creditCents != null
}

/** Line bookkeeping shared by the transaction and invoice editors. */
object Lines {
    fun totals(lines: List<LineInput>): Pair<Long, Long> =
        lines.sumOf { it.debitCents ?: 0L } to lines.sumOf { it.creditCents ?: 0L }

    fun isBalanced(lines: List<LineInput>): Boolean = totals(lines).let { it.first == it.second }

    fun populatedCount(lines: List<LineInput>): Int = lines.count { it.accountId != null && it.hasAmount }

    /**
     * When the focused line is empty and every other line has an amount, fills in the difference
     * on the side that balances the entry (debit when credits exceed debits, and vice versa).
     */
    fun autoFill(lines: List<LineInput>, key: Long, debitSide: Boolean): List<LineInput> {
        val target = lines.firstOrNull { it.key == key } ?: return lines
        if (target.hasAmount || target.locked) return lines
        val others = lines.filter { it.key != key }
        if (others.isEmpty() || others.any { !it.hasAmount }) return lines
        val (debits, credits) = totals(others)
        val diff = debits - credits
        return when {
            debitSide && diff < 0 -> lines.map { if (it.key == key) it.copy(debit = Money.toInput(-diff)) else it }
            !debitSide && diff > 0 -> lines.map { if (it.key == key) it.copy(credit = Money.toInput(diff)) else it }
            else -> lines
        }
    }

    /** Validates the lines and converts them to draft lines, or returns a user-facing error. */
    fun toDraft(lines: List<LineInput>, requireBothSides: Boolean = true): Result<List<DraftLine>> {
        if (lines.any { it.accountId == null && it.hasAmount }) return failure("Choose an account for every line with an amount.")
        val populated = lines.filter { it.accountId != null && (it.debit.isNotBlank() || it.credit.isNotBlank()) }
        if (populated.size < 2) return failure("Please fill at least two lines with an account and an amount.")
        val result = mutableListOf<DraftLine>()
        for (line in populated) {
            val debit = line.debitCents
            val credit = line.creditCents
            if (debit != null && credit != null) return failure("Each line must have either a debit or a credit amount, not both.")
            if (debit == null && credit == null) return failure("Each line must have a debit or credit amount greater than zero.")
            result += if (debit != null) {
                DraftLine(line.accountId!!, debit, EntryType.Debit, period = line.period)
            } else {
                DraftLine(line.accountId!!, credit!!, EntryType.Credit, period = line.period)
            }
        }
        val debits = result.filter { it.type == EntryType.Debit }.sumOf { it.amount }
        val credits = result.filter { it.type == EntryType.Credit }.sumOf { it.amount }
        if (requireBothSides && (debits == 0L || credits == 0L)) return failure("The entry must have at least one debit and one credit.")
        if (debits != credits) return failure("Debits (${Money.format(debits)}) must equal credits (${Money.format(credits)}).")
        return Result.success(result)
    }

    private fun failure(message: String) = Result.failure<List<DraftLine>>(IllegalArgumentException(message))
}
