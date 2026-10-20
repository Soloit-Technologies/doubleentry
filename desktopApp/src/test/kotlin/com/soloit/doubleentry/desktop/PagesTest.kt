package com.soloit.doubleentry.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.use
import com.soloit.doubleentry.desktop.ui.App
import com.soloit.doubleentry.desktop.ui.AppColors
import com.soloit.doubleentry.desktop.ui.DesktopTheme
import com.soloit.doubleentry.desktop.ui.LocalApp
import com.soloit.doubleentry.desktop.ui.Section
import com.soloit.doubleentry.desktop.ui.accounts.DiskStatementFile
import com.soloit.doubleentry.desktop.ui.accounts.StatementImport
import com.soloit.doubleentry.desktop.ui.transactions.TransactionEditor
import com.soloit.doubleentry.ui.TransactionEditorRoute
import com.soloit.doubleentry.ui.accounts.StatementImportViewModel
import com.soloit.doubleentry.ui.transactions.TransactionEditorViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import org.jetbrains.skia.EncodedImageFormat
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Renders every page of the desktop app with the sample ledger, in light and dark, plus the
 * transaction editor and a statement import, and saves the pictures to build/screenshots.
 * Fails when something throws while it's drawn.
 */
class PagesTest {
    private val desktop = TestDesktop()
    private val output = File("build/screenshots").apply { mkdirs() }

    @After
    fun tearDown() = desktop.close()

    private fun render(name: String, width: Int = 1440, height: Int = 900, dark: Boolean = false, content: @Composable () -> Unit) {
        ImageComposeScene(width, height) {
            CompositionLocalProvider(LocalApp provides desktop.state) {
                DesktopTheme(dark) { Box(Modifier.fillMaxSize().background(AppColors.window)) { content() } }
            }
        }.use { scene ->
            // Let the ViewModels load from the database and the layout settle.
            var image = scene.render(0)
            repeat(12) { frame ->
                Thread.sleep(150)
                image = scene.render((frame + 1) * 16_000_000L)
            }
            File(output, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Test
    fun everyPageRenders() {
        for (dark in listOf(false, true)) {
            for (section in Section.entries) {
                desktop.state.section = section
                render("${section.name.lowercase()}-${if (dark) "dark" else "light"}", dark = dark) { App(desktop.state) }
            }
        }
        assertTrue(File(output, "accounts-light.png").length() > 0)
    }

    @Test
    fun editorAndImportRender() {
        val core = desktop.core
        val checking = kotlinx.coroutines.runBlocking { core.ledger.getAccounts().first { it.number == 1910 }.id }
        val editor = TransactionEditorViewModel(core.ledger, core.reconciliations, TransactionEditorRoute(accountId = checking, date = LocalDate.now().toString()))
        render("editor", 820, 640) { TransactionEditor(editor, onClose = {}) }

        val csv = File.createTempFile("statement", ".csv").apply {
            writeText(
                """
                Date;Text;Amount;Balance
                ${LocalDate.now().withDayOfMonth(2)};ICA Supermarket;-412,50;100551,85
                ${LocalDate.now().withDayOfMonth(3)};Spotify;-119,00;100432,85
                ${LocalDate.now().withDayOfMonth(4)};Salary October;32500,00;132932,85
                """.trimIndent(),
            )
            deleteOnExit()
        }
        val import = StatementImportViewModel(core.ledger, core.invoices, core.statementImports, core.preferences, core.undo, checking, DiskStatementFile(csv))
        render("import", 1100, 760) { StatementImport(import, onClose = {}) }
    }
}
