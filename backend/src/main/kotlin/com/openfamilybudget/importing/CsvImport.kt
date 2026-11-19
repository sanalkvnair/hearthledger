package com.openfamilybudget.importing

import com.openfamilybudget.common.badRequest
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class CsvMapping(
    val delimiter: String? = null,
    val dateColumn: String,
    val dateFormat: String = "yyyy-MM-dd",
    val descriptionColumn: String,
    /** One signed column (negative = expense), or leave null and use debit/credit columns. */
    val amountColumn: String? = null,
    val debitColumn: String? = null,
    val creditColumn: String? = null,
    val decimalSeparator: String = ".",
    val invertSign: Boolean = false,
)

data class ParsedCsv(val delimiter: String, val headers: List<String>, val rows: List<List<String>>)

object CsvParser {
    fun detectDelimiter(text: String): String {
        val first = text.lineSequence().firstOrNull().orEmpty()
        return listOf(",", ";", "\t").maxBy { d -> first.count { it == d[0] } }
    }

    fun parse(raw: String, delimiter: String? = null): ParsedCsv {
        val text = raw.removePrefix("\uFEFF")
        val delim = (delimiter?.takeIf { it.isNotEmpty() } ?: detectDelimiter(text))[0]
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() { row.add(cell.toString().trim()); cell.setLength(0) }
        fun endRow() { endCell(); if (row.any { it.isNotEmpty() }) rows.add(row); row = mutableListOf() }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == delim -> endCell()
                !quoted && c == '\n' -> endRow()
                !quoted && c == '\r' -> {}
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        if (rows.isEmpty()) throw badRequest("EMPTY_FILE", "The file has no rows")
        return ParsedCsv(delim.toString(), rows.first(), rows.drop(1))
    }

    fun parseAmount(raw: String, decimalSeparator: String): BigDecimal? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        val negative = s.startsWith("(") && s.endsWith(")") || s.startsWith("-") || s.endsWith("-")
        s = s.filter { it.isDigit() || it.toString() == decimalSeparator }
        if (decimalSeparator != ".") s = s.replace(decimalSeparator, ".")
        if (s.isEmpty() || s == ".") return null
        val v = s.toBigDecimalOrNull() ?: return null
        return if (negative) v.negate() else v
    }

    fun parseDate(raw: String, pattern: String): LocalDate =
        LocalDate.parse(raw.trim(), DateTimeFormatter.ofPattern(pattern))
}
