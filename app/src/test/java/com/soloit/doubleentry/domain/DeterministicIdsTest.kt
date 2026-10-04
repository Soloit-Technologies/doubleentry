package com.soloit.doubleentry.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Expected values were produced by running the original .NET implementation
 * (DefaultAccountPlan.DeterministicId, AssetService.DeterministicWriteoffId/EntryId and
 * EfUserSettingStore.DeriveId). If these drift, devices running the old and new app would
 * create duplicate rows in Supabase instead of converging.
 */
class DeterministicIdsTest {

    @Test
    fun defaultPlanIdsMatchDotNet() {
        assertEquals("dbf18732-1f07-5d23-9947-fb6419573321", DeterministicIds.defaultPlanAccountId(1010))
        assertEquals("00911c82-eedc-5799-a313-7a4cb4595b9d", DeterministicIds.defaultPlanAccountId(1910))
        assertEquals("1338d8ea-f7fe-5b86-b662-814fc7667104", DeterministicIds.defaultPlanAccountId(2010))
        assertEquals("c8924d37-955f-555b-94f0-5d1b24591263", DeterministicIds.defaultPlanAccountId(6010))
        assertEquals("6fa29185-69c3-5569-9a8d-cc575837d9dd", DeterministicIds.defaultPlanAccountId(8920))
    }

    @Test
    fun writeoffIdsMatchDotNet() {
        val asset = "7d0c5b2e-91a4-4f3e-8c6b-2a1f0e9d8c7b"
        val tx = DeterministicIds.writeoffTransactionId(asset, LocalDate.of(2026, 1, 1))
        assertEquals("e4ebe4e9-42a4-409e-21c4-c2c130fd64c9", tx)
        assertEquals("f13162e0-0a4d-651b-386a-2edde935bbda", DeterministicIds.writeoffTransactionId(asset, LocalDate.of(2025, 12, 1)))
        assertEquals("9589bdaf-bf2c-2191-4e0d-60cf4de8a904", DeterministicIds.writeoffEntryId(tx, EntryType.Debit))
        assertEquals("f1417e8a-ccda-dbf3-562d-74131b38b17a", DeterministicIds.writeoffEntryId(tx, EntryType.Credit))
    }

    @Test
    fun writeoffIdIgnoresIdCaseAndMonthDay() {
        val lower = DeterministicIds.writeoffTransactionId("7d0c5b2e-91a4-4f3e-8c6b-2a1f0e9d8c7b", LocalDate.of(2026, 1, 1))
        val upper = DeterministicIds.writeoffTransactionId("7D0C5B2E-91A4-4F3E-8C6B-2A1F0E9D8C7B", LocalDate.of(2026, 1, 31))
        assertEquals(lower, upper)
    }

    @Test
    fun settingIdsMatchDotNet() {
        assertEquals("25f47464-f37a-f0dd-5f50-b9ce11e69688", DeterministicIds.settingId("BalanceSheet.AssetGroups"))
        assertEquals("add5f392-5860-4244-9ba7-8ec25433c76d", DeterministicIds.settingId("Cashflow.CashAccountGroups"))
        assertEquals("c54a9518-4f81-0a74-5ec8-b7d41cc7638e", DeterministicIds.settingId("Invoice.CreditAccountNumber"))
    }

    @Test
    fun defaultPlanUsesItsDeterministicIds() {
        val cash = DefaultAccountPlan.accounts.first { it.number == 1910 }
        assertEquals("00911c82-eedc-5799-a313-7a4cb4595b9d", cash.id)
        assertEquals(DefaultAccountPlan.accounts.size, DefaultAccountPlan.accounts.map { it.number }.toSet().size)
    }
}
