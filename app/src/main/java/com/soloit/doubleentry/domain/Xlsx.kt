package com.soloit.doubleentry.domain

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.floor

/**
 * Reads the cells of an Excel workbook's (.xlsx) first visible sheet as text, the way the CSV
 * reader would see them: date-formatted numbers become ISO dates, other numbers plain decimals.
 * Formulas give their cached result. The old binary .xls format is not supported.
 */
object Xlsx {

    /** True for a zip archive, which is what an .xlsx file is. */
    fun isXlsx(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    /** The first visible sheet's rows; blank rows are kept, missing cells are empty strings. */
    fun records(bytes: ByteArray): List<List<String>> {
        val files = unzip(bytes)
        fun file(name: String) = files[name] ?: throw IOException("The workbook has no $name.")

        val workbook = Workbook().also { parse(file(WORKBOOK), it) }
        val relations = files[WORKBOOK_RELS]?.let { r -> Relations().also { parse(r, it) }.targets }.orEmpty()
        val sheet = workbook.sheets.firstNotNullOfOrNull { relations[it] }?.let(::resolve) ?: "xl/worksheets/sheet1.xml"
        val strings = files[SHARED_STRINGS]?.let { s -> SharedStrings().also { parse(s, it) }.strings }.orEmpty()
        val dateStyles = files[STYLES]?.let { s -> Styles().also { parse(s, it) }.dateStyles }.orEmpty()

        val cells = Sheet(strings, dateStyles, if (workbook.date1904) EPOCH_1904 else EPOCH_1900)
        parse(file(sheet), cells)
        return cells.rows
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.removePrefix("/")
                if (!name.endsWith(".xml") && !name.endsWith(".rels")) continue
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_ENTRY_BYTES) throw IOException("The workbook is too large to be a bank statement.")
                }
                files[name] = out.toByteArray()
            }
        }
        return files
    }

    /** Relationship targets are relative to xl/ unless absolute. */
    private fun resolve(target: String): String = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"

    private fun parse(xml: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        factory.newSAXParser().parse(ByteArrayInputStream(xml), handler)
    }

    /** Element name without a namespace prefix ("x:c" → "c"). */
    private fun local(qName: String) = qName.substringAfter(':')

    private fun Attributes.get(name: String): String? =
        (0 until length).firstOrNull { local(getQName(it)) == name }?.let(::getValue)

    private class Workbook : DefaultHandler() {
        /** Relationship ids of the visible sheets, in tab order. */
        val sheets = mutableListOf<String>()
        var date1904 = false

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (local(qName)) {
                "workbookPr" -> date1904 = attributes.get("date1904").let { it == "1" || it == "true" }
                "sheet" -> if (attributes.get("state").let { it == null || it == "visible" }) attributes.get("id")?.let(sheets::add)
            }
        }
    }

    private class Relations : DefaultHandler() {
        val targets = mutableMapOf<String, String>()

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            if (local(qName) == "Relationship") {
                val id = attributes.get("Id") ?: return
                val target = attributes.get("Target") ?: return
                targets[id] = target
            }
        }
    }

    /** Each `<si>`'s text runs joined; phonetic hints (`<rPh>`) are left out. */
    private class SharedStrings : DefaultHandler() {
        val strings = mutableListOf<String>()
        private val current = StringBuilder()
        private var inText = false
        private var inPhonetic = false

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (local(qName)) {
                "si" -> current.clear()
                "rPh" -> inPhonetic = true
                "t" -> inText = !inPhonetic
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (local(qName)) {
                "si" -> strings += current.toString()
                "rPh" -> inPhonetic = false
                "t" -> inText = false
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inText) current.appendRange(ch, start, start + length)
        }
    }

    /** Indices of the cell formats (`<cellXfs>`) that show a number as a date. */
    private class Styles : DefaultHandler() {
        val dateStyles = mutableSetOf<Int>()
        private val customDateFormats = mutableSetOf<Int>()
        private var inCellFormats = false
        private var index = 0

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (local(qName)) {
                "numFmt" -> {
                    val id = attributes.get("numFmtId")?.toIntOrNull() ?: return
                    if (isDateFormat(attributes.get("formatCode").orEmpty())) customDateFormats += id
                }
                "cellXfs" -> inCellFormats = true
                "xf" -> if (inCellFormats) {
                    val id = attributes.get("numFmtId")?.toIntOrNull() ?: 0
                    if (id in BUILT_IN_DATE_FORMATS || id in customDateFormats) dateStyles += index
                    index++
                }
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (local(qName) == "cellXfs") inCellFormats = false
        }

        /** Has a day or year once quoted text, escapes and [colour]/[locale] sections are removed ("m" alone may mean minutes). */
        private fun isDateFormat(code: String): Boolean {
            val bare = code.replace(Regex("\"[^\"]*\"|\\\\.|\\[[^]]*]"), "").lowercase()
            return bare.any { it == 'd' || it == 'y' }
        }
    }

    private class Sheet(
        private val strings: List<String>,
        private val dateStyles: Set<Int>,
        private val epoch: LocalDate,
    ) : DefaultHandler() {
        val rows = mutableListOf<List<String>>()
        private var row: MutableList<String>? = null
        private var column = -1
        private var type: String? = null
        private var style = 0
        private val value = StringBuilder()
        private var inValue = false
        private var inInlineString = false

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (local(qName)) {
                "row" -> {
                    // Rows without data are omitted from the file; keep the gaps like empty CSV lines.
                    val number = attributes.get("r")?.toIntOrNull()
                    if (number != null) while (rows.size < number - 1) rows += emptyList<String>()
                    row = mutableListOf()
                    column = -1
                }
                "c" -> {
                    column = attributes.get("r")?.let(::columnIndex) ?: (column + 1)
                    type = attributes.get("t")
                    style = attributes.get("s")?.toIntOrNull() ?: 0
                    value.clear()
                }
                "v" -> inValue = true
                "is" -> inInlineString = true
                "t" -> inValue = inInlineString
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (local(qName)) {
                "row" -> row?.let(rows::add).also { row = null }
                "c" -> row?.let { cells ->
                    while (cells.size <= column) cells += ""
                    cells[column] = text(value.toString())
                }
                "v", "t" -> inValue = false
                "is" -> inInlineString = false
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inValue) value.appendRange(ch, start, start + length)
        }

        private fun text(raw: String): String = when (type) {
            "s" -> raw.trim().toIntOrNull()?.let(strings::getOrNull).orEmpty()
            "inlineStr", "str", "d" -> raw
            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
            "e" -> ""
            else -> number(raw.trim())
        }

        private fun number(raw: String): String {
            val n = raw.toBigDecimalOrNull() ?: return raw
            if (style in dateStyles) {
                val days = floor(n.toDouble()).toLong()
                // Serial 60 is the 29 February 1900 that Lotus 1-2-3 invented; Excel kept it.
                return epoch.plusDays(if (epoch == EPOCH_1900 && days < 61) days + 1 else days).toString()
            }
            // Binary floating point: 15 significant digits, as Excel shows (123.45000000000001 → 123.45).
            return n.round(MathContext(15)).stripTrailingZeros().let { if (it.signum() == 0) BigDecimal.ZERO else it }.toPlainString()
        }

        /** "AB12" → 27. */
        private fun columnIndex(reference: String): Int? {
            val letters = reference.takeWhile { it.isLetter() }.uppercase()
            if (letters.isEmpty()) return null
            return letters.fold(0) { acc, c -> acc * 26 + (c - 'A' + 1) } - 1
        }
    }

    private const val WORKBOOK = "xl/workbook.xml"
    private const val WORKBOOK_RELS = "xl/_rels/workbook.xml.rels"
    private const val SHARED_STRINGS = "xl/sharedStrings.xml"
    private const val STYLES = "xl/styles.xml"
    private const val MAX_ENTRY_BYTES = 50 * 1024 * 1024

    /** Serial 1 is 1 January 1900 (serial 0 counts from 30 December 1899 once past Lotus' leap day). */
    private val EPOCH_1900: LocalDate = LocalDate.of(1899, 12, 30)
    private val EPOCH_1904: LocalDate = LocalDate.of(1904, 1, 1)
    private val BUILT_IN_DATE_FORMATS = (14..17) + 22 + (27..36) + (50..58)
}
