package com.soloit.doubleentry.data

import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.Entry
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Transaction
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringWriter
import java.time.LocalDate

class CsvExportTest {
    private val bank = Account("bank", 1910, "Bank", AccountType.Asset)
    private val food = Account("food", 5010, "Food, drinks", AccountType.Expense)

    @Test
    fun transactionsAreOneRowPerLineWithQuotingAndPlainAmounts() {
        val tx = Transaction(
            "t1", LocalDate.of(2026, 9, 1), "ICA \"Maxi\"", "Weekly\nshop",
            listOf(Entry("e2", bank, 1_234_50, EntryType.Credit), Entry("e1", food, 1_234_50, EntryType.Debit)),
        )
        val out = StringWriter()
        CsvExport.transactions(listOf(tx), out)
        assertEquals(
            "Date,Transaction,Title,Description,Account number,Account name,Account type,Debit,Credit\r\n" +
                "2026-09-01,t1,\"ICA \"\"Maxi\"\"\",\"Weekly\nshop\",5010,\"Food, drinks\",Expense,1234.50,\r\n" +
                "2026-09-01,t1,\"ICA \"\"Maxi\"\"\",\"Weekly\nshop\",1910,Bank,Asset,,1234.50\r\n",
            out.toString(),
        )
    }

    @Test
    fun balancesIncludeEveryAccountAndNegativeAmounts() {
        val out = StringWriter()
        CsvExport.balances(listOf(food, bank), mapOf("bank" to -5_00L), LocalDate.of(2026, 10, 2), out)
        assertEquals(
            "As of,Account number,Account name,Account type,Balance\r\n" +
                "2026-10-02,1910,Bank,Asset,-5.00\r\n" +
                "2026-10-02,5010,\"Food, drinks\",Expense,0.00\r\n",
            out.toString(),
        )
    }
}
