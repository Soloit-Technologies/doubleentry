package com.soloit.doubleentry.ui.assets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.soloit.doubleentry.data.AssetRepository
import com.soloit.doubleentry.data.LedgerRepository
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.Asset
import com.soloit.doubleentry.domain.Depreciation
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.Transaction
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.domain.shortLabel
import com.soloit.doubleentry.domain.yearMonth
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.AmountRow
import com.soloit.doubleentry.ui.components.ConfirmDialog
import com.soloit.doubleentry.ui.components.EmptyState
import com.soloit.doubleentry.ui.components.GroupedItem
import com.soloit.doubleentry.ui.components.LoadingState
import com.soloit.doubleentry.ui.components.MessageDialog
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class AssetDetailUiState(
    val loading: Boolean = true,
    val asset: Asset? = null,
    val debitAccount: Account? = null,
    val creditAccount: Account? = null,
    val writeoffs: List<Transaction> = emptyList(),
)

class AssetDetailViewModel(private val assets: AssetRepository, ledger: LedgerRepository, private val id: String) : ViewModel() {
    val message = MutableStateFlow<String?>(null)
    val deleted = MutableStateFlow(false)

    val state: StateFlow<AssetDetailUiState> = ledger.changes.mapLatest {
        val asset = assets.getAsset(id) ?: return@mapLatest AssetDetailUiState(loading = false)
        AssetDetailUiState(
            loading = false,
            asset = asset,
            debitAccount = ledger.getAccount(asset.debitAccountId),
            creditAccount = ledger.getAccount(asset.creditAccountId),
            writeoffs = assets.getWriteoffTransactions(id),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssetDetailUiState())

    init {
        viewModelScope.launch { runCatching { assets.processWriteoffs(LocalDate.now()) } }
    }

    fun delete() = viewModelScope.launch {
        try {
            assets.deleteAsset(id)
            deleted.value = true
        } catch (e: Exception) {
            message.value = e.message
        }
    }
}

@Composable
fun AssetDetailScreen(assetId: String, onBack: () -> Unit, onOpenTransaction: (String) -> Unit) {
    val vm = appViewModel { AssetDetailViewModel(it.assets, it.ledger, assetId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(deleted) { if (deleted) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.asset?.name ?: "Asset") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (state.asset != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "Delete asset") }
                },
            )
        },
    ) { padding ->
        val asset = state.asset
        when {
            state.loading -> LoadingState(Modifier.padding(padding))
            asset == null -> EmptyState(Icons.Rounded.Inventory2, "Asset not found", "It may have been deleted on another device.", Modifier.padding(padding))
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SectionCard(contentPadding = PaddingValues(20.dp)) {
                        Text("Current book value", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(asset.currentValue, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { asset.writtenOffFraction },
                            drawStopIndicator = {},
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Written off ${Money.format(asset.amountWrittenOff)} of ${Money.format(asset.cost)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                item { SectionHeader("Details") }
                item {
                    SectionCard {
                        DetailRow("Purchase price", Money.format(asset.cost))
                        DetailRow("In use since", asset.activationDate.formatMedium())
                        DetailRow("Useful life", "${asset.usefulLifeMonths} months (${formatYears(asset.usefulLifeMonths)})")
                        val end = Depreciation.schedule(asset.activationDate, asset.cost, asset.usefulLifeMonths).lastOrNull()?.month
                        end?.let { DetailRow("Fully written off", it.yearMonth.shortLabel()) }
                        DetailRow("Last write-off", asset.lastWrittenOffMonth?.yearMonth?.shortLabel() ?: "None yet")
                        DetailRow("Depreciation account", state.debitAccount?.display ?: "Missing account")
                        DetailRow("Asset account", state.creditAccount?.display ?: "Missing account")
                    }
                }
                item { SectionHeader("Write-offs") }
                if (state.writeoffs.isEmpty()) {
                    item {
                        Text(
                            "The first write-off is posted after the activation month has ended.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                itemsIndexed(state.writeoffs, key = { _, t -> t.id }) { index, tx ->
                    GroupedItem(index, state.writeoffs.size, onClick = { onOpenTransaction(tx.id) }) {
                        AmountRow(tx.date.yearMonth.shortLabel(), tx.totalDebits, supporting = "Posted ${tx.date.formatMedium()}")
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete asset?",
            message = "This stops tracking the asset and deletes all of its automatic depreciation transactions.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { confirmDelete = false; vm.delete() },
            onDismiss = { confirmDelete = false },
        )
    }
    message?.let { MessageDialog("Couldn't delete", it) { vm.message.value = null } }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun formatYears(months: Int): String {
    val years = months / 12
    val rest = months % 12
    return listOfNotNull(
        years.takeIf { it > 0 }?.let { "$it year${if (it == 1) "" else "s"}" },
        rest.takeIf { it > 0 }?.let { "$it month${if (it == 1) "" else "s"}" },
    ).joinToString(" ").ifEmpty { "0 months" }
}
