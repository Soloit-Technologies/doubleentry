package com.soloit.doubleentry.ui.accounts

import com.soloit.doubleentry.ui.components.FigureRow
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.domain.ReconciliationStatus
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.label
import com.soloit.doubleentry.ui.LocalAppContainer
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
import com.soloit.doubleentry.ui.components.color
import com.soloit.doubleentry.ui.components.fitToWidth
import com.soloit.doubleentry.ui.components.isLargeFont
import com.soloit.doubleentry.ui.components.scrollItemToTop
import com.soloit.doubleentry.ui.theme.AppTheme
import com.soloit.doubleentry.ui.theme.tabular
import java.time.YearMonth
import kotlinx.coroutines.launch

private const val SearchKey = "search"

@Composable
fun AccountsScreen(
    onOpenAccount: (String, YearMonth) -> Unit,
    onOpenNetWorth: (YearMonth) -> Unit,
    onNewAccount: () -> Unit,
    onOpenSettings: () -> Unit,
    onRestoreFromCloud: () -> Unit,
) {
    val vm = appViewModel { AccountsViewModel(it.ledger, it.reconciliations, it.startupComplete) }
    val state by vm.state.collectAsStateWithLifecycle()
    val sync by LocalAppContainer.current.sync.status.collectAsStateWithLifecycle()
    // Collapsed section names, comma separated (a plain string survives process death).
    var collapsed by rememberSaveable { mutableStateOf("") }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Settings") } },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            if (state.hasAccounts) {
                ExtendedFloatingActionButton(onClick = onNewAccount, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Account") })
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            state = listState,
            contentPadding = ScreenPadding,
        ) {
            item { MonthSelector(state.month, vm::setMonth, Modifier.padding(bottom = 12.dp)) }
            when {
                state.loading -> item { LoadingState() }
                !state.hasAccounts -> item {
                    WelcomeCard(
                        waitingForCloud = !state.startupComplete || sync.isSyncing,
                        seeding = state.seeding,
                        onUseDefaultPlan = vm::seedDefaultPlan,
                        onStartFromScratch = onNewAccount,
                        onRestoreFromCloud = if (sync.available && !sync.isSignedIn) onRestoreFromCloud else null,
                    )
                }
                else -> {
                    item { NetWorthHero(state, onClick = { onOpenNetWorth(state.month) }) }
                    item(key = SearchKey) {
                        SearchField(
                            state.query,
                            vm::setQuery,
                            "Search accounts",
                            Modifier.padding(top = 16.dp, bottom = 4.dp),
                            onActivate = { scope.launch { listState.scrollItemToTop(SearchKey) } },
                        )
                    }
                    if (state.sections.isEmpty()) {
                        item { EmptyState(Icons.Rounded.SearchOff, "No matching accounts", "Try a different name or number.") }
                    }
                    val collapsedTypes = collapsed.split(',').filter { it.isNotEmpty() }.toSet()
                    state.sections.forEach { section ->
                        val isCollapsed = section.type.name in collapsedTypes
                        item(key = "h-${section.type}") {
                            SectionTitle(section, isCollapsed) {
                                val next = if (isCollapsed) collapsedTypes - section.type.name else collapsedTypes + section.type.name
                                collapsed = next.joinToString(",")
                            }
                        }
                        if (!isCollapsed) {
                            itemsIndexed(section.rows, key = { _, row -> row.account.id }) { index, row ->
                                GroupedItem(index, section.rows.size, onClick = { onOpenAccount(row.account.id, state.month) }) {
                                    AccountRow(row, state.reconciliations[row.account.id])
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetWorthHero(state: AccountsUiState, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val gradient = Brush.linearGradient(listOf(scheme.primary, lerp(scheme.primary, Color.Black, 0.28f)))
    Surface(onClick = onClick, shape = MaterialTheme.shapes.extraLarge, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.background(gradient).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Net worth · ${state.month.label()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onPrimary.copy(alpha = 0.85f),
                    modifier = Modifier.weight(1f),
                )
                // Says where a tap goes: the trend lives in Reports.
                Text("Trend", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = scheme.onPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                Money.format(state.netWorth),
                style = MaterialTheme.typography.displaySmall.tabular(),
                color = scheme.onPrimary,
                maxLines = 1,
                autoSize = fitToWidth(MaterialTheme.typography.displaySmall),
            )
            Spacer(Modifier.height(8.dp))
            Surface(shape = CircleShape, color = scheme.onPrimary.copy(alpha = 0.16f), contentColor = scheme.onPrimary) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (state.netWorthChange >= 0) Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown,
                        null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("${Money.formatSigned(state.netWorthChange)} this month", style = MaterialTheme.typography.labelMedium.tabular(), maxLines = 1, autoSize = fitToWidth(MaterialTheme.typography.labelMedium))
                }
            }
            Spacer(Modifier.height(16.dp))
            FigureRow { item ->
                HeroFigure("Income", state.income, item)
                HeroFigure("Spending", state.spending, item)
                HeroFigure("Saved", state.income - state.spending, item)
            }
        }
    }
}

@Composable
private fun HeroFigure(label: String, cents: Long, modifier: Modifier = Modifier) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Column(modifier.clip(MaterialTheme.shapes.medium).background(onPrimary.copy(alpha = 0.12f)).padding(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = onPrimary.copy(alpha = 0.8f))
        Text(Money.formatWhole(cents), style = MaterialTheme.typography.titleSmall.tabular(), color = onPrimary, maxLines = 1, autoSize = fitToWidth(MaterialTheme.typography.titleSmall))
    }
}

@Composable
private fun SectionTitle(section: AccountSectionUi, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onToggle).padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(section.type.color()))
        Spacer(Modifier.width(8.dp))
        Text(section.type.pluralLabel, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        MoneyText(section.total, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(
            Icons.Rounded.ExpandMore,
            if (collapsed) "Expand" else "Collapse",
            modifier = Modifier.rotate(if (collapsed) 0f else 180f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccountRow(row: AccountBalanceUi, reconciliation: ReconciliationStatus?) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AccountTypeAvatar(row.account.type)
        Spacer(Modifier.width(14.dp))
        TextAndFigures(
            figures = {
                MoneyText(row.closing, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (row.change != 0L) {
                    Text(
                        Money.formatSigned(row.change),
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            spacing = 0.dp,
        ) {
            Text(row.account.name, style = MaterialTheme.typography.bodyLarge, maxLines = if (isLargeFont()) 2 else 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${row.account.number}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (reconciliation != null) {
                    Spacer(Modifier.width(6.dp))
                    if (reconciliation.inSync) {
                        Icon(Icons.Rounded.Verified, "Reconciled", Modifier.size(14.dp), tint = AppTheme.finance.positive)
                    } else {
                        Icon(Icons.Rounded.Warning, "Changed since reconciliation", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeCard(
    waitingForCloud: Boolean,
    seeding: Boolean,
    onUseDefaultPlan: () -> Unit,
    onStartFromScratch: () -> Unit,
    onRestoreFromCloud: (() -> Unit)?,
) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.animateContentSize()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Savings, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Text("Welcome to DoubleEntry", style = MaterialTheme.typography.headlineSmall)
            if (waitingForCloud) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Checking your cloud data…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(
                    "Every transaction moves money between at least two accounts. Start with a ready-made personal chart of accounts, or build your own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onRestoreFromCloud != null) {
                    // Offered first: someone moving from the previous app should restore, not start over.
                    FilledTonalButton(onClick = onRestoreFromCloud, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.CloudDownload, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Sign in to restore from the cloud")
                    }
                }
                Button(onClick = onUseDefaultPlan, enabled = !seeding, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (seeding) "Creating accounts…" else "Use the default personal plan")
                }
                OutlinedButton(onClick = onStartFromScratch, modifier = Modifier.fillMaxWidth()) { Text("Start from scratch") }
                Text(
                    "The default plan has about 60 accounts (cash, bank, loans, salary, housing, food …). You can rename or delete them later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
