package com.soloit.doubleentry.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class StatementMatcherTest {
    private val bank = Account("bank", 1930, "Bank", AccountType.Asset)
    private val cash = Account("cash", 1910, "Cash", AccountType.Asset)
    private val savings = Account("savings", 1940, "Savings", AccountType.Asset)
    private val food = Account("food", 5010, "Food", AccountType.Expense)
    private val drinks = Account("drinks", 5020, "Drinks", AccountType.Expense)
    private val household = Account("household", 5030, "Household", AccountType.Expense)
    private val gym = Account("gym", 5040, "Gym", AccountType.Expense)
    private val tax = Account("tax", 8910, "Tax", AccountType.Expense)
    private val salary = Account("salary", 3010, "Salary", AccountType.Revenue)
    private val oct = LocalDate.of(2026, 10, 1)

    /** Lines as (account, cents): positive is a debit, negative a credit. */
    private fun tx(id: String, date: LocalDate, title: String, vararg lines: Pair<Account, Long>) = Transaction(
        id, date, title, "",
        lines.mapIndexed { i, (account, cents) -> Entry("$id-$i", account, kotlin.math.abs(cents), if (cents > 0) EntryType.Debit else EntryType.Credit) },
    )

    private fun line(row: Int, date: LocalDate, text: String, amount: Long) = StatementLine(row, date, text, amount, null)

    private fun guessFor(text: String, amount: Long, vararg history: Transaction): VoucherGuess? =
        StatementMatcher.analyze(bank, listOf(line(0, oct.plusDays(20), text, amount)), history.toList()).new.single().guess

    @Test
    fun linesMatchBookedTransactionsWithinAFewDaysAndEachTransactionOnce() {
        val ica = tx("ica", oct, "ICA", food to 100_00, bank to -100_00)
        val coffee = tx("coffee", oct.plusDays(1), "Coffee", drinks to 35_00, bank to -35_00)
        val analysis = StatementMatcher.analyze(
            bank,
            listOf(
                line(0, oct.plusDays(3), "ICA NARA", -100_00),
                line(1, oct.plusDays(1), "COFFEE", -35_00),
                line(2, oct.plusDays(1), "COFFEE", -35_00),
            ),
            listOf(ica, coffee),
        )
        assertEquals(mapOf(0 to "ica", 1 to "coffee"), analysis.matched.associate { it.line.row to it.transaction.id })
        assertEquals(listOf(2), analysis.new.map { it.line.row })
        assertEquals(emptyList<Transaction>(), analysis.missing)
    }

    @Test
    fun theClosestDateWinsWhenAmountsRepeat() {
        val first = tx("first", oct, "Coffee", drinks to 35_00, bank to -35_00)
        val second = tx("second", oct.plusDays(2), "Coffee", drinks to 35_00, bank to -35_00)
        val analysis = StatementMatcher.analyze(
            bank,
            listOf(line(0, oct.plusDays(2), "COFFEE", -35_00), line(1, oct, "COFFEE", -35_00)),
            listOf(first, second),
        )
        assertEquals(mapOf(0 to "second", 1 to "first"), analysis.matched.associate { it.line.row to it.transaction.id })
    }

    @Test
    fun transactionsInThePeriodThatNoLineMatchesAreReportedMissing() {
        val tooEarly = tx("early", oct.minusDays(10), "ICA", food to 100_00, bank to -100_00)
        val gymFee = tx("gym", oct.plusDays(4), "Gym", gym to 300_00, bank to -300_00)
        val outside = tx("outside", oct.plusDays(30), "Gym", gym to 300_00, bank to -300_00)
        val internal = tx("internal", oct.plusDays(2), "Correction", bank to 50_00, bank to -50_00)
        val analysis = StatementMatcher.analyze(
            bank,
            listOf(line(0, oct, "ICA", -100_00), line(1, oct.plusDays(9), "SALARY", 1_000_00)),
            listOf(tooEarly, gymFee, outside, internal),
        )
        assertEquals(emptyList<StatementMatch>(), analysis.matched) // 10 days apart is too far
        assertEquals(listOf("gym"), analysis.missing.map { it.id })
        assertEquals(oct to oct.plusDays(9), analysis.from to analysis.to)
    }

    @Test
    fun aNewLineIsBookedLikeTheLatestTransactionWithTheSameTitle() {
        val older = tx("older", oct.minusDays(40), "ICA Nära", household to 80_00, bank to -80_00)
        val latest = tx("latest", oct.minusDays(10), "ICA Nära", food to 100_00, bank to -100_00)
        val guess = guessFor("ica  nära", -250_00, older, latest)!!
        assertEquals("latest", guess.source.id)
        assertEquals(listOf(DraftLine("bank", 250_00, EntryType.Credit), DraftLine("food", 250_00, EntryType.Debit)), guess.lines)
    }

    @Test
    fun textsThatDifferOnlyInNumbersShareTheirBooking() {
        val earlier = tx("earlier", oct.minusDays(10), "Kortköp 260915 ICA Nära", food to 100_00, bank to -100_00)
        assertEquals("earlier", guessFor("Kortköp 261003 ICA Nära", -42_00, earlier)?.source?.id)
        assertNull(guessFor("Systembolaget", -42_00, earlier))
    }

    @Test
    fun multiLineBookingsAreScaledToTheNewAmount() {
        val pay = tx("pay", oct.minusDays(30), "LÖN", bank to 25_000_00, tax to 10_000_00, salary to -35_000_00)
        assertEquals(
            listOf(DraftLine("bank", 26_000_00, EntryType.Debit), DraftLine("tax", 10_400_00, EntryType.Debit), DraftLine("salary", 36_400_00, EntryType.Credit)),
            guessFor("LÖN", 26_000_00, pay)!!.lines,
        )
    }

    @Test
    fun roundingIsSettledOnTheLargestLineSoTheEntryBalances() {
        val split = tx("split", oct.minusDays(5), "Shop", food to 100, drinks to 100, household to 100, bank to -300)
        val lines = guessFor("Shop", -100, split)!!.lines
        assertEquals(listOf(100L, 34L, 33L, 33L), lines.map { it.amount })
        assertEquals(lines.filter { it.type == EntryType.Debit }.sumOf { it.amount }, lines.filter { it.type == EntryType.Credit }.sumOf { it.amount })
    }

    @Test
    fun moneyComingBackFlipsTheSides() {
        val purchase = tx("purchase", oct.minusDays(5), "ICA", food to 100_00, bank to -100_00)
        assertEquals(
            listOf(DraftLine("bank", 40_00, EntryType.Debit), DraftLine("food", 40_00, EntryType.Credit)),
            guessFor("ICA", 40_00, purchase)!!.lines,
        )
    }

    @Test
    fun matchedLinesTeachTheBankTextForLaterLines() {
        val groceries = tx("groceries", oct, "Groceries", food to 100_00, bank to -100_00)
        val analysis = StatementMatcher.analyze(
            bank,
            listOf(line(0, oct, "ICA NARA", -100_00), line(1, oct.plusDays(5), "ICA NARA", -80_00)),
            listOf(groceries),
        )
        val guess = analysis.new.single().guess!!
        assertEquals("groceries", guess.source.id)
        assertEquals(listOf(DraftLine("bank", 80_00, EntryType.Credit), DraftLine("food", 80_00, EntryType.Debit)), guess.lines)
    }

    @Test
    fun bookingsFromAnotherAccountOfTheSameTypeAreReused() {
        val paidInCash = tx("cash", oct.minusDays(5), "Kiosk", food to 20_00, cash to -20_00)
        assertEquals(
            listOf(DraftLine("bank", 25_00, EntryType.Credit), DraftLine("food", 25_00, EntryType.Debit)),
            guessFor("Kiosk", -25_00, paidInCash)!!.lines,
        )
        // A transfer between two other bank accounts doesn't say which one this account replaces.
        val transfer = tx("transfer", oct.minusDays(5), "Transfer", savings to 500_00, cash to -500_00)
        assertNull(guessFor("Transfer", -500_00, transfer))
    }

    private val payables = Account("payables", 2440, "Payables", AccountType.Liability)
    private val receivables = Account("receivables", 1510, "Receivables", AccountType.Asset)

    private fun invoice(id: String, amount: Long, due: LocalDate, payable: Boolean = true, paidSoFar: Long = 0) = Invoice(
        id = id, amountDue = amount, invoiceDate = due.minusDays(30), dueDate = due, bookingTransactionId = "booking-$id",
        outstandingAccountId = if (payable) payables.id else receivables.id, outstandingIsCredit = payable,
        isPaid = false, paidDate = null, paymentTransactionId = null, paidFromAccountId = null,
        partialPayments = if (paidSoFar > 0) listOf(InvoicePayment("part-$id", due.minusDays(10), paidSoFar, bank.id)) else emptyList(),
    )

    private fun paymentsFor(lines: List<StatementLine>, vararg invoices: Invoice): Map<Int, String?> =
        StatementMatcher.analyze(bank, lines, emptyList(), invoices.toList()).new.associate { it.line.row to it.invoice?.id }

    @Test
    fun aLinePayingExactlyWhatIsOwedIsLinkedToTheInvoiceInsteadOfTheGuess() {
        val earlier = tx("earlier", oct.minusDays(40), "VATTENFALL", household to 900_00, bank to -900_00)
        val bill = invoice("bill", 1_250_00, oct.plusDays(5))
        val new = StatementMatcher.analyze(bank, listOf(line(0, oct.plusDays(4), "VATTENFALL", -1_250_00)), listOf(earlier), listOf(bill)).new.single()
        assertEquals("bill", new.invoice?.id)
    }

    @Test
    fun invoicesArePaidInTheirDirectionOnlyAndWhatIsLeftAfterInstalmentsCounts() {
        val bill = invoice("bill", 1_000_00, oct.plusDays(10), paidSoFar = 400_00)
        val sale = invoice("sale", 2_000_00, oct.plusDays(10), payable = false)
        val lines = listOf(
            line(0, oct, "BG 5555", -600_00), // the rest of the bill
            line(1, oct, "BG 5555", 600_00), // money in: not a bill payment
            line(2, oct, "CUSTOMER", 2_000_00),
            line(3, oct, "CUSTOMER", -2_000_00), // money out: not a receivable
        )
        assertEquals(mapOf(0 to "bill", 1 to null, 2 to "sale", 3 to null), paymentsFor(lines, bill, sale))
    }

    @Test
    fun eachInvoiceIsPaidOnceByTheLineClosestToItsDueDate() {
        val first = invoice("first", 500_00, oct.plusDays(2))
        val second = invoice("second", 500_00, oct.plusDays(20))
        val lines = listOf(line(0, oct.plusDays(21), "RENT", -500_00), line(1, oct.plusDays(1), "RENT", -500_00), line(2, oct.plusDays(25), "RENT", -500_00))
        assertEquals(mapOf(0 to "second", 1 to "first", 2 to null), paymentsFor(lines, first, second))
    }

    @Test
    fun linesLongBeforeTheInvoiceDateDoNotPayIt() {
        val bill = invoice("bill", 500_00, oct.plusDays(40)) // invoice date oct + 10
        assertEquals(mapOf(0 to null, 1 to "bill"), paymentsFor(listOf(line(0, oct, "X", -500_00), line(1, oct.plusDays(8), "X", -500_00)), bill))
    }

    @Test
    fun canPayChecksDirectionAmountStateAndAccount() {
        val bill = invoice("bill", 1_000_00, oct)
        assertEquals(true, StatementMatcher.canPay(bank.id, -300_00, bill))
        assertEquals(false, StatementMatcher.canPay(bank.id, 300_00, bill))
        assertEquals(false, StatementMatcher.canPay(bank.id, -1_000_01, bill))
        assertEquals(false, StatementMatcher.canPay(payables.id, -300_00, bill))
        assertEquals(false, StatementMatcher.canPay(bank.id, -300_00, bill.copy(isPaid = true)))
    }

    @Test
    fun counterLinesBookTheAmountAgainstOneAccount() {
        assertEquals(
            listOf(DraftLine("bank", 12_00, EntryType.Credit), DraftLine("food", 12_00, EntryType.Debit)),
            StatementMatcher.counterLines("bank", -12_00, "food"),
        )
        assertEquals(
            listOf(DraftLine("bank", 12_00, EntryType.Debit), DraftLine("salary", 12_00, EntryType.Credit)),
            StatementMatcher.counterLines("bank", 12_00, "salary"),
        )
    }
}
