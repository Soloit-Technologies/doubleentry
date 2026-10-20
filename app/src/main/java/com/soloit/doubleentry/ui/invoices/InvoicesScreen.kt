package com.soloit.doubleentry.ui.invoices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.domain.Invoice
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.EmptyState
import com.soloit.doubleentry.ui.components.LoadingState
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.ScreenPadding
import com.soloit.doubleentry.ui.components.SearchField
import com.soloit.doubleentry.ui.components.SegmentedChoice
import com.soloit.doubleentry.ui.components.StatusChip
import com.soloit.doubleentry.ui.components.TextAndFigures
import com.soloit.doubleentry.ui.components.isLargeFont
import com.soloit.doubleentry.ui.theme.AppTheme

@Composable
fun DueStatusChip(status: DueStatus, modifier: Modifier = Modifier) {
    val finance = AppTheme.finance
    val scheme = MaterialTheme.colorScheme
    val (container, content, icon) = when (status.tone) {
        DueTone.Paid -> Triple(finance.positiveContainer, finance.onPositiveContainer, Icons.Rounded.CheckCircle)
        DueTone.Overdue -> Triple(finance.negativeContainer, finance.onNegativeContainer, Icons.Rounded.Warning)
        DueTone.Soon -> Triple(finance.warningContainer, finance.onWarningContainer, Icons.Rounded.Schedule)
        DueTone.Later -> Triple(scheme.surfaceContainerHighest, scheme.onSurfaceVariant, Icons.Rounded.Schedule)
    }
    StatusChip(status.text, container, content, modifier, icon)
}

@Composable
fun InvoicesScreen(onOpen: (String) -> Unit, onNew: () -> Unit, onOpenSettings: () -> Unit) {
    val vm = appViewModel { InvoicesViewModel(it.invoices, it.ledger) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val finance = AppTheme.finance

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Invoices") },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Settings") } },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Invoice") })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.loading) {
                item { LoadingState() }
                return@LazyColumn
            }
            if (state.unpaidCount > 0) {
                item {
                    val overdue = state.overdueCount > 0
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = if (overdue) finance.negativeContainer else MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val on = if (overdue) finance.onNegativeContainer else MaterialTheme.colorScheme.onPrimaryContainer
                        Column(Modifier.padding(20.dp)) {
                            Text("Outstanding", style = MaterialTheme.typography.labelLarge, color = on.copy(alpha = 0.8f))
                            MoneyText(state.outstanding, style = MaterialTheme.typography.displaySmall, color = on, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${state.unpaidCount} unpaid" + if (overdue) " · ${state.overdueCount} overdue" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = on,
                            )
                        }
                    }
                }
            }
            item {
                SegmentedChoice(
                    options = InvoiceTab.entries,
                    selected = state.tab,
                    onSelect = vm::setTab,
                    label = { if (it == InvoiceTab.Unpaid) "Unpaid (${state.unpaidCount})" else "Paid (${state.paidCount})" },
                )
            }
            item { SearchField(state.query, vm::setQuery, "Search invoices") }
            if (state.rows.isEmpty()) {
                item {
                    EmptyState(
                        Icons.AutoMirrored.Rounded.ReceiptLong,
                        if (state.tab == InvoiceTab.Unpaid) "Nothing to pay" else "No paid invoices",
                        if (state.tab == InvoiceTab.Unpaid) "Add bills you receive to keep track of due dates." else "Invoices you mark as paid show up here.",
                    )
                }
            }
            items(state.rows, key = { it.invoice.id }) { row -> InvoiceCard(row) { onOpen(row.invoice.id) } }
        }
    }
}

@Composable
private fun InvoiceCard(row: InvoiceRowUi, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAndFigures(
                    figures = {
                        val partial = !row.invoice.isPaid && row.invoice.partiallyPaid > 0
                        MoneyText(if (partial) row.invoice.remaining else row.invoice.amountDue, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (partial) {
                            Text("left of ${Money.format(row.invoice.amountDue)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                ) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = if (isLargeFont()) 2 else 1, overflow = TextOverflow.Ellipsis)
                    Text("Invoice date ${row.invoice.invoiceDate.formatMedium()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DueStatusChip(row.invoice.dueStatus())
        }
    }
}
