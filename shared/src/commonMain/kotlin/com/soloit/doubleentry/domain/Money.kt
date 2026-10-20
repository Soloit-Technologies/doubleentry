package com.soloit.doubleentry.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * Amounts are stored as [Long] minor units (cents) everywhere in the app so that sums and
 * comparisons are exact. This object converts between cents, decimals and display text.
 */
object Money {

    fun toDecimal(cents: Long): BigDecimal = BigDecimal.valueOf(cents, 2)

    fun fromDecimal(value: BigDecimal): Long =
        value.setScale(2, RoundingMode.HALF_UP).unscaledValue().longValueExact()

    /** Formats with grouping and two decimals in the current locale, e.g. `1 234,56`. */
    fun format(cents: Long, locale: Locale = Locale.getDefault()): String =
        formatter(locale, decimals = 2).format(toDecimal(cents))

    /** Like [format] but always shows a sign for non-zero values. */
    fun formatSigned(cents: Long, locale: Locale = Locale.getDefault()): String = when {
        cents > 0 -> "+" + format(cents, locale)
        cents < 0 -> "−" + format(cents.absoluteValue, locale)
        else -> format(0, locale)
    }

    /** Whole units only, for charts and compact tiles. */
    fun formatWhole(cents: Long, locale: Locale = Locale.getDefault()): String =
        formatter(locale, decimals = 0).format(toDecimal(cents).setScale(0, RoundingMode.HALF_UP))

    /** Compact representation for chart axes, e.g. `12k`, `1.2M`. */
    fun formatCompact(cents: Long): String {
        val units = cents / 100.0
        val abs = units.absoluteValue
        return when {
            abs >= 1_000_000 -> trim(units / 1_000_000) + "M"
            abs >= 1_000 -> trim(units / 1_000) + "k"
            else -> trim(units)
        }
    }

    private fun trim(value: Double): String {
        val rounded = if (value.absoluteValue >= 10) Math.round(value).toDouble() else Math.round(value * 10) / 10.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }

    /** Plain editable representation without grouping, using the locale decimal separator. */
    fun toInput(cents: Long, locale: Locale = Locale.getDefault()): String {
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return toDecimal(cents).toPlainString().replace('.', separator)
    }

    /**
     * Parses user input such as `1234,5`, `1 234.50`, `1,234.50` or `1.234,50` into cents.
     * Returns `null` when the text is not a number.
     */
    fun parse(text: String?, locale: Locale = Locale.getDefault()): Long? {
        if (text == null) return null
        var s = text.trim()
            .replace(" ", "")
            .replace(" ", "")
            .replace(" ", "")
            .replace("'", "")
            .replace("−", "-")
        if (s.isEmpty()) return null

        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')
        s = when {
            lastComma >= 0 && lastDot >= 0 -> {
                // Both present: whichever comes last is the decimal separator.
                if (lastComma > lastDot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            }
            lastComma >= 0 -> normalizeSingleSeparator(s, ',', locale)
            lastDot >= 0 -> normalizeSingleSeparator(s, '.', locale)
            else -> s
        }

        return try {
            fromDecimal(BigDecimal(s))
        } catch (_: NumberFormatException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun normalizeSingleSeparator(s: String, sep: Char, locale: Locale): String {
        val count = s.count { it == sep }
        if (count > 1) return s.replace(sep.toString(), "") // only grouping separators
        val digitsAfter = s.length - s.indexOf(sep) - 1
        val localeDecimal = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val isDecimal = digitsAfter != 3 || localeDecimal == sep
        return if (isDecimal) s.replace(sep, '.') else s.replace(sep.toString(), "")
    }

    private data class FormatterKey(val locale: Locale, val decimals: Int)

    private val formatters = ThreadLocal<MutableMap<FormatterKey, NumberFormat>>()

    private fun formatter(locale: Locale, decimals: Int): NumberFormat {
        val map = formatters.get() ?: mutableMapOf<FormatterKey, NumberFormat>().also { formatters.set(it) }
        return map.getOrPut(FormatterKey(locale, decimals)) {
            NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = decimals
                maximumFractionDigits = decimals
                roundingMode = RoundingMode.HALF_UP
            }
        }
    }
}
