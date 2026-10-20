package com.soloit.doubleentry.desktop

import com.soloit.doubleentry.desktop.ui.AppState
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.TransactionDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

/** The desktop app on a data folder of its own, holding the sample ledger the Android tests use. */
class TestDesktop : AutoCloseable {
    private val dataDir = Files.createTempDirectory("doubleentry-desktop").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val container = DesktopContainer(scope, dataDir)
    val core get() = container.core
    val state = AppState(container, window = null)

    init {
        runBlocking {
            File("../shared/src/jvmTest/resources/sample-data-v3.json").inputStream().use { core.backup.import(it) }
            // A few transactions this month, so the month views have something to show.
            val accounts = core.ledger.getAccounts()
            val checking = accounts.first { it.number == 1910 }.id
            val expense = accounts.first { it.type == AccountType.Expense }.id
            val today = LocalDate.now()
            listOf("Groceries" to 84_50L, "Electricity" to 61_20L, "Market hall" to 23_95L).forEachIndexed { i, (title, amount) ->
                core.ledger.post(
                    TransactionDraft(
                        today.withDayOfMonth((i * 5 + 1).coerceAtMost(today.lengthOfMonth())), title, "",
                        listOf(DraftLine(expense, amount, EntryType.Debit), DraftLine(checking, amount, EntryType.Credit)),
                    ),
                )
            }
        }
    }

    override fun close() {
        scope.cancel()
        dataDir.deleteRecursively()
    }
}
