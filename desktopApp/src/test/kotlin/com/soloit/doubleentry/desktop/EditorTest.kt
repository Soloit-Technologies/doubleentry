package com.soloit.doubleentry.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.soloit.doubleentry.desktop.ui.DesktopTheme
import com.soloit.doubleentry.desktop.ui.LocalApp
import com.soloit.doubleentry.desktop.ui.transactions.TransactionEditor
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.ui.TransactionEditorRoute
import com.soloit.doubleentry.ui.transactions.TransactionEditorViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Entering a transaction the way a desktop user does: typing, with accounts picked by number. */
@OptIn(ExperimentalTestApi::class)
class EditorTest {
    private val desktop = TestDesktop()

    @After
    fun tearDown() = desktop.close()

    @Test
    fun enteringATransactionWithTheKeyboard() = runDesktopComposeUiTest(900, 700) {
        val core = desktop.core
        val accounts = runBlocking { core.ledger.getAccounts() }
        val checking = accounts.first { it.number == 1910 }
        val expense = accounts.first { it.type == com.soloit.doubleentry.domain.AccountType.Expense }
        val vm = TransactionEditorViewModel(core.ledger, core.reconciliations, TransactionEditorRoute())
        var closed = false
        setContent {
            CompositionLocalProvider(LocalApp provides desktop.state) {
                DesktopTheme(dark = false) { TransactionEditor(vm, onClose = { closed = true }) }
            }
        }

        onNodeWithTag("title").performTextInput("Lunch with Anna")
        val firstAccount = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("account-0")))
        firstAccount.performClick()
        firstAccount.performTextInput(expense.number.toString())
        firstAccount.performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("debit-0").performTextInput("12.50")

        val secondAccount = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("account-1")))
        secondAccount.performClick()
        secondAccount.performTextInput("Checking")
        secondAccount.performKeyInput { pressKey(Key.Enter) }
        // Focusing the empty credit fills in what balances the entry.
        onNodeWithTag("credit-1").performClick()
        waitForIdle()

        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 5_000) { closed }

        val saved = runBlocking { core.ledger.getTransactions().first { it.title == "Lunch with Anna" } }
        assertEquals(1250L, saved.totalDebits)
        assertEquals(expense.id, saved.entries.single { it.type == EntryType.Debit }.account.id)
        assertEquals(checking.id, saved.entries.single { it.type == EntryType.Credit }.account.id)
    }
}
