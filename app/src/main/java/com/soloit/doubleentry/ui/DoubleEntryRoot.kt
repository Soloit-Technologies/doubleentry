package com.soloit.doubleentry.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.soloit.doubleentry.AppContainer
import com.soloit.doubleentry.system.LaunchRequest
import com.soloit.doubleentry.system.LaunchTarget
import com.soloit.doubleentry.ui.recurring.RecurringEditorScreen
import com.soloit.doubleentry.ui.recurring.RecurringListScreen
import com.soloit.doubleentry.ui.accounts.AccountEditorScreen
import com.soloit.doubleentry.ui.accounts.AccountLedgerScreen
import com.soloit.doubleentry.ui.accounts.AccountsScreen
import com.soloit.doubleentry.ui.accounts.StatementImportScreen
import com.soloit.doubleentry.ui.assets.AssetDetailScreen
import com.soloit.doubleentry.ui.assets.AssetEditorScreen
import com.soloit.doubleentry.ui.assets.AssetsScreen
import com.soloit.doubleentry.ui.invoices.InvoiceDetailScreen
import com.soloit.doubleentry.ui.invoices.InvoiceEditorScreen
import com.soloit.doubleentry.ui.invoices.InvoicesScreen
import com.soloit.doubleentry.ui.reports.ReportsScreen
import com.soloit.doubleentry.ui.settings.BalanceSheetSettingsScreen
import com.soloit.doubleentry.ui.settings.CashflowSettingsScreen
import com.soloit.doubleentry.ui.settings.CloudSyncScreen
import com.soloit.doubleentry.ui.settings.DataManagementScreen
import com.soloit.doubleentry.ui.settings.IncomeStatementSettingsScreen
import com.soloit.doubleentry.ui.settings.SyncBanner
import com.soloit.doubleentry.ui.settings.InvoiceSettingsScreen
import com.soloit.doubleentry.ui.settings.SettingsScreen
import com.soloit.doubleentry.ui.transactions.TransactionDetailScreen
import com.soloit.doubleentry.ui.transactions.TransactionEditorScreen
import com.soloit.doubleentry.ui.transactions.TransactionsScreen
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

// ---- Routes ---------------------------------------------------------------------------------

@Serializable data object AccountsRoute
@Serializable data object TransactionsRoute
@Serializable data object ReportsRoute
@Serializable data object InvoicesRoute
@Serializable data object AssetsRoute

@Serializable data class AccountLedgerRoute(val accountId: String, val year: Int, val month: Int)
@Serializable data class AccountEditorRoute(val accountId: String? = null)
/** Imports the bank statement file at [uri] (a document picked by the user) into [accountId]. */
@Serializable data class StatementImportRoute(val accountId: String, val uri: String)
@Serializable data class TransactionDetailRoute(val id: String)
/** [accountId] and [date] (ISO) prefill a new transaction, e.g. one started from an account's ledger. */
@Serializable
data class TransactionEditorRoute(
    val editId: String? = null,
    val duplicateId: String? = null,
    val accountId: String? = null,
    val date: String? = null,
)
@Serializable data class InvoiceDetailRoute(val id: String)
@Serializable data class InvoiceEditorRoute(val editId: String? = null)
@Serializable data class AssetDetailRoute(val id: String)
@Serializable data object AssetEditorRoute
@Serializable data object SettingsRoute
@Serializable data object CloudSyncRoute
@Serializable data object DataManagementRoute
@Serializable data object BalanceSheetSettingsRoute
@Serializable data object IncomeStatementSettingsRoute
@Serializable data object CashflowSettingsRoute
@Serializable data object InvoiceSettingsRoute
@Serializable data object RecurringListRoute
@Serializable data class RecurringEditorRoute(val ruleId: String? = null, val fromTransactionId: String? = null)

