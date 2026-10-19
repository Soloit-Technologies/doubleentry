package com.soloit.doubleentry.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.data.UndoEvent
import com.soloit.doubleentry.desktop.DesktopContainer
import com.soloit.doubleentry.desktop.ui.accounts.AccountsPage
import com.soloit.doubleentry.desktop.ui.accounts.StatementImportWindow
import com.soloit.doubleentry.desktop.ui.assets.AssetsPage
import com.soloit.doubleentry.desktop.ui.invoices.InvoicesPage
import com.soloit.doubleentry.desktop.ui.reports.ReportsPage
import com.soloit.doubleentry.desktop.ui.settings.SettingsPage
import com.soloit.doubleentry.desktop.ui.transactions.TransactionEditorWindow
import com.soloit.doubleentry.desktop.ui.transactions.TransactionsPage
import com.soloit.doubleentry.ui.TransactionEditorRoute
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.Icon
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarSeverity
import io.github.composefluent.component.MenuItem
import io.github.composefluent.component.NavigationDisplayMode
import io.github.composefluent.component.NavigationView
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.rememberNavigationState
import io.github.composefluent.component.Text
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowSwap
import io.github.composefluent.icons.regular.BoxMultiple
import io.github.composefluent.icons.regular.DataTrending
import io.github.composefluent.icons.regular.Receipt
import io.github.composefluent.icons.regular.Settings
import io.github.composefluent.icons.regular.Wallet
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDate

enum class Section(val label: String, val icon: ImageVector) {
    Accounts("Accounts", Icons.Regular.Wallet),
    Activity("Activity", Icons.Regular.ArrowSwap),
    Reports("Reports", Icons.Regular.DataTrending),
    Invoices("Invoices", Icons.Regular.Receipt),
    Assets("Assets", Icons.Regular.BoxMultiple),
    Settings("Settings", Icons.Regular.Settings),
}

/** A statement file dropped or picked for [accountId]. */
data class StatementImportRequest(val accountId: String, val file: File)

/** What the whole window shares: the section shown, the editor and import windows, and app-wide messages. */
class AppState(val container: DesktopContainer, val window: ComposeWindow?) {
    val core get() = container.core
    var section by mutableStateOf(Section.Accounts)
    var editor by mutableStateOf<TransactionEditorRoute?>(null)
    var statementImport by mutableStateOf<StatementImportRequest?>(null)
    var message by mutableStateOf<Pair<String, String>?>(null)

    /** Focused by Ctrl+F: the search box of the page shown. */
    val searchFocus = FocusRequester()

    fun newTransaction(accountId: String? = null, date: LocalDate? = null) {
        editor = TransactionEditorRoute(accountId = accountId, date = date?.toString())
    }

    fun editTransaction(id: String) { editor = TransactionEditorRoute(editId = id) }
    fun duplicateTransaction(id: String) { editor = TransactionEditorRoute(duplicateId = id) }

    fun showError(title: String, text: String?) {
        message = title to (text ?: "Something went wrong.")
    }

    /** Window-wide keyboard shortcuts. */
    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return false
        when (event.key) {
            Key.N -> newTransaction()
            Key.F -> runCatching { searchFocus.requestFocus() }
            Key.One -> section = Section.Accounts
            Key.Two -> section = Section.Activity
            Key.Three -> section = Section.Reports
            Key.Four -> section = Section.Invoices
            Key.Five -> section = Section.Assets
            Key.Comma -> section = Section.Settings
            else -> return false
        }
        return true
    }
}

val LocalApp = staticCompositionLocalOf<AppState> { error("AppState not provided") }

@Composable
fun App(state: AppState) {
    val session by state.core.sessions.active.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize().background(AppColors.window)) {
        // Below 1500 dp the navigation keeps to an icon rail, leaving the width to the panes.
        val wide = maxWidth >= 1500.dp
        NavigationView(
            displayMode = if (wide) NavigationDisplayMode.Left else NavigationDisplayMode.LeftCompact,
            state = rememberNavigationState(initialExpanded = wide),
            menuItems = {
                Section.entries.filter { it != Section.Settings }.forEach { section ->
                    item {
                        MenuItem(
                            selected = state.section == section,
                            onClick = { state.section = section },
                            text = { Text(section.label) },
                            icon = { Icon(section.icon, contentDescription = null) },
                        )
                    }
                }
            },
            footerItems = {
                item {
                    MenuItem(
                        selected = state.section == Section.Settings,
                        onClick = { state.section = Section.Settings },
                        text = { Text(Section.Settings.label) },
                        icon = { Icon(Section.Settings.icon, contentDescription = null) },
                    )
                }
            },
            title = { Text("DoubleEntry") },
            color = AppColors.window,
            border = null,
        ) {
            // Opening another ledger starts every screen over, with its own ViewModels.
            key(session) {
                Box(Modifier.fillMaxSize()) {
                    when (state.section) {
                        Section.Accounts -> AccountsPage()
                        Section.Activity -> TransactionsPage()
                        Section.Reports -> ReportsPage()
                        Section.Invoices -> InvoicesPage()
                        Section.Assets -> AssetsPage()
                        Section.Settings -> SettingsPage()
                    }
                }
            }
        }
        UndoBar(Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    state.editor?.let { route ->
        key(session, route) { TransactionEditorWindow(route, onClose = { state.editor = null }) }
    }
    state.statementImport?.let { request ->
        key(session, request) { StatementImportWindow(request, onClose = { state.statementImport = null }) }
    }
    state.message?.let { (title, text) ->
        ContentDialog(title = title, visible = true, content = { Text(text) }, primaryButtonText = "OK", onButtonClick = { state.message = null })
    }
}

/** "Deleted … · Undo", for a while after something was deleted. */
@Composable
private fun UndoBar(modifier: Modifier) {
    val app = LocalApp.current
    var event by remember { mutableStateOf<UndoEvent?>(null) }
    LaunchedEffect(Unit) { app.core.undo.events.collect { event = it } }
    LaunchedEffect(event) {
        if (event != null) {
            delay(8_000)
            event = null
        }
    }
    val current = event ?: return
    InfoBar(
        title = { Text(current.message) },
        message = {},
        severity = InfoBarSeverity.Informational,
        modifier = modifier.widthIn(max = 520.dp),
        action = {
            Row {
                SubtleButton(onClick = { app.core.undo.run(current); event = null }) { Text("Undo") }
            }
        },
    )
}
