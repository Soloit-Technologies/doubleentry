package com.soloit.doubleentry.ui.transactions

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Transaction
import com.soloit.doubleentry.domain.relativeDayLabel
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AccountTypeAvatar
import com.soloit.doubleentry.ui.components.CardDivider
import com.soloit.doubleentry.ui.components.ConfirmDialog
import com.soloit.doubleentry.ui.components.EmptyState
import com.soloit.doubleentry.ui.components.LoadingState
import com.soloit.doubleentry.ui.components.MessageDialog
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SectionHeader
import com.soloit.doubleentry.ui.components.StatusChip
import com.soloit.doubleentry.ui.components.icon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TransactionDetailUiState(
    val loading: Boolean = true,
    val transaction: Transaction? = null,
    val isDepreciation: Boolean = false,
)

class TransactionDetailViewModel(private val ledger: LedgerRepository, private val id: String) : ViewModel() {
    val message = MutableStateFlow<String?>(null)
    val deleted = MutableStateFlow(false)

    val state: StateFlow<TransactionDetailUiState> = ledger.changes.mapLatest {
        TransactionDetailUiState(false, ledger.getTransaction(id), ledger.isDepreciationTransaction(id))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionDetailUiState())

    fun delete() = viewModelScope.launch {
        try {
            ledger.deleteTransaction(id)
            deleted.value = true
        } catch (e: Exception) {
            message.value = e.message
        }
    }
}

@Composable
fun TransactionDetailScreen(transactionId: String, onBack: () -> Unit, onEdit: () -> Unit, onDuplicate: () -> Unit) {
    val vm = appViewModel { TransactionDetailViewModel(it.ledger, transactionId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(deleted) { if (deleted) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transaction") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (state.transaction != null) {
                        IconButton(onClick = onDuplicate) { Icon(Icons.Rounded.ContentCopy, "Duplicate") }
                        IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "Edit") }
                        if (!state.isDepreciation) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "Delete") }
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
                }
                if (tx.description.isNotBlank()) {
                    SectionCard(contentPadding = PaddingValues(16.dp)) {
                        Text("Description", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(tx.description, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                EntriesCard(tx)
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete transaction?",
            message = "This removes the transaction and all its lines. This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { confirmDelete = false; vm.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
    message?.let { MessageDialog("Couldn't delete", it) { vm.message.value = null } }
}

@Composable
internal fun EntriesCard(tx: Transaction) {
    Column {
        SectionHeader("Lines")
        SectionCard {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Account", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("Debit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                Text("Credit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            tx.entries.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(e.account.type.icon(), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.account.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("#${e.account.number}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.End) {
                        if (e.type == EntryType.Debit) MoneyText(e.amount, style = MaterialTheme.typography.bodyMedium)
                    }
                    Column(Modifier.width(96.dp), horizontalAlignment = Alignment.End) {
                        if (e.type == EntryType.Credit) MoneyText(e.amount, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            CardDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Total", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
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
