package com.soloit.doubleentry.ui.transactions

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.domain.Transaction
import com.soloit.doubleentry.domain.label
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
import com.soloit.doubleentry.ui.components.TextAndFigures
import com.soloit.doubleentry.ui.components.isLargeFont

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
            item { SearchField(vm.query, { vm.query = it }, "Search all transactions", Modifier.padding(bottom = 12.dp)) }
            // A search always covers every month, so the month selector steps aside while one is active.
            if (!state.searching) item { MonthSelector(state.month, vm::setMonth, Modifier.padding(bottom = 12.dp)) }
            when {
                state.loading -> item { LoadingState() }
                state.days.isEmpty() -> item {
                    if (state.searching) {
                        EmptyState(Icons.AutoMirrored.Rounded.ReceiptLong, "No matches", "Nothing in any month matches. Try a different search.")
                    } else {
                        EmptyState(
                            Icons.AutoMirrored.Rounded.ReceiptLong,
                            "No transactions in ${state.month.label()}",
                            "Tap Transaction to record money moving between two accounts.",
                        )
                    }
                }
                else -> {
                    item {
                        val noun = "${state.count} ${if (state.searching) "match" else "transaction"}${if (state.count == 1) "" else if (state.searching) "es" else "s"}"
                        Text(
                            if (state.searching) "$noun in all months" else "$noun in ${state.month.label()}",
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
        TextAndFigures(figures = { MoneyText(row.amount, fontWeight = FontWeight.Medium) }) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = if (isLargeFont()) 2 else 1, overflow = TextOverflow.Ellipsis)
            Text(
                row.flow,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
