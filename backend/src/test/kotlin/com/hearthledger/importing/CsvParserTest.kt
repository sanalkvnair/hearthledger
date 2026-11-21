package com.hearthledger.importing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CsvParserTest {
    @Test
    fun `detects delimiter, handles quotes and BOM`() {
        val csv = CsvParser.parse("\uFEFFDate;Text;Amount\n2026-01-02;\"Shop; \"\"big\"\"\";-12,50\n")
        assertEquals(";", csv.delimiter)
        assertEquals(listOf("Date", "Text", "Amount"), csv.headers)
        assertEquals(listOf("2026-01-02", "Shop; \"big\"", "-12,50"), csv.rows.single())
    }

    @Test
    fun `parses amounts in different formats`() {
        assertEquals(BigDecimal("-1234.56"), CsvParser.parseAmount("-1 234,56 kr", ","))
        assertEquals(BigDecimal("-99.10"), CsvParser.parseAmount("(\$99.10)", "."))
        assertEquals(BigDecimal("1500.00"), CsvParser.parseAmount("1,500.00", "."))
        assertEquals(null, CsvParser.parseAmount("", "."))
    }
}
