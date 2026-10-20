package com.soloit.doubleentry.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.data.CostPeriodSummary
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.Recurrence
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.domain.label
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Transaction
import com.soloit.doubleentry.domain.relativeDayLabel
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AccountTypeAvatar
import com.soloit.doubleentry.ui.components.CardDivider
import com.soloit.doubleentry.ui.components.EmptyState
import com.soloit.doubleentry.ui.components.LoadingState
import com.soloit.doubleentry.ui.components.MessageDialog
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SectionHeader
import com.soloit.doubleentry.ui.components.StatusChip
import com.soloit.doubleentry.ui.components.icon
import com.soloit.doubleentry.ui.components.isLargeFont
import java.time.LocalDate

@Composable
fun TransactionDetailScreen(
    transactionId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onRepeat: () -> Unit,
    onOpenRecurring: (String) -> Unit,
    onOpenTransaction: (String) -> Unit,
    /** Opens an account's ledger at the month of this transaction's date. */
    onOpenAccount: (String, LocalDate) -> Unit,
) {
    val vm = appViewModel { TransactionDetailViewModel(it.ledger, it.recurring, it.undo, transactionId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    LaunchedEffect(deleted) { if (deleted) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transaction") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (state.transaction != null && state.periodSource == null) {
                        if (!state.isDepreciation && state.recurringRule == null) {
                            IconButton(onClick = onRepeat) { Icon(Icons.Rounded.Repeat, "Make recurring") }
                        }
                        IconButton(onClick = onDuplicate) { Icon(Icons.Rounded.ContentCopy, "Duplicate") }
                        IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "Edit") }
                        if (!state.isAutomatic) IconButton(onClick = vm::delete) { Icon(Icons.Rounded.DeleteOutline, "Delete") }
                    }
                },
            )
        },
    ) { padding ->
        val tx = state.transaction
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            tx == null -> EmptyState(Icons.AutoMirrored.Rounded.EventNote, "Transaction not found", "It may have been deleted on another device.", Modifier.padding(padding))
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SectionCard(contentPadding = PaddingValues(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccountTypeAvatar(tx.categoryType(), size = 48)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tx.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(tx.date.relativeDayLabel(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    MoneyText(tx.totalDebits, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    Text(tx.flowSummary(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.isDepreciation) {
                        Spacer(Modifier.height(12.dp))
                        StatusChip(
                            "Automatic depreciation · delete the asset to remove",
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.onSecondaryContainer,
                            icon = Icons.Rounded.AutoMode,
                        )
                    }
                    state.periodSource?.let { source ->
                        Spacer(Modifier.height(12.dp))
                        Surface(onClick = { onOpenTransaction(source.id) }, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            StatusChip(
                                "Share of \u201C${source.title}\u201D · ${source.date.formatMedium()}",
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.colorScheme.onSecondaryContainer,
                                icon = Icons.Rounded.DateRange,
                            )
                        }
                    }
                    state.recurringRule?.let { rule ->
                        Spacer(Modifier.height(12.dp))
                        Surface(onClick = { onOpenRecurring(rule.id) }, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            StatusChip(
                                "Recurring · ${Recurrence.summary(rule)}",
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.colorScheme.onSecondaryContainer,
                                icon = Icons.Rounded.Repeat,
                            )
                        }
                    }
                }
                if (tx.description.isNotBlank()) {
                    SectionCard(contentPadding = PaddingValues(16.dp)) {
                        Text("Description", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(tx.description, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                EntriesCard(tx, onOpenAccount = { onOpenAccount(it, tx.date) })
                state.costPeriods?.let { CostPeriodsCard(it, state.accounts, onOpenTransaction) }
            }
        }
    }

    message?.let { MessageDialog("Couldn't delete", it) { vm.message.value = null } }
}

/** Each spread line with its monthly shares; a share posted separately opens its posting. */
@Composable
private fun CostPeriodsCard(summary: CostPeriodSummary, accounts: Map<String, Account>, onOpen: (String) -> Unit) {
    Column {
        SectionHeader("Spread over period")
        SectionCard {
            summary.lines.forEachIndexed { index, line ->
                if (index > 0) CardDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(accounts[line.accountId]?.name ?: "Unknown account", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${Money.format(line.amount)} · ${line.period.label()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                line.shares.forEach { share ->
                    val postingId = share.postingId
                    Row(
                        Modifier.fillMaxWidth()
                            .then(if (postingId != null) Modifier.clickable { onOpen(postingId) } else Modifier)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(share.month.label(), style = MaterialTheme.typography.bodyMedium)
                            if (postingId == null) {
                                Text("In this transaction", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        MoneyText(share.amount, style = MaterialTheme.typography.bodyMedium)
                        if (postingId != null) {
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open posting", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Spacer(Modifier.width(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EntriesCard(tx: Transaction, onOpenAccount: ((String) -> Unit)? = null) {
    // With a large font two fixed amount columns don't fit, so each line shows its one amount and names its side.
    val singleColumn = isLargeFont()
    Column {
        SectionHeader("Lines")
        SectionCard {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Account", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (!singleColumn) {
                    Text("Debit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text("Credit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            tx.entries.forEach { e ->
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (onOpenAccount != null) Modifier.clickable { onOpenAccount(e.account.id) } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(e.account.type.icon(), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp))
                    // The name gets the full row width; the amounts share the second line with the account number
                    Column(Modifier.weight(1f)) {
                        Text(e.account.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (singleColumn) {
                            Text("#${e.account.number} · ${e.type.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MoneyText(e.amount, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.End))
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("#${e.account.number}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Column(Modifier.width(96.dp), horizontalAlignment = Alignment.End) {
                                    if (e.type == EntryType.Debit) MoneyText(e.amount, style = MaterialTheme.typography.bodyMedium)
                                }
                                Column(Modifier.width(96.dp), horizontalAlignment = Alignment.End) {
                                    if (e.type == EntryType.Credit) MoneyText(e.amount, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
            CardDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Total", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (singleColumn) {
                    // Saved transactions always balance, so one total covers both sides.
                    MoneyText(tx.totalDebits, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                } else {
                    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.End) {
                        MoneyText(tx.totalDebits, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.End) {
                        MoneyText(tx.totalCredits, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
