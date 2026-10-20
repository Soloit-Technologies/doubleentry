package com.soloit.doubleentry.domain

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.math.absoluteValue

/** A bank's account export split into a header and the data rows below it. */
data class StatementTable(
    /** The file's header cells, or "Column 1", "Column 2" … when it has none. */
    val headers: List<String>,
    val rows: List<List<String>>,
) {
    /** Identifies the bank's file format, so the columns chosen for it can be reused next time. */
    val signature: String get() = headers.joinToString("|") { it.trim().lowercase() }

    fun cell(row: List<String>, column: Int?): String = column?.let { row.getOrNull(it) }?.trim().orEmpty()

    fun column(index: Int): List<String> = rows.map { cell(it, index) }
}

/** Which column holds what, as indices into [StatementTable.headers]. */
data class StatementColumns(
    val date: Int? = null,
    val text: Int? = null,
    /** Signed amount; null when the file has separate [moneyOut] and [moneyIn] columns instead. */
    val amount: Int? = null,
    val moneyOut: Int? = null,
    val moneyIn: Int? = null,
    /** The account balance after each row, when the file has one. */
    val balance: Int? = null,
    /** The file shows money leaving the account as positive amounts (common on credit card statements). */
    val reversed: Boolean = false,
) {
    val isComplete: Boolean get() = date != null && (amount != null || (moneyOut != null && moneyIn != null))
}

/** One transaction of a bank statement. */
data class StatementLine(
    /** Index of the row in [StatementTable.rows]; stable while the same file is open. */
    val row: Int,
    val date: LocalDate,
    val text: String,
    /** Cents; positive when money came into the account. */
    val amount: Long,
    /** The account balance after this line, in the same sign convention as [amount]. */
    val balance: Long?,
)

data class StatementRead(
    val lines: List<StatementLine>,
    /** Rows without a valid date or a non-zero amount (pending purchases, totals, notes). */
    val skipped: List<Int>,
)

/**
 * Reads CSV/TSV and Excel (.xlsx) account exports from banks. They disagree on nearly everything (delimiter,
 * encoding, header names and language, date and number formats, preamble lines), so the layout
 * is detected from the content; the user can correct the columns before importing.
 */
object BankStatement {

