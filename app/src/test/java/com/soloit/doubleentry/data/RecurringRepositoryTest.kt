package com.soloit.doubleentry.data

import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.DeterministicIds
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Frequency
import com.soloit.doubleentry.domain.LedgerException
import com.soloit.doubleentry.domain.RecurringRule
import com.soloit.doubleentry.testing.TestLedger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class RecurringRepositoryTest {
    private val t = TestLedger()
    private lateinit var bank: Account
    private lateinit var rent: Account

    @After
    fun tearDown() = t.close()

    private suspend fun rentRule(start: LocalDate, id: String = "rule-1"): RecurringRule {
        bank = t.account(1910, "Bank", AccountType.Asset)
        rent = t.account(5010, "Rent", AccountType.Expense)
        return RecurringRule(
            id = id,
            title = "Rent",
            description = "Monthly rent",
            lines = listOf(DraftLine(rent.id, 9_000_00, EntryType.Debit), DraftLine(bank.id, 9_000_00, EntryType.Credit)),
            frequency = Frequency.Monthly,
            startDate = start,
        )
    }

    @Test
    fun savingPostsEveryDueOccurrenceOnceWithDeterministicIds() = runBlocking<Unit> {
        val rule = rentRule(LocalDate.of(2026, 7, 31))
        val today = LocalDate.of(2026, 10, 2)
        t.recurring.save(rule, today)

        val posted = t.ledger.getTransactions().map { it.date }
        // The 31st clamps to the 30th in September.
        assertEquals(listOf(LocalDate.of(2026, 7, 31), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 30)), posted)
        val ids = t.ledger.getTransactions().map { it.id }
        assertEquals(posted.map { DeterministicIds.recurringTransactionId(rule.id, it) }, ids)
        assertEquals(LocalDate.of(2026, 9, 30), t.recurring.getRule(rule.id)!!.lastPostedDate)

        // Running again (e.g. on the next app start) posts nothing new.
        assertEquals(0, t.recurring.process(today))
        assertEquals(1, t.recurring.process(LocalDate.of(2026, 10, 31)))
        assertEquals(-4 * 9_000_00L, t.ledger.getBalances(LocalDate.of(2026, 10, 31))[bank.id])
    }

    @Test
    fun aDeletedOccurrenceIsNotPostedAgain() = runBlocking<Unit> {
        val rule = rentRule(LocalDate.of(2026, 9, 1))
        t.recurring.save(rule, LocalDate.of(2026, 9, 1))
        val first = t.ledger.getTransactions().single()
        t.ledger.deleteTransaction(first.id)

        // Even a device that never saw the advanced position skips it: the id exists as a tombstone.
        t.settings.put(RecurringRepository.PREFIX + rule.id, RecurringRepository.encode(rule))
        assertEquals(0, t.recurring.process(LocalDate.of(2026, 9, 2)))
        assertEquals(0, t.liveTransactionCount())
    }

    @Test
    fun ruleMadeFromATransactionStartsAfterIt() = runBlocking<Unit> {
        val rule = rentRule(LocalDate.of(2026, 9, 1)).copy(lastPostedDate = LocalDate.of(2026, 9, 1))
        t.recurring.save(rule, LocalDate.of(2026, 9, 20))
        assertEquals(0, t.liveTransactionCount())
        t.recurring.process(LocalDate.of(2026, 10, 1))
        assertEquals(listOf(LocalDate.of(2026, 10, 1)), t.ledger.getTransactions().map { it.date })
    }

    @Test
    fun editingKeepsThePostingPositionSoNothingIsBackFilled() = runBlocking<Unit> {
        val rule = rentRule(LocalDate.of(2026, 8, 1))
        t.recurring.save(rule, LocalDate.of(2026, 9, 15))
        assertEquals(2, t.liveTransactionCount())

        // Moving the day of month must not post August and September again on the 25th.
        t.recurring.save(rule.copy(startDate = LocalDate.of(2026, 8, 25)), LocalDate.of(2026, 9, 15))
        assertEquals(2, t.liveTransactionCount())
        t.recurring.process(LocalDate.of(2026, 9, 30))
        assertEquals(LocalDate.of(2026, 9, 25), t.ledger.getTransactions().last().date)
    }

    @Test
    fun pausedRulesPostNothingAndCatchUpWhenResumed() = runBlocking<Unit> {
        val rule = rentRule(LocalDate.of(2026, 9, 1)).copy(paused = true)
        t.recurring.save(rule, LocalDate.of(2026, 10, 2))
        assertEquals(0, t.liveTransactionCount())
        t.settings.put(RecurringRepository.PREFIX + rule.id, RecurringRepository.encode(rule.copy(paused = false)))
        assertEquals(2, t.recurring.process(LocalDate.of(2026, 10, 2)))
    }

    @Test
    fun ruleWithADeletedAccountIsSkippedWithoutAdvancing() = runBlocking<Unit> {
        val rule = rentRule(LocalDate.of(2026, 9, 1))
        val spare = t.account(1990, "Spare", AccountType.Asset)
        t.recurring.save(rule.copy(lines = listOf(DraftLine(rent.id, 100, EntryType.Debit), DraftLine(spare.id, 100, EntryType.Credit))), LocalDate.of(2026, 8, 1))
        t.ledger.deleteAccount(spare.id)

        assertEquals(0, t.recurring.process(LocalDate.of(2026, 9, 2)))
        assertNull(t.recurring.getRule(rule.id)!!.lastPostedDate)
    }

    @Test
    fun invalidRulesAreRejected() = runBlocking<Unit> {
        val rule = rentRule(LocalDate.of(2026, 9, 1))
        assertThrows(LedgerException::class.java) { runBlocking { t.recurring.save(rule.copy(title = " ")) } }
        assertThrows(LedgerException::class.java) { runBlocking { t.recurring.save(rule.copy(endDate = LocalDate.of(2026, 1, 1))) } }
        assertThrows(LedgerException::class.java) {
            runBlocking { t.recurring.save(rule.copy(lines = listOf(DraftLine(rent.id, 100, EntryType.Debit), DraftLine(bank.id, 99, EntryType.Credit)))) }
        }
    }

    @Test
    fun ruleForTransactionFindsTheRuleThatPostedIt() = runBlocking<Unit> {
        val rule = rentRule(LocalDate.of(2026, 9, 1))
        t.recurring.save(rule, LocalDate.of(2026, 9, 1))
        val posted = t.ledger.getTransactions().single()
        assertEquals(rule.id, t.recurring.ruleForTransaction(posted.id)?.id)
        assertNull(t.recurring.ruleForTransaction("something-else"))
    }
}
