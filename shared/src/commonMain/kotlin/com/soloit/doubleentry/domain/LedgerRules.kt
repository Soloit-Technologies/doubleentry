package com.soloit.doubleentry.domain

/** Prefix matching shared by all reports: blank prefixes never match. */
fun matchesAnyPrefix(accountNumber: Int, prefixes: List<String>): Boolean {
    val number = accountNumber.toString()
    return prefixes.any { it.isNotBlank() && number.startsWith(it.trim()) }
}

/** Index of the first group whose prefixes match [accountNumber], or -1. */
fun List<AccountGroup>.indexOfGroupFor(accountNumber: Int): Int =
    indexOfFirst { matchesAnyPrefix(accountNumber, it.prefixes) }

/** Parses a comma separated prefix list, e.g. `"19, 1930"`. */
fun parsePrefixes(raw: String): List<String> =
    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

object TransactionValidator {
    /** Throws [LedgerException] unless the transaction is titled, positive and balanced. */
    fun validate(title: String, lines: List<DraftLine>) {
        if (title.isBlank()) throw LedgerException("Transaction must have a title.")
        if (lines.isEmpty()) throw LedgerException("Transaction must have at least one entry.")
        if (lines.any { it.amount <= 0 }) throw LedgerException("All entry amounts must be positive.")
        val debits = lines.filter { it.type == EntryType.Debit }.sumOf { it.amount }
        val credits = lines.filter { it.type == EntryType.Credit }.sumOf { it.amount }
        if (debits != credits) throw LedgerException("Transaction is not balanced (debits != credits).")
    }

    fun validate(draft: TransactionDraft) = validate(draft.title, draft.lines)
}

/**
 * Picks the payable/receivable line an invoice payment settles. A payable is a liability on the
 * credit side; a receivable is an asset on the debit side. Liabilities take priority, then a
 * single line equal to the amount due, then a lone candidate.
 */
object OutstandingDetector {
    data class Outstanding(val accountId: String, val isCredit: Boolean)

    fun detect(lines: List<DraftLine>, accountTypes: Map<String, AccountType>, amountDue: Long): Outstanding {
        fun typeOf(id: String) = accountTypes[id] ?: AccountType.Asset

        val payables = lines.filter { it.type == EntryType.Credit && typeOf(it.accountId) == AccountType.Liability }
        if (payables.size == 1) return Outstanding(payables[0].accountId, true)

        val receivables = lines.filter { it.type == EntryType.Debit && typeOf(it.accountId) == AccountType.Asset }
        if (receivables.size == 1) return Outstanding(receivables[0].accountId, false)

        val amountMatches = lines.filter { it.amount == amountDue }
        if (amountMatches.size == 1) return Outstanding(amountMatches[0].accountId, amountMatches[0].type == EntryType.Credit)

        val candidates = payables + receivables
        if (candidates.size == 1) return Outstanding(candidates[0].accountId, candidates[0].type == EntryType.Credit)

        throw LedgerException(
            "Could not determine the outstanding payable/receivable account from the bookkeeping rows. " +
                "Make sure exactly one liability (credit) or asset (debit) line represents the amount due.",
        )
    }
}
