package com.soloit.doubleentry.ui.transactions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth

data class TransactionRowUi(
    val id: String,
    val date: LocalDate,
    val title: String,
    val flow: String,
    val amount: Long,
    val categoryType: AccountType,
    val searchText: String,
)

data class TransactionsUiState(
    val loading: Boolean = true,
    val month: YearMonth = YearMonth.now(),
    val days: List<Pair<LocalDate, List<TransactionRowUi>>> = emptyList(),
    val count: Int = 0,
    /** A search covers every month, not just the selected one. */
    val searching: Boolean = false,
)

/** "Checking Account → Groceries", with "+n" when a side has several accounts. */
fun Transaction.flowSummary(): String {
    fun side(type: EntryType): String {
        val names = entries.filter { it.type == type }.map { it.account.name }.distinct()
        return when (names.size) {
            0 -> "—"
            1 -> names[0]
            else -> "${names[0]} +${names.size - 1}"
        }
    }
    return "${side(EntryType.Credit)} → ${side(EntryType.Debit)}"
}

/** The account type that best describes what a transaction is about (for its icon). */
fun Transaction.categoryType(): AccountType = when {
    entries.any { it.account.type == AccountType.Revenue } -> AccountType.Revenue
    entries.any { it.account.type == AccountType.Expense } -> AccountType.Expense
    else -> entries.firstOrNull { it.type == EntryType.Debit }?.account?.type ?: AccountType.Asset
}

/** What a search matches against: title, description, account names and numbers, and the amount. */
internal fun Transaction.searchText(): String = buildString {
    append(title).append(' ').append(description).append(' ')
    entries.forEach { append(it.account.name).append(' ').append(it.account.number).append(' ') }
    append(Money.format(totalDebits)).append(' ').append(Money.toInput(totalDebits))
}

internal fun Transaction.toRow() = TransactionRowUi(
    id = id,
    date = date,
    title = title,
    flow = flowSummary(),
    amount = totalDebits,
    categoryType = categoryType(),
    searchText = searchText(),
)

private val newestFirst = compareByDescending<Transaction> { it.date }.thenBy { it.title }

class TransactionsViewModel(ledger: LedgerRepository) : ViewModel() {
    private val month = MutableStateFlow(YearMonth.now())

    /** Compose state, not part of [state], so the search field never lags behind typing. */
    var query by mutableStateOf("")

    /** Every transaction, newest first: a month is a slice of it and a search scans all of it. */
    private val everything = ledger.changes.mapLatest { ledger.getTransactions().sortedWith(newestFirst).map { it.toRow() } }

    val state: StateFlow<TransactionsUiState> = combine(everything, month, snapshotFlow { query }) { all, m, q ->
        val search = q.trim()
        val matches = if (search.isEmpty()) {
            all.filter { YearMonth.from(it.date) == m }
        } else {
            all.filter { it.searchText.contains(search, ignoreCase = true) }
        }
        TransactionsUiState(
            loading = false,
            month = m,
            days = matches.groupBy { it.date }.toList(),
            count = matches.size,
            searching = search.isNotEmpty(),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    fun setMonth(value: YearMonth) { month.value = value }
}
