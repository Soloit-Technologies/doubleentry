package com.soloit.doubleentry.ui.transactions

import com.soloit.doubleentry.domain.Entry
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.ui.components.LineInput
import com.soloit.doubleentry.ui.components.Lines
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds the editable debit/credit lines shared by the transaction and invoice editors.
 * [onUserChange] fires for edits made by the user (not for programmatic loads).
 */
class BookingLines(private val onUserChange: () -> Unit) {
    private var nextKey = 0L
    private val _lines = MutableStateFlow(listOf(newLine(), newLine()))
    val lines: StateFlow<List<LineInput>> = _lines.asStateFlow()
    val current: List<LineInput> get() = _lines.value

    fun newLine(accountId: String? = null, debit: String = "", credit: String = "", locked: Boolean = false) =
        LineInput(nextKey++, accountId, debit, credit, locked)

    /** Replaces all lines without counting as a user edit (used when loading). */
    fun load(entries: List<Entry>) {
        _lines.value = entries.map {
            newLine(
                accountId = it.account.id,
                debit = if (it.type == EntryType.Debit) Money.toInput(it.amount) else "",
                credit = if (it.type == EntryType.Credit) Money.toInput(it.amount) else "",
            )
        }.ifEmpty { listOf(newLine(), newLine()) }
    }

    fun set(lines: List<LineInput>) { _lines.value = lines }

    fun update(key: Long, transform: (LineInput) -> LineInput) = edit { list -> list.map { if (it.key == key) transform(it) else it } }

    fun setAccount(key: Long, accountId: String) = update(key) { it.copy(accountId = accountId) }
    fun setDebit(key: Long, value: String) = update(key) { it.copy(debit = value) }
    fun setCredit(key: Long, value: String) = update(key) { it.copy(credit = value) }
    fun add() = edit { it + newLine() }
    fun remove(key: Long) = edit { list -> list.filterNot { it.key == key && !it.locked } }

    fun autoFillDebit(key: Long) = autoFill(key, debitSide = true)
    fun autoFillCredit(key: Long) = autoFill(key, debitSide = false)

    private fun autoFill(key: Long, debitSide: Boolean) {
        val before = _lines.value
        val after = Lines.autoFill(before, key, debitSide)
        if (after != before) edit { after }
    }

    private fun edit(transform: (List<LineInput>) -> List<LineInput>) {
        _lines.update(transform)
        onUserChange()
    }
}