private data class TopLevel(val route: Any, val type: KClass<*>, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val topLevel = listOf(
    TopLevel(AccountsRoute, AccountsRoute::class, "Accounts", Icons.Outlined.AccountBalanceWallet, Icons.Rounded.AccountBalanceWallet),
    TopLevel(TransactionsRoute, TransactionsRoute::class, "Activity", Icons.Outlined.SwapHoriz, Icons.Rounded.SwapHoriz),
    TopLevel(ReportsRoute, ReportsRoute::class, "Reports", Icons.Outlined.Insights, Icons.Rounded.Insights),
    TopLevel(InvoicesRoute, InvoicesRoute::class, "Invoices", Icons.AutoMirrored.Outlined.ReceiptLong, Icons.AutoMirrored.Rounded.ReceiptLong),
    TopLevel(AssetsRoute, AssetsRoute::class, "Assets", Icons.Outlined.Inventory2, Icons.Rounded.Inventory2),
)

// ---- DI helpers -----------------------------------------------------------------------------

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** Creates (or returns the existing) ViewModel scoped to the current destination. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalAppContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

// ---- Root -----------------------------------------------------------------------------------

@Composable
fun DoubleEntryRoot(container: AppContainer, launchRequest: LaunchRequest? = null, onLaunchHandled: () -> Unit = {}) {
    CompositionLocalProvider(LocalAppContainer provides container) {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val destination = entry?.destination
        val showBar = destination == null || topLevel.any { destination.hasRoute(it.type) }
        val snackbar = remember { SnackbarHostState() }
        val syncStatus by container.sync.status.collectAsStateWithLifecycle()

        // Deletes elsewhere close their screen at once, so the undo snackbar lives here.
        LaunchedEffect(Unit) {
            container.undo.events.collect { event ->
                snackbar.currentSnackbarData?.dismiss()
                launch {
                    val result = snackbar.showSnackbar(event.message, actionLabel = "Undo", duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) container.undo.run(event)
                }
            }
        }

        Scaffold(
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar, if (showBar) Modifier else Modifier.navigationBarsPadding().imePadding()) },
            bottomBar = {
                AnimatedVisibility(
                    visible = showBar,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    Column {
                        // Sync trouble shows on every tab: unsynced changes are easy to lose track of.
                        AnimatedVisibility(visible = syncStatus.attention != null) {
                            SyncBanner(
                                status = syncStatus,
                                onOpen = { nav.navigate(CloudSyncRoute) },
                                onDismissReplaced = container.sync::dismissReplaced,
                            )
                        }
                        NavigationBar {
                            topLevel.forEach { item ->
                                val selected = destination?.hierarchy?.any { it.hasRoute(item.type) } == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { nav.navigateTopLevel(item.route) },
                                    icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = AccountsRoute,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 16 } },
                exitTransition = { fadeOut(tween(150)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = { fadeOut(tween(150)) + slideOutHorizontally(tween(220)) { it / 16 } },
            ) {
                // Ignore repeated taps that would otherwise pop past the start destination.
                val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }
                val settings: () -> Unit = { nav.navigate(SettingsRoute) }

                composable<AccountsRoute> {
                    AccountsScreen(
                        onOpenAccount = { id, month -> nav.navigate(AccountLedgerRoute(id, month.year, month.monthValue)) },
                        onNewAccount = { nav.navigate(AccountEditorRoute()) },
                        onOpenSettings = settings,
                        onRestoreFromCloud = { nav.navigate(CloudSyncRoute) },
                    )
                }
                composable<AccountLedgerRoute> { e ->
                    val route = e.toRoute<AccountLedgerRoute>()
                    AccountLedgerScreen(
                        route = route,
                        onBack = back,
                        onEdit = { nav.navigate(AccountEditorRoute(route.accountId)) },
                        onOpenTransaction = { nav.navigate(TransactionDetailRoute(it)) },
                        onNewTransaction = { date -> nav.navigate(TransactionEditorRoute(accountId = route.accountId, date = date.toString())) },
                        onImportStatement = { uri -> nav.navigate(StatementImportRoute(route.accountId, uri.toString())) },
                    )
                }
                composable<AccountEditorRoute> { e -> AccountEditorScreen(e.toRoute<AccountEditorRoute>().accountId, onDone = back) }
                composable<StatementImportRoute> { e ->
                    StatementImportScreen(
                        route = e.toRoute<StatementImportRoute>(),
                        onBack = back,
                        onOpenTransaction = { nav.navigate(TransactionDetailRoute(it)) },
                    )
                }

                composable<TransactionsRoute> {
                    TransactionsScreen(
                        onOpen = { nav.navigate(TransactionDetailRoute(it)) },
                        onNew = { nav.navigate(TransactionEditorRoute()) },
                        onOpenSettings = settings,
                    )
                }
                composable<TransactionDetailRoute> { e ->
                    val id = e.toRoute<TransactionDetailRoute>().id
                    TransactionDetailScreen(
                        transactionId = id,
                        onBack = back,
                        onEdit = { nav.navigate(TransactionEditorRoute(editId = id)) },
                        onDuplicate = { nav.navigate(TransactionEditorRoute(duplicateId = id)) },
                        onRepeat = { nav.navigate(RecurringEditorRoute(fromTransactionId = id)) },
                        onOpenRecurring = { nav.navigate(RecurringEditorRoute(ruleId = it)) },
                    )
                }
                composable<TransactionEditorRoute> { e -> TransactionEditorScreen(e.toRoute<TransactionEditorRoute>(), onDone = back) }

                composable<ReportsRoute> {
                    ReportsScreen(
                        onOpenSettings = settings,
                        onOpenBalanceSheetSettings = { nav.navigate(BalanceSheetSettingsRoute) },
                        onOpenIncomeSettings = { nav.navigate(IncomeStatementSettingsRoute) },
                        onOpenCashflowSettings = { nav.navigate(CashflowSettingsRoute) },
                    )
                }

                composable<InvoicesRoute> {
                    InvoicesScreen(
                        onOpen = { nav.navigate(InvoiceDetailRoute(it)) },
                        onNew = { nav.navigate(InvoiceEditorRoute()) },
                        onOpenSettings = settings,
                    )
                }
                composable<InvoiceDetailRoute> { e ->
                    val id = e.toRoute<InvoiceDetailRoute>().id
                    InvoiceDetailScreen(
                        invoiceId = id,
                        onBack = back,
                        onEdit = { nav.navigate(InvoiceEditorRoute(id)) },
                        onOpenTransaction = { nav.navigate(TransactionDetailRoute(it)) },
                    )
                }
                composable<InvoiceEditorRoute> { e -> InvoiceEditorScreen(e.toRoute<InvoiceEditorRoute>().editId, onDone = back) }

                composable<AssetsRoute> {
                    AssetsScreen(
                        onOpen = { nav.navigate(AssetDetailRoute(it)) },
                        onNew = { nav.navigate(AssetEditorRoute) },
                        onOpenSettings = settings,
                    )
                }
                composable<AssetDetailRoute> { e ->
                    AssetDetailScreen(
                        assetId = e.toRoute<AssetDetailRoute>().id,
                        onBack = back,
                        onOpenTransaction = { nav.navigate(TransactionDetailRoute(it)) },
                    )
                }
                composable<AssetEditorRoute> { AssetEditorScreen(onDone = back) }

                composable<SettingsRoute> {
                    SettingsScreen(
                        onBack = back,
                        onCloudSync = { nav.navigate(CloudSyncRoute) },
                        onDataManagement = { nav.navigate(DataManagementRoute) },
                        onBalanceSheet = { nav.navigate(BalanceSheetSettingsRoute) },
                        onIncomeStatement = { nav.navigate(IncomeStatementSettingsRoute) },
                        onCashflow = { nav.navigate(CashflowSettingsRoute) },
                        onInvoices = { nav.navigate(InvoiceSettingsRoute) },
                        onRecurring = { nav.navigate(RecurringListRoute) },
                    )
                }
                composable<CloudSyncRoute> { CloudSyncScreen(onBack = back) }
                composable<DataManagementRoute> { DataManagementScreen(onBack = back) }
                composable<BalanceSheetSettingsRoute> { BalanceSheetSettingsScreen(onBack = back) }
                composable<IncomeStatementSettingsRoute> { IncomeStatementSettingsScreen(onBack = back) }
                composable<CashflowSettingsRoute> { CashflowSettingsScreen(onBack = back) }
                composable<InvoiceSettingsRoute> { InvoiceSettingsScreen(onBack = back) }
                composable<RecurringListRoute> {
                    RecurringListScreen(
                        onBack = back,
                        onOpen = { nav.navigate(RecurringEditorRoute(ruleId = it)) },
                        onNew = { nav.navigate(RecurringEditorRoute()) },
                    )
                }
                composable<RecurringEditorRoute> { e -> RecurringEditorScreen(e.toRoute<RecurringEditorRoute>(), onDone = back) }
            }
        }

        // Shortcut, notification and widget taps.
        LaunchedEffect(launchRequest) {
            val request = launchRequest ?: return@LaunchedEffect
            when (request.target) {
                LaunchTarget.NewTransaction -> nav.navigate(TransactionEditorRoute())
                LaunchTarget.NewInvoice -> nav.navigate(InvoiceEditorRoute())
                LaunchTarget.Invoices -> nav.navigateTopLevel(InvoicesRoute)
                LaunchTarget.Invoice -> {
                    nav.navigateTopLevel(InvoicesRoute)
                    request.id?.let { nav.navigate(InvoiceDetailRoute(it)) }
                }
            }
            onLaunchHandled()
        }
    }
}

private fun NavHostController.navigateTopLevel(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
