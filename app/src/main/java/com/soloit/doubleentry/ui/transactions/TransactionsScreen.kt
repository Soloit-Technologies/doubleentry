package com.soloit.doubleentry.ui.transactions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.Transaction
import com.soloit.doubleentry.domain.firstDay
import com.soloit.doubleentry.domain.lastDay
import com.soloit.doubleentry.domain.relativeDayLabel
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AccountTypeAvatar
import com.soloit.doubleentry.ui.components.EmptyState
import com.soloit.doubleentry.ui.components.GroupedItem
import com.soloit.doubleentry.ui.components.LoadingState
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.MonthSelector
import com.soloit.doubleentry.ui.components.ScreenPadding
import com.soloit.doubleentry.ui.components.SearchField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val query: String = "",
    val days: List<Pair<LocalDate, List<TransactionRowUi>>> = emptyList(),
    val count: Int = 0,
)

/** "Checking Account → Groceries", with "+n" when a side has several accounts. */
internal fun Transaction.flowSummary(): String {
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
internal fun Transaction.categoryType(): AccountType = when {
    entries.any { it.account.type == AccountType.Revenue } -> AccountType.Revenue
    entries.any { it.account.type == AccountType.Expense } -> AccountType.Expense
    else -> entries.firstOrNull { it.type == EntryType.Debit }?.account?.type ?: AccountType.Asset
}

class TransactionsViewModel(ledger: LedgerRepository) : ViewModel() {
    private val month = MutableStateFlow(YearMonth.now())
    private val query = MutableStateFlow("")

    private val loaded = combine(month, ledger.changes) { m, _ -> m }.mapLatest { m ->
        m to ledger.getTransactions(m.firstDay(), m.lastDay())
            .sortedWith(compareByDescending<Transaction> { it.date }.thenBy { it.title })
            .map { tx ->
                TransactionRowUi(
                    id = tx.id,
                    date = tx.date,
                    title = tx.title,
                    flow = tx.flowSummary(),
                    amount = tx.totalDebits,
                    categoryType = tx.categoryType(),
                    searchText = buildString {
                        append(tx.title).append(' ').append(tx.description).append(' ')
                        tx.entries.forEach { append(it.account.name).append(' ').append(it.account.number).append(' ') }
                        append(Money.format(tx.totalDebits)).append(' ').append(Money.toInput(tx.totalDebits))
                    },
                )
            }
    }

    val state: StateFlow<TransactionsUiState> = combine(loaded, query) { (m, rows), q ->
        val search = q.trim()
        val visible = if (search.isEmpty()) rows else rows.filter { it.searchText.contains(search, ignoreCase = true) }
        TransactionsUiState(
            loading = false,
            month = m,
            query = q,
            days = visible.groupBy { it.date }.toList(),
            count = visible.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    fun setMonth(value: YearMonth) { month.value = value }
    fun setQuery(value: String) { query.value = value }
}

@Composable
fun TransactionsScreen(onOpen: (String) -> Unit, onNew: () -> Unit, onOpenSettings: () -> Unit) {
    val vm = appViewModel { TransactionsViewModel(it.ledger) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Activity") },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Settings") } },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Transaction") })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = ScreenPadding) {
            item { MonthSelector(state.month, vm::setMonth) }
            item { SearchField(state.query, vm::setQuery, "Search title, account or amount", Modifier.padding(vertical = 12.dp)) }
            when {
                state.loading -> item { LoadingState() }
                state.days.isEmpty() -> item {
                    EmptyState(
                        Icons.AutoMirrored.Rounded.ReceiptLong,
                        if (state.query.isBlank()) "No transactions this month" else "No matches",
                        if (state.query.isBlank()) "Tap Transaction to record money moving between two accounts." else "Try a different search.",
                    )
                }
                else -> {
                    item {
                        Text(
                            "${state.count} transaction${if (state.count == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                        )
                    }
                    state.days.forEach { (date, rows) ->
                        item(key = "d-$date") {
                            Text(
                                date.relativeDayLabel(),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
                            )
                        }
                        itemsIndexed(rows, key = { _, r -> r.id }) { index, row ->
                            GroupedItem(index, rows.size, onClick = { onOpen(row.id) }) { TransactionRow(row) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(row: TransactionRowUi) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AccountTypeAvatar(row.categoryType)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                row.flow,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        MoneyText(row.amount, fontWeight = FontWeight.Medium)
    }
}
