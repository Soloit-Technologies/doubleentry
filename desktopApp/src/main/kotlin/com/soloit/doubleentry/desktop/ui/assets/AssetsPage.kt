package com.soloit.doubleentry.desktop.ui.assets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.desktop.ui.LocalApp
import com.soloit.doubleentry.desktop.ui.components.Card
import com.soloit.doubleentry.desktop.ui.components.CellText
import com.soloit.doubleentry.desktop.ui.components.DataTable
import com.soloit.doubleentry.desktop.ui.components.DetailRow
import com.soloit.doubleentry.desktop.ui.components.EmptyState
import com.soloit.doubleentry.desktop.ui.components.Layer
import com.soloit.doubleentry.desktop.ui.components.MoneyText
import com.soloit.doubleentry.desktop.ui.components.PaneHeader
import com.soloit.doubleentry.desktop.ui.components.SearchBox
import com.soloit.doubleentry.desktop.ui.components.Splitter
import com.soloit.doubleentry.desktop.ui.components.TableColumn
import com.soloit.doubleentry.desktop.ui.components.display
import com.soloit.doubleentry.desktop.ui.components.rememberPaneWidth
import com.soloit.doubleentry.desktop.ui.components.rememberViewModel
import com.soloit.doubleentry.domain.Asset
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.ui.assets.AssetDetailViewModel
import com.soloit.doubleentry.ui.assets.AssetsViewModel
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Button
import io.github.composefluent.component.ProgressBar
import io.github.composefluent.component.Text

@Composable
fun AssetsPage() {
    val app = LocalApp.current
    val core = app.core
    val vm = rememberViewModel { AssetsViewModel(core.assets) }
    val state by vm.state.collectAsState()
    var selected by remember { mutableStateOf<String?>(null) }
    val detailWidth = rememberPaneWidth(380.dp, min = 300.dp)

    Row(Modifier.fillMaxSize().padding(end = 8.dp, bottom = 8.dp)) {
        Layer(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize()) {
                PaneHeader("Assets", subtitle = "Worth ${Money.format(state.totalValue)} of ${Money.format(state.totalCost)} paid")
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f))
                    SearchBox(state.query, vm::setQuery, "Search assets", Modifier.width(280.dp), focus = app.searchFocus)
                }
                val columns = remember {
                    listOf(
                        TableColumn<Asset>("Asset", weight = 1f) { CellText(it.name) },
                        TableColumn("In use since", width = 120.dp) { CellText(it.activationDate.toString(), secondary = true) },
                        TableColumn("Written off", width = 160.dp) { ProgressBar(it.writtenOffFraction, Modifier.width(140.dp)) },
                        TableColumn("Cost", width = 120.dp, alignEnd = true) { MoneyText(it.cost) },
                        TableColumn("Value", width = 120.dp, alignEnd = true) { MoneyText(it.currentValue) },
                    )
                }
                DataTable(
                    rows = state.assets,
                    key = { it.id },
                    columns = columns,
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                    selected = selected,
                    onSelect = { selected = it.id },
                )
                if (!state.loading && state.assets.isEmpty()) {
                    Text(
                        "No assets. Assets that are written off over time are added in the Android app for now; they appear here once synced.",
                        color = FluentTheme.colors.text.text.secondary,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
        Splitter(detailWidth, growsRight = false)
        Layer(Modifier.width(detailWidth.value).fillMaxHeight()) {
            val id = selected
            if (id == null) EmptyState("No asset selected", "Select an asset to see how it's written off.") else AssetDetail(id, onClosed = { selected = null })
        }
    }
}

@Composable
private fun AssetDetail(id: String, onClosed: () -> Unit) {
    val app = LocalApp.current
    val core = app.core
    val vm = rememberViewModel(id) { AssetDetailViewModel(core.assets, core.ledger, core.undo, id) }
    val state by vm.state.collectAsState()
    val message by vm.message.collectAsState()
    LaunchedEffect(message) { message?.let { app.showError("Asset", it); vm.message.value = null } }
    val deleted by vm.deleted.collectAsState()
    LaunchedEffect(deleted) { if (deleted) onClosed() }
    val asset = state.asset ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(asset.name, style = FluentTheme.typography.subtitle)
        MoneyText(asset.currentValue, style = FluentTheme.typography.title)
        ProgressBar(asset.writtenOffFraction, Modifier.fillMaxWidth())
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                DetailRow("Cost", Money.format(asset.cost))
                DetailRow("In use since", asset.activationDate.formatMedium())
                DetailRow("Useful life", "${asset.usefulLifeMonths / 12} years ${asset.usefulLifeMonths % 12} months")
                DetailRow("Written off", Money.format(asset.amountWrittenOff))
                state.debitAccount?.let { DetailRow("Expense account", it.display()) }
                state.creditAccount?.let { DetailRow("Asset account", it.display()) }
            }
        }
        if (state.writeoffs.isNotEmpty()) {
            Text("Write-offs", style = FluentTheme.typography.bodyStrong)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    state.writeoffs.forEach { tx ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(tx.date.formatMedium(), modifier = Modifier.weight(1f))
                            MoneyText(tx.totalDebits)
                        }
                    }
                }
            }
        }
        Button(onClick = { vm.delete() }) { Text("Delete asset") }
    }
}
