package com.soloit.doubleentry.ui.assets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.domain.Asset
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.formatMedium
import com.soloit.doubleentry.ui.appViewModel
import com.soloit.doubleentry.ui.components.EmptyState
import com.soloit.doubleentry.ui.components.IconAvatar
import com.soloit.doubleentry.ui.components.LoadingState
import com.soloit.doubleentry.ui.components.MoneyText
import com.soloit.doubleentry.ui.components.ScreenPadding
import com.soloit.doubleentry.ui.components.SearchField
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.TextAndFigures
import com.soloit.doubleentry.ui.components.isLargeFont
import com.soloit.doubleentry.ui.components.scrollItemToTop
import com.soloit.doubleentry.ui.theme.tabular
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val SearchKey = "search"

@Composable
fun AssetsScreen(onOpen: (String) -> Unit, onNew: () -> Unit, onOpenSettings: () -> Unit) {
    val vm = appViewModel { AssetsViewModel(it.assets) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Assets") },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Settings") } },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Asset") })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), state = listState, contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                state.loading -> item { LoadingState() }
                state.totalCost == 0L && state.query.isBlank() -> item {
                    EmptyState(
                        Icons.Rounded.Inventory2,
                        "No assets yet",
                        "Track things like a car, furniture or equipment. DoubleEntry writes off their value every month automatically.",
                    )
                }
                else -> {
                    item { AssetsSummary(state) }
                    item(key = SearchKey) {
                        SearchField(
                            state.query,
                            vm::setQuery,
                            "Search assets",
                            Modifier.padding(vertical = 4.dp),
                            onActivate = { scope.launch { listState.scrollItemToTop(SearchKey) } },
                        )
                    }
                    items(state.assets, key = { it.id }) { asset -> AssetCard(asset) { onOpen(asset.id) } }
                }
            }
        }
    }
}

@Composable
private fun AssetsSummary(state: AssetsUiState) {
    SectionCard(contentPadding = PaddingValues(20.dp), containerColor = MaterialTheme.colorScheme.primaryContainer) {
        val on = MaterialTheme.colorScheme.onPrimaryContainer
        Text("Book value", style = MaterialTheme.typography.labelLarge, color = on.copy(alpha = 0.8f))
        MoneyText(state.totalValue, style = MaterialTheme.typography.displaySmall, color = on, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        val written = state.totalCost - state.totalValue
        val percent = if (state.totalCost == 0L) 0 else (written * 100.0 / state.totalCost).roundToInt()
        Text(
            "Purchased for ${Money.format(state.totalCost)} · $percent% written off",
            style = MaterialTheme.typography.bodyMedium.tabular(),
            color = on,
        )
    }
}

@Composable
private fun AssetCard(asset: Asset, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconAvatar(Icons.Rounded.Inventory2, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                TextAndFigures(
                    figures = {
                        MoneyText(asset.currentValue, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("of ${Money.format(asset.cost)}", style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    spacing = 0.dp,
                ) {
                    Text(asset.name, style = MaterialTheme.typography.titleMedium, maxLines = if (isLargeFont()) 2 else 1, overflow = TextOverflow.Ellipsis)
                    Text("Since ${asset.activationDate.formatMedium()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { asset.writtenOffFraction },
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${(asset.writtenOffFraction * 100).roundToInt()}% depreciated over ${asset.usefulLifeMonths} months",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
