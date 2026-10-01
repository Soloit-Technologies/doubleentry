package com.soloit.doubleentry.data

import com.soloit.doubleentry.domain.AccountGroup
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.CashflowSettings
import com.soloit.doubleentry.domain.EntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Formats shared with the .NET app: synced `user_settings` values and JSON backups. */
class CompatibilityTest {

    @Test
    fun groupsAreEncodedLikeSystemTextJson() {
        val json = SettingsRepository.encodeGroups(listOf(AccountGroup("Bank & savings", listOf("19", "1930")), AccountGroup("Empty", emptyList())))
        // .NET reads PascalCase properties case-sensitively and needs "Prefixes" even when empty.
        assertEquals("""[{"Name":"Bank & savings","Prefixes":["19","1930"]},{"Name":"Empty","Prefixes":[]}]""", json)
    }

    @Test
    fun readsSettingsWrittenByDotNet() {
        // Exact output of JsonSerializer.Serialize(new List<GroupDto> { ... }) in the .NET app.
        val dotNet = """[{"Name":"Bankkonton & spar","Prefixes":["19","1930"]}]"""
        assertEquals(listOf(AccountGroup("Bankkonton & spar", listOf("19", "1930"))), SettingsRepository.decodeGroups(dotNet))

        val values = mapOf(
            SettingsRepository.Keys.INVOICE_CREDIT_ACCOUNT to "2440",
            SettingsRepository.Keys.INVOICE_PAYMENT_ACCOUNTS to "[1910,2410]",
            SettingsRepository.Keys.TAX_PREFIXES to """["891"]""",
        )
        val invoice = SettingsRepository.invoiceFrom(values)
        assertEquals(2440, invoice.creditAccountNumber)
        assertEquals(listOf(1910, 2410), invoice.paymentAccountNumbers)
        assertEquals(listOf("891"), SettingsRepository.incomeStatementFrom(values).taxPrefixes)
    }

    @Test
    fun cashGroupsFallBackToDefaultsOnlyWhenUnset() {
        assertEquals(CashflowSettings.DEFAULT_CASH_GROUPS, SettingsRepository.cashflowFrom(emptyMap()).cashGroups)
        assertEquals(emptyList<AccountGroup>(), SettingsRepository.cashflowFrom(mapOf(SettingsRepository.Keys.CASH_GROUPS to "[]")).cashGroups)
        assertEquals(CashflowSettings.DEFAULT_CASH_GROUPS, SettingsRepository.cashflowFrom(mapOf(SettingsRepository.Keys.CASH_GROUPS to "not json")).cashGroups)
    }

    @Test
    fun decodesMauiSampleBackup() {
        val text = javaClass.classLoader!!.getResource("sample-data-v3.json")!!.readText()
        val backup = BackupService.json.decodeFromString(BackupService.LedgerBackup.serializer(), text)
        assertEquals(3, backup.schemaVersion)
        assertEquals(25, backup.accounts.size)
        assertEquals(AccountType.Asset, backup.accounts.first().type)
        val opening = backup.transactions.first()
        assertEquals("Opening Balance", opening.title)
        assertEquals(2_850_000L, opening.entries.first().amount) // 28500.0 → cents
        assertEquals(EntryType.Debit, opening.entries.first().entryType)
        assertEquals(opening.entries.filter { it.entryType == EntryType.Debit }.sumOf { it.amount }, opening.entries.filter { it.entryType == EntryType.Credit }.sumOf { it.amount })
    }

    @Test
    fun backupWritesDecimalsAndEnumNames() {
        val dto = BackupService.EntryDto(1910, 123_456, EntryType.Credit)
        val json = BackupService.json.encodeToString(BackupService.EntryDto.serializer(), dto).replace("\\s".toRegex(), "")
        assertEquals("""{"accountNumber":1910,"amount":1234.56,"entryType":"Credit"}""", json)
        // Older exports may contain enum ordinals.
        val legacy = BackupService.json.decodeFromString(BackupService.EntryDto.serializer(), """{"accountNumber":1,"amount":5,"entryType":1}""")
        assertEquals(EntryType.Credit, legacy.entryType)
        assertEquals(500L, legacy.amount)
    }

    @Test
    fun optionalSettingsAreOmittedWhenNull() {
        val json = BackupService.json.encodeToString(BackupService.SettingsDto.serializer(), BackupService.SettingsDto())
        assertEquals("{}", json.replace("\\s".toRegex(), ""))
        assertNull(BackupService.json.decodeFromString(BackupService.SettingsDto.serializer(), "{}").invoiceCreditAccountNumber)
    }
}
