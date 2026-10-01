package com.soloit.doubleentry.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class MoneyTest {
    private val sv = Locale.forLanguageTag("sv-SE")
    private val en = Locale.US

    @Test
    fun parsesSwedishAndEnglishInput() {
        assertEquals(123_456L, Money.parse("1234,56", sv))
        assertEquals(123_456L, Money.parse("1 234,56", sv))
        assertEquals(123_456L, Money.parse("1 234,56", sv))
        assertEquals(123_456L, Money.parse("1234.56", sv))
        assertEquals(123_456L, Money.parse("1,234.56", en))
        assertEquals(123_456L, Money.parse("1.234,56", sv))
        assertEquals(1_000_000L, Money.parse("10000", sv))
        assertEquals(50L, Money.parse("0,5", sv))
    }

    @Test
    fun threeDigitsAfterSeparatorDependsOnLocale() {
        assertEquals(123_400L, Money.parse("1,234", en)) // grouping in English
        assertEquals(123L, Money.parse("1,234", sv)) // decimal comma in Swedish, rounded to cents
        assertEquals(123_456_700L, Money.parse("1.234.567", sv))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Money.parse("", sv))
        assertNull(Money.parse("abc", sv))
        assertNull(Money.parse(null, sv))
    }

    @Test
    fun formatsAndRoundTrips() {
        assertEquals("1,234.56", Money.format(123_456, en))
        assertEquals("-12.30", Money.format(-1_230, en))
        assertEquals("+5.00", Money.formatSigned(500, en))
        assertEquals("1234,56", Money.toInput(123_456, sv))
        assertEquals(123_456L, Money.parse(Money.toInput(123_456, sv), sv))
        assertEquals("12k", Money.formatCompact(1_200_000))
        assertEquals("1.5M", Money.formatCompact(150_000_000))
    }
}