    /** Decodes by byte order mark, else as UTF-8, falling back to Windows-1252 (still used by several Nordic banks). */
    fun decode(bytes: ByteArray): String {
        fun startsWith(vararg bom: Int) = bytes.size >= bom.size && bom.indices.all { bytes[it] == bom[it].toByte() }
        return when {
            startsWith(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            startsWith(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            startsWith(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                String(bytes, Charset.forName("windows-1252"))
            }
        }
    }

    /** Reads a statement file: an Excel workbook's first sheet, or CSV/TSV text in any encoding [decode] knows. */
    fun table(bytes: ByteArray): StatementTable =
        if (Xlsx.isXlsx(bytes)) table(Xlsx.records(bytes)) else table(decode(bytes))

    /** Splits [text] into records, finds the header row (skipping preamble lines) and the data rows below it. */
    fun table(text: String): StatementTable = table(records(text, detectDelimiter(text)))

    private fun table(all: List<List<String>>): StatementTable {
        val records = all.filter { r -> r.any { it.isNotBlank() } }
        val headerIndex = records.take(HEADER_SEARCH_ROWS).indexOfFirst(::isHeader)
        if (headerIndex >= 0) {
            val header = records[headerIndex]
            val rows = records.drop(headerIndex + 1)
            val width = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
            val headers = List(width) { i -> header.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() } ?: "Column ${i + 1}" }
            return StatementTable(headers, rows)
        }
        // No header: data starts at the first record as wide as most records (preamble lines are narrower).
        val width = records.groupingBy { it.size }.eachCount().maxByOrNull { it.value }?.key ?: 0
        val rows = records.dropWhile { it.size != width }
        return StatementTable(List(width) { "Column ${it + 1}" }, rows)
    }

    /** Guesses the columns from the header names, falling back to the content where they say nothing. */
    fun detectColumns(table: StatementTable): StatementColumns {
        val used = mutableSetOf<Int>()
        val dateFormats = table.headers.indices.associateWith { dateFormat(table.column(it)) }
        fun isDate(i: Int) = dateRatio(table.column(i), dateFormats[i]) >= 0.5
        fun isMoney(i: Int) = amountRatio(table.column(i)) >= 0.5

        fun best(role: Role, exactOnly: Boolean = false, accept: (Int) -> Boolean): Int? = table.headers.indices
            .filter { it !in used && accept(it) }
            .mapNotNull { i -> rank(table.headers[i], role.words)?.takeIf { !exactOnly || it % 2 == 0 }?.let { i to it } }
            .minByOrNull { it.second }?.first

        val balance = best(Role.Balance, accept = ::isMoney)?.also { used += it }
        var amount = best(Role.Amount, exactOnly = true, accept = ::isMoney)
        var moneyOut: Int? = null
        var moneyIn: Int? = null
        if (amount == null) {
            val out = best(Role.Out, accept = ::isMoney)
            val into = out?.let { o -> best(Role.In) { it != o && isMoney(it) } }
            if (out != null && into != null) {
                moneyOut = out
                moneyIn = into
            } else {
                amount = best(Role.Amount, accept = ::isMoney)
            }
        }
        listOfNotNull(amount, moneyOut, moneyIn).forEach { used += it }
        val date = (best(Role.Date, accept = ::isDate)
            ?: table.headers.indices.firstOrNull { it !in used && dateRatio(table.column(it), dateFormats[it]) >= 0.8 })
            ?.also { used += it }

        // Content fallbacks for files without (recognizable) headers.
        if (amount == null && moneyOut == null) {
            amount = table.headers.indices.firstOrNull { it !in used && looksLikeMoney(table.column(it)) }?.also { used += it }
        }
        val chainedBalance = if (balance == null && amount != null) {
            table.headers.indices.firstOrNull { it !in used && looksLikeMoney(table.column(it)) && chains(table, amount, it) }
                ?.also { used += it }
        } else {
            null
        }
        val text = best(Role.Text) { true }
            ?: table.headers.indices.filter { it !in used }
                .maxByOrNull { i -> table.column(i).sumOf { cell -> cell.count(Char::isLetter) } }
                ?.takeIf { i -> table.column(i).any { cell -> cell.any(Char::isLetter) } }

        return StatementColumns(date, text, amount, moneyOut, moneyIn, balance ?: chainedBalance)
    }

    /** Reads every row with [columns]; rows without a valid date or a non-zero amount are skipped. */
    fun read(table: StatementTable, columns: StatementColumns): StatementRead {
        if (!columns.isComplete) return StatementRead(emptyList(), table.rows.indices.toList())
        val dates = dateFormat(table.column(columns.date!!))
        val decimal = decimalSeparator(listOfNotNull(columns.amount, columns.moneyOut, columns.moneyIn, columns.balance).flatMap(table::column))
        val sign = if (columns.reversed) -1 else 1
        val lines = mutableListOf<StatementLine>()
        val skipped = mutableListOf<Int>()
        table.rows.forEachIndexed { index, row ->
            val date = dates?.let { parseDate(table.cell(row, columns.date), it) }
            val amount = if (columns.amount != null) {
                parseAmount(table.cell(row, columns.amount), decimal)
            } else {
                val out = table.cell(row, columns.moneyOut)
                val into = table.cell(row, columns.moneyIn)
                val outCents = if (out.isBlank()) 0L else parseAmount(out, decimal)
                val inCents = if (into.isBlank()) 0L else parseAmount(into, decimal)
                // Some banks sign the out column, some don't: its size is what counts.
                if (outCents == null || inCents == null) null else inCents.absoluteValue - outCents.absoluteValue
            }
            if (date == null || amount == null || amount == 0L) {
                skipped += index
            } else {
                val text = table.cell(row, columns.text).replace(whitespace, " ")
                val balance = columns.balance?.let { parseAmount(table.cell(row, it), decimal) }
                lines += StatementLine(index, date, text, amount * sign, balance?.times(sign))
            }
        }
        return StatementRead(lines, skipped)
    }

    /**
     * The balance after the statement's last transaction, or null when the file has no balances.
     * Files are newest or oldest first; when every line has the same date, the balances tell which.
     */
    fun closingBalance(lines: List<StatementLine>): Long? {
        if (lines.isEmpty() || lines.any { it.balance == null }) return null
        val oldestFirst = when {
            lines.first().date != lines.last().date -> lines.first().date < lines.last().date
            else -> lines.zipWithNext().all { (a, b) -> b.balance == a.balance!! + b.amount }
        }
        return if (oldestFirst) lines.last().balance else lines.first().balance
    }

    // ---- CSV -------------------------------------------------------------------------------

    /** RFC 4180 records with an arbitrary [delimiter]: quoted fields may hold delimiters, quotes ("") and line breaks. */
    internal fun records(text: String, delimiter: Char): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var fields = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        val s = text.replace("\r\n", "\n").replace('\r', '\n')
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"' && s.getOrNull(i + 1) == '"') {
                    field.append('"')
                    i++
                } else if (c == '"') {
                    quoted = false
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> if (field.isBlank()) { field.clear(); quoted = true } else field.append(c)
                    delimiter -> { fields += field.toString(); field.clear() }
                    '\n' -> { fields += field.toString(); field.clear(); records += fields; fields = mutableListOf() }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || fields.isNotEmpty()) {
            fields += field.toString()
            records += fields
        }
        return records
    }

    /** The candidate that occurs equally often on the most lines (preamble lines and decimal commas vary). */
    internal fun detectDelimiter(text: String): Char {
        val lines = text.lineSequence().filter { it.isNotBlank() }.take(DELIMITER_SAMPLE_LINES).toList()
        var best = ','
        var bestScore = 0
        for (candidate in delimiters) {
            val counts = lines.map { countOutsideQuotes(it, candidate) }.filter { it > 0 }
            val mode = counts.groupingBy { it }.eachCount().maxWithOrNull(compareBy({ it.value }, { it.key }))?.key ?: continue
            val score = counts.count { it == mode }
            if (score > bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    private fun countOutsideQuotes(line: String, c: Char): Int {
        var quoted = false
        var count = 0
        for (ch in line) {
            if (ch == '"') quoted = !quoted else if (ch == c && !quoted) count++
        }
        return count
    }

    // ---- Headers ---------------------------------------------------------------------------

    private enum class Role(vararg words: String) {
        // Most specific first: the earlier a word, the better the column it names.
        Date(
            "bokföringsdag", "bokföringsdatum", "bokförd", "reskontradatum", "booking date", "posting date", "posted date",
            "date posted", "buchungstag", "buchungsdatum", "bogføringsdato", "bokføringsdato", "kirjauspäivä",
            "transaktionsdag", "transaktionsdatum", "transaction date", "completed date", "started date", "datum", "date",
            "dato", "päivämäärä", "valutadag", "valutadatum", "value date", "wertstellung",
        ),
        Text(
            "beskrivning", "text", "transaktionstext", "rubrik", "meddelande", "specifikation", "description", "beskrivelse",
            "tekst", "payee", "merchant", "name", "namn", "details", "memo", "narrative", "buchungstext", "verwendungszweck",
            "selitys", "mottagare", "avsändare", "referens", "reference", "transaktionstyp", "type",
        ),
        Amount(
            "belopp", "amount", "summa", "beløp", "beløb", "betrag", "määrä", "summe", "transaktionsbelopp",
            "insättningar uttag", "insättning uttag", "value", "montant", "importe",
        ),
        Out("uttag", "debit", "debet", "withdrawal", "withdrawals", "money out", "paid out", "out", "ut", "utbetalning", "soll"),
        In("insättning", "insättningar", "credit", "kredit", "deposit", "deposits", "money in", "paid in", "in", "inbetalning", "haben"),
        Balance("saldo", "balance", "bokfört saldo", "kontostand", "running balance");

        val words: List<String> = words.map(::normalize)
    }

    /** 2·i for a header that is keyword i, 2·i+1 for one that contains it as whole words; null if none. */
    private fun rank(header: String, words: List<String>): Int? {
        val h = normalize(header)
        if (h.isEmpty()) return null
        return words.withIndex().mapNotNull { (i, w) ->
            when {
                h == w -> 2 * i
                " $h ".contains(" $w ") -> 2 * i + 1
                else -> null
            }
        }.minOrNull()
    }

    private fun normalize(text: String): String =
        text.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    private fun isHeader(record: List<String>): Boolean =
        record.count { cell -> Role.entries.any { rank(cell, it.words) != null } } >= 2 &&
            record.none { cell -> dateFormats.any { parseDate(cell, it) != null } }

    // ---- Dates -----------------------------------------------------------------------------

    private val dateFormats: List<DateTimeFormatter> = listOf(
        "uuuu-M-d", "uuuu/M/d", "uuuu.M.d", "uuuuMMdd",
        // Day first before month first: when both fit every row, the European reading wins.
        "d.M.uuuu", "d/M/uuuu", "d-M-uuuu", "M/d/uuuu",
        "d.M.uu", "d/M/uu", "M/d/uu", "uu-MM-dd",
        "d MMM uuuu", "d-MMM-uuuu", "d-MMM-uu", "MMM d uuuu", "MMM d, uuuu", "d MMMM uuuu", "MMMM d, uuuu",
    ).map {
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
    }

    /** A time after the date ("2026-10-03 14:22:01", "2026-10-03T14:22Z") is ignored. */
    private val timeSuffix = Regex("""[ T]+\d{1,2}:\d{2}.*$""")

    internal fun parseDate(cell: String, format: DateTimeFormatter): LocalDate? {
        val s = cell.trim().replace(timeSuffix, "")
        if (s.isEmpty()) return null
        return try {
            LocalDate.parse(s, format)
        } catch (_: Exception) {
            null
        }
    }

    /** The format that reads the most cells of a column (one format per column, so 03/04 can't mean two things). */
    internal fun dateFormat(cells: List<String>): DateTimeFormatter? {
        val sample = cells.filter { it.isNotBlank() }.take(DETECTION_SAMPLE_ROWS)
        if (sample.isEmpty()) return null
        return dateFormats.map { f -> f to sample.count { parseDate(it, f) != null } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first
    }

    private fun dateRatio(cells: List<String>, format: DateTimeFormatter?): Double {
        val sample = cells.filter { it.isNotBlank() }.take(DETECTION_SAMPLE_ROWS)
        if (format == null || sample.isEmpty()) return 0.0
        return sample.count { parseDate(it, format) != null }.toDouble() / sample.size
    }

    // ---- Amounts ---------------------------------------------------------------------------

    /** Digits with grouping and one sign: leading or trailing minus, or parentheses. */
    private val moneyPattern = Regex("""^[+-]?\(?[\d.,' ]*\d[\d.,' ]*\)?-?$""")

    /** A currency code or symbol before or after the number ("SEK -45.00", "1 234,56 kr", "€12"). */
    private val currency = Regex("""^(?:\p{L}{1,3}\.?|\p{Sc})?\s*(.*?)\s*(?:\p{L}{1,3}\.?|\p{Sc})?$""")

    /** Decimals or a sign: amounts rather than row, account or reference numbers. */
    private val moneyHint = Regex("""^[-(]|[.,]\d{1,2}\)?-?$|-$""")

    /** Removes a currency and odd spaces; null unless what is left is a number. */
    private fun cleanAmount(cell: String): String? {
        val s = cell.trim()
            .replace('−', '-').replace('–', '-')
            .replace(' ', ' ').replace(' ', ' ')
        val number = currency.matchEntire(s)?.groupValues?.get(1) ?: return null
        return number.takeIf { it.isNotEmpty() && moneyPattern.matches(it) }
    }

    /**
     * Parses `-1 234,56 kr`, `1,234.56`, `(12.50)`, `12,50-` … into cents. A separator followed by
     * one or two digits is the decimal one; with three digits ("1,234") the column's [decimal] decides.
     */
    internal fun parseAmount(cell: String, decimal: Char): Long? {
        val s = cleanAmount(cell) ?: return null
        val negative = s.contains('-') || s.startsWith('(')
        val last = s.indexOfLast { it == '.' || it == ',' }
        val decimalAt = when {
            last < 0 -> -1
            s.substring(last + 1).count(Char::isDigit) != 3 -> last
            s[last] == decimal -> last
            else -> -1
        }
        val whole = (if (decimalAt >= 0) s.substring(0, decimalAt) else s).filter(Char::isDigit)
        val fraction = if (decimalAt >= 0) s.substring(decimalAt + 1).filter(Char::isDigit) else ""
        return try {
            val cents = Money.fromDecimal(BigDecimal("${whole.ifEmpty { "0" }}.${fraction.ifEmpty { "0" }}"))
            if (negative) -cents else cents
        } catch (_: ArithmeticException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** The decimal separator of a set of amounts: whichever is followed by one or two digits most often. */
    internal fun decimalSeparator(cells: List<String>): Char {
        var dot = 0
        var comma = 0
        for (cell in cells) {
            val s = cleanAmount(cell) ?: continue
            val last = s.indexOfLast { it == '.' || it == ',' }
            if (last < 0) continue
            val digits = s.substring(last + 1).count(Char::isDigit)
            // Three digits after it make the separator a grouping one, which votes for the other.
            val isComma = (s[last] == ',') == (digits != 3)
            if (isComma) comma++ else dot++
        }
        return if (comma > dot) ',' else '.'
    }

    private fun amountRatio(cells: List<String>): Double {
        val sample = cells.filter { it.isNotBlank() }.take(DETECTION_SAMPLE_ROWS)
        if (sample.isEmpty()) return 0.0
        return sample.count { cleanAmount(it) != null }.toDouble() / sample.size
    }

    /** Mostly amounts, and not just whole numbers like row or account numbers: some have decimals or a sign. */
    private fun looksLikeMoney(cells: List<String>): Boolean =
        amountRatio(cells) >= 0.8 && cells.mapNotNull(::cleanAmount).any { moneyHint.containsMatchIn(it) }

    /** True when [balance] moves by the [amount] of each row, oldest or newest first: a running balance. */
    private fun chains(table: StatementTable, amount: Int, balance: Int): Boolean {
        val decimal = decimalSeparator(table.column(amount) + table.column(balance))
        val pairs = table.rows.mapNotNull { row ->
            val a = parseAmount(table.cell(row, amount), decimal) ?: return@mapNotNull null
            val b = parseAmount(table.cell(row, balance), decimal) ?: return@mapNotNull null
            a to b
        }
        if (pairs.size < 2) return false
        val steps = pairs.zipWithNext()
        val forward = steps.count { (p, n) -> n.second - p.second == n.first }
        val backward = steps.count { (p, n) -> p.second - n.second == p.first }
        return maxOf(forward, backward) >= steps.size * 0.8
    }

    private val whitespace = Regex("""\s+""")
    private val delimiters = listOf('\t', ';', ',', '|')
    private const val HEADER_SEARCH_ROWS = 20
    private const val DELIMITER_SAMPLE_LINES = 30
    private const val DETECTION_SAMPLE_ROWS = 200
}
