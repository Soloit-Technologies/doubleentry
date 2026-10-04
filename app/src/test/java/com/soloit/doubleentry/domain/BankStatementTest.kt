package com.soloit.doubleentry.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BankStatementTest {

    private fun parse(text: String): Pair<StatementTable, StatementColumns> {
        val table = BankStatement.table(text)
        return table to BankStatement.detectColumns(table)
    }

    @Test
    fun swedbankExportWithPreambleQuotedCommasAndRunningBalance() {
        val (table, columns) = parse(
            """
            * Transaktioner Period 2026-09-01 – 2026-09-30 Skapad 2026-10-01 10:15 CEST
            Radnummer,Clearingnummer,Kontonummer,Produkt,Valuta,Bokföringsdag,Transaktionsdag,Valutadag,Referens,Beskrivning,Belopp,Bokfört saldo
            1,8327-9,1234567890,Privatkonto,SEK,2026-09-30,2026-09-29,2026-09-30,"ICA NARA, SODERMALM","ICA NARA, SODERMALM",-245.50,12754.50
            2,8327-9,1234567890,Privatkonto,SEK,2026-09-25,2026-09-25,2026-09-25,LÖN,LÖN,25000.00,13000.00
            3,8327-9,1234567890,Privatkonto,SEK,2026-09-01,2026-09-01,2026-09-01,HYRA,HYRA,-12000.00,-12000.00
            """.trimIndent(),
        )
        assertEquals(StatementColumns(date = 5, text = 9, amount = 10, balance = 11), columns)

        val read = BankStatement.read(table, columns)
        assertEquals(emptyList<Int>(), read.skipped)
        assertEquals(
            listOf(
                StatementLine(0, LocalDate.of(2026, 9, 30), "ICA NARA, SODERMALM", -245_50, 12_754_50),
                StatementLine(1, LocalDate.of(2026, 9, 25), "LÖN", 25_000_00, 13_000_00),
                StatementLine(2, LocalDate.of(2026, 9, 1), "HYRA", -12_000_00, -12_000_00),
            ),
            read.lines,
        )
        // Newest first: the closing balance is on the first row.
        assertEquals(12_754_50L, BankStatement.closingBalance(read.lines))
    }

    @Test
    fun nordeaExportInWindows1252WithDecimalCommasAndPendingRows() {
        val bytes = """
            Bokföringsdag;Belopp;Avsändare;Mottagare;Namn;Rubrik;Saldo;Valuta
            Reserverat;-89,00;;;;Kortköp 261003 Pressbyrån;;SEK
            2026/10/02;-1 245,50;;;;Kortköp 261001 ICA Nära Årsta;8 754,50;SEK
            2026/10/01;10 000,00;;;;Lön;10 000,00;SEK
        """.trimIndent().toByteArray(charset("windows-1252"))

        val (table, columns) = parse(BankStatement.decode(bytes))
        assertEquals(StatementColumns(date = 0, text = 5, amount = 1, balance = 6), columns)

        val read = BankStatement.read(table, columns)
        assertEquals(listOf(0), read.skipped)
        assertEquals(listOf("Kortköp 261001 ICA Nära Årsta", "Lön"), read.lines.map { it.text })
        assertEquals(listOf(-1_245_50L, 10_000_00L), read.lines.map { it.amount })
        assertEquals(LocalDate.of(2026, 10, 2), read.lines.first().date)
        assertEquals(8_754_50L, BankStatement.closingBalance(read.lines))
    }

    @Test
    fun tabSeparatedDebitAndCreditColumnsWithDayFirstDates() {
        val (table, columns) = parse(
            "Date\tDescription\tDebit\tCredit\tBalance\n" +
                "03/10/2026\tTESCO STORES 1234\t45.20\t\t954.80\n" +
                "01/10/2026\tSALARY ACME LTD\t\t1,000.00\t1,000.00\n",
        )
        assertEquals(StatementColumns(date = 0, text = 1, moneyOut = 2, moneyIn = 3, balance = 4), columns)

        val lines = BankStatement.read(table, columns).lines
        assertEquals(listOf(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 1)), lines.map { it.date })
        assertEquals(listOf(-45_20L, 1_000_00L), lines.map { it.amount })
    }

    @Test
    fun monthFirstDatesAreUsedWhenDayFirstDoesNotFit() {
        val (table, columns) = parse("Date,Payee,Amount\n10/13/2026,Coffee,-4.50\n10/01/2026,Refund,12.00\n")
        assertEquals(
            listOf(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 1)),
            BankStatement.read(table, columns).lines.map { it.date },
        )
    }

    @Test
    fun filesWithoutHeadersAreReadFromTheirContent() {
        val (table, columns) = parse("2026-10-03;ICA NARA;-245,50;8 754,50\n2026-10-01;LÖN;9 000,00;9 000,00\n")
        assertEquals(listOf("Column 1", "Column 2", "Column 3", "Column 4"), table.headers)
        assertEquals(StatementColumns(date = 0, text = 1, amount = 2, balance = 3), columns)
        assertEquals(2, BankStatement.read(table, columns).lines.size)
    }

    @Test
    fun reversedSignsFlipAmountsAndBalances() {
        val (table, columns) = parse("Datum,Text,Belopp,Saldo\n2026-10-03,NETFLIX,129.00,129.00\n")
        val line = BankStatement.read(table, columns.copy(reversed = true)).lines.single()
        assertEquals(-129_00L, line.amount)
        assertEquals(-129_00L, line.balance)
    }

    @Test
    fun timesAfterDatesAreIgnored() {
        val (table, columns) = parse(
            "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance\n" +
                "CARD_PAYMENT,Current,2026-10-02 18:01:12,2026-10-03 09:14:55,Spotify,-119.00,0.00,SEK,COMPLETED,881.00\n",
        )
        assertEquals(3, columns.date)
        assertEquals(LocalDate.of(2026, 10, 3), BankStatement.read(table, columns).lines.single().date)
    }

    @Test
    fun amountsInEveryCommonNotation() {
        assertEquals(-1_234_56L, BankStatement.parseAmount("-1 234,56 kr", ','))
        assertEquals(-1_234_56L, BankStatement.parseAmount("−1 234,56", ','))
        assertEquals(-12_50L, BankStatement.parseAmount("(12.50)", '.'))
        assertEquals(-12_50L, BankStatement.parseAmount("12,50-", ','))
        assertEquals(1_234_56L, BankStatement.parseAmount("1,234.56", '.'))
        assertEquals(1_234_56L, BankStatement.parseAmount("1.234,56", ','))
        assertEquals(1_000_00L, BankStatement.parseAmount("SEK 1 000", ','))
        assertEquals(12_00L, BankStatement.parseAmount("€12", '.'))
        // Three digits after a separator: the column's decimal separator decides.
        assertEquals(1_234_00L, BankStatement.parseAmount("1,234", '.'))
        assertNull(BankStatement.parseAmount("8327-9", '.'))
        assertNull(BankStatement.parseAmount("2026-10-03", '.'))
        assertNull(BankStatement.parseAmount("Kortköp 231003 ICA", '.'))
        assertNull(BankStatement.parseAmount("", '.'))
    }

    @Test
    fun decimalSeparatorIsDetectedPerColumn() {
        assertEquals(',', BankStatement.decimalSeparator(listOf("1 245,50", "10 000,00")))
        assertEquals('.', BankStatement.decimalSeparator(listOf("45.20", "1,000.00")))
        assertEquals('.', BankStatement.decimalSeparator(listOf("1,000", "2,500")))
        assertEquals(',', BankStatement.decimalSeparator(listOf("1.000", "2.500")))
    }

    @Test
    fun quotedFieldsKeepDelimitersQuotesAndLineBreaks() {
        val records = BankStatement.records("a,\"b,c\",\"say \"\"hi\"\"\"\r\n1,\"two\nlines\",3\n", ',')
        assertEquals(listOf(listOf("a", "b,c", "say \"hi\""), listOf("1", "two\nlines", "3")), records)
    }

    @Test
    fun delimiterIgnoresDecimalCommasAndPreambleLines() {
        assertEquals(';', BankStatement.detectDelimiter("Datum;Text;Belopp\n2026-10-01;A;-1,50\n2026-10-02;B;2,00\n"))
        assertEquals(',', BankStatement.detectDelimiter("Export 2026\nDatum,Text,Belopp\n2026-10-01,A,-1.50\n"))
        assertEquals('\t', BankStatement.detectDelimiter("Date\tText\tAmount\n2026-10-01\tA, B\t-1,50\n"))
    }

    @Test
    fun byteOrderMarksSelectTheEncoding() {
        val text = "Datum;Text\n2026-10-01;Café"
        assertEquals(text, BankStatement.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, BankStatement.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray()))
        assertEquals(text, BankStatement.decode(text.toByteArray()))
    }

    @Test
    fun closingBalanceOfOldestFirstFilesAndSingleDayFiles() {
        val day = LocalDate.of(2026, 10, 1)
        val oldestFirst = listOf(
            StatementLine(0, day, "A", 100_00, 100_00),
            StatementLine(1, day.plusDays(1), "B", -30_00, 70_00),
        )
        assertEquals(70_00L, BankStatement.closingBalance(oldestFirst))
        // All on one day: the balances show the order.
        val newestFirst = listOf(StatementLine(0, day, "B", -30_00, 70_00), StatementLine(1, day, "A", 100_00, 100_00))
        assertEquals(70_00L, BankStatement.closingBalance(newestFirst))
        assertNull(BankStatement.closingBalance(listOf(StatementLine(0, day, "A", 1, null))))
    }
}
