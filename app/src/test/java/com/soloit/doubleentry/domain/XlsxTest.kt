package com.soloit.doubleentry.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxTest {

    private fun workbook(sheet: String, date1904: Boolean = false, hiddenFirst: Boolean = false): ByteArray {
        val files = mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "xl/workbook.xml" to """
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <workbookPr date1904="${if (date1904) 1 else 0}"/>
                  <sheets>
                    ${if (hiddenFirst) """<sheet name="Hidden" sheetId="2" state="hidden" r:id="rId2"/>""" else ""}
                    <sheet name="Transaktioner" sheetId="1" r:id="rId1"/>
                  </sheets>
                </workbook>
            """,
            "xl/_rels/workbook.xml.rels" to """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="worksheet" Target="/xl/worksheets/sheet2.xml"/>
                </Relationships>
            """,
            "xl/styles.xml" to """
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <numFmts count="2">
                    <numFmt numFmtId="164" formatCode="yyyy\-mm\-dd"/>
                    <numFmt numFmtId="165" formatCode="#,##0.00 &quot;kr&quot;;[Red]\-#,##0.00 &quot;kr&quot;"/>
                  </numFmts>
                  <cellStyleXfs count="1"><xf numFmtId="0"/></cellStyleXfs>
                  <cellXfs count="4"><xf numFmtId="0"/><xf numFmtId="164"/><xf numFmtId="165"/><xf numFmtId="14"/></cellXfs>
                </styleSheet>
            """,
            "xl/sharedStrings.xml" to """
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>Kontoutdrag</t></si>
                  <si><t>Datum</t></si>
                  <si><t>Text</t></si>
                  <si><t>Belopp</t></si>
                  <si><t>Saldo</t></si>
                  <si><r><t>ICA </t></r><r><t>Nära</t></r><rPh><t>ignored</t></rPh></si>
                  <si><t>Lön</t></si>
                </sst>
            """,
            "xl/worksheets/sheet1.xml" to sheet,
            "xl/worksheets/sheet2.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>hidden</t></is></c></row></sheetData></worksheet>""",
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, xml) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.trimIndent().toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val statement = """
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c></row>
            <row r="3"><c r="A3" t="s"><v>1</v></c><c r="B3" t="s"><v>2</v></c><c r="D3" t="s"><v>3</v></c><c r="E3" t="s"><v>4</v></c></row>
            <row r="4"><c r="A4" s="1"><v>46298</v></c><c r="B4" t="s"><v>5</v></c><c r="D4" s="2"><v>-245.5</v></c><c r="E4" s="2"><v>12754.500000000002</v></c></row>
            <row r="5"><c r="A5" s="3"><v>46291.75</v></c><c r="B5" t="s"><v>6</v></c><c r="D5" s="2"><f>D6*2</f><v>25000</v></c><c r="E5"><v>13000</v></c></row>
          </sheetData>
        </worksheet>
    """

    @Test
    fun readsTheFirstVisibleSheetWithDatesNumbersAndStrings() {
        val bytes = workbook(statement, hiddenFirst = true)
        assertTrue(Xlsx.isXlsx(bytes))
        assertEquals(
            listOf(
                listOf("Kontoutdrag"),
                emptyList(),
                listOf("Datum", "Text", "", "Belopp", "Saldo"),
                listOf("2026-10-03", "ICA Nära", "", "-245.5", "12754.5"),
                listOf("2026-09-26", "Lön", "", "25000", "13000"),
            ),
            Xlsx.records(bytes),
        )
    }

    @Test
    fun bankStatementImportsAWorkbookLikeACsvFile() {
        val table = BankStatement.table(workbook(statement))
        val columns = BankStatement.detectColumns(table)
        assertEquals(StatementColumns(date = 0, text = 1, amount = 3, balance = 4), columns)
        val read = BankStatement.read(table, columns)
        assertEquals(
            listOf(
                StatementLine(0, LocalDate.of(2026, 10, 3), "ICA Nära", -245_50, 12_754_50),
                StatementLine(1, LocalDate.of(2026, 9, 26), "Lön", 25_000_00, 13_000_00),
            ),
            read.lines,
        )
    }

    @Test
    fun serialDatesHonourThe1904SystemAndExcelsLeapDay() {
        fun date(serial: String, date1904: Boolean = false) = Xlsx.records(
            workbook("""<worksheet><sheetData><row><c s="3"><v>$serial</v></c></row></sheetData></worksheet>""", date1904),
        ).single().single()
        assertEquals("1900-01-01", date("1"))
        assertEquals("1900-02-28", date("59"))
        assertEquals("1900-03-01", date("61"))
        assertEquals("2026-10-03", date("44836", date1904 = true))
    }

    @Test
    fun textFilesAreNotWorkbooks() {
        assertFalse(Xlsx.isXlsx("Datum;Text;Belopp".toByteArray()))
    }
}
