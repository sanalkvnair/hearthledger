package com.hearthledger.export

import com.hearthledger.family.CurrentUser
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayOutputStream
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/export")
class ExportController(private val jdbc: JdbcClient, private val current: CurrentUser) {
    private val header = listOf("Date", "Type", "Amount", "Currency", "Base amount", "Account", "To account", "Category", "Commitment", "Necessity", "Member", "Note", "Excluded")

    private fun rows(from: LocalDate?, to: LocalDate?): List<List<Any?>> =
        jdbc.sql(
            """SELECT t.date, t.type, t.amount, t.currency, t.base_amount, a.name, ta.name, c.name, t.commitment, t.necessity, u.name, t.note, t.excluded
               FROM txn t JOIN app_user u ON u.id = t.user_id
               LEFT JOIN account a ON a.id = t.account_id LEFT JOIN account ta ON ta.id = t.to_account_id
               LEFT JOIN category c ON c.id = t.category_id
               WHERE t.family_id = :f AND (CAST(:from AS date) IS NULL OR t.date >= :from) AND (CAST(:to AS date) IS NULL OR t.date <= :to)
               ORDER BY t.date, t.id""",
        ).param("f", current.familyId()).param("from", from).param("to", to).query { rs, _ ->
            listOf<Any?>(
                rs.getDate(1).toLocalDate().toString(), rs.getString(2), rs.getBigDecimal(3), rs.getString(4), rs.getBigDecimal(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), if (rs.getBoolean(13)) "yes" else "",
            )
        }.list()

    private fun csvCell(v: Any?): String {
        val s = v?.toString().orEmpty()
        // Prefix formula-looking text so spreadsheet apps do not execute it.
        val safe = if (v !is Number && s.trimStart().firstOrNull()?.let { it in "=+-@" } == true || s.firstOrNull()?.let { it == '\t' || it == '\r' } == true) "'$s" else s
        return if (safe.any { it in ",\"\n\r\t" }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun file(bytes: ByteArray, name: String, type: String) = ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
        .contentType(MediaType.parseMediaType(type)).body(bytes)

    @GetMapping("/transactions.csv")
    fun csv(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<ByteArray> {
        val sb = StringBuilder("\uFEFF").append(header.joinToString(",")).append("\r\n")
        rows(from, to).forEach { sb.append(it.joinToString(",") { c -> csvCell(c) }).append("\r\n") }
        return file(sb.toString().toByteArray(), "transactions.csv", "text/csv")
    }

    @GetMapping("/transactions.xlsx")
    fun xlsx(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<ByteArray> {
        val out = ByteArrayOutputStream()
        SXSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Transactions")
            (listOf(header) + rows(from, to)).forEachIndexed { r, values ->
                val row = sheet.createRow(r)
                values.forEachIndexed { c, v ->
                    val cell = row.createCell(c)
                    when (v) {
                        is java.math.BigDecimal -> cell.setCellValue(v.toDouble())
                        else -> cell.setCellValue(v?.toString().orEmpty())
                    }
                }
            }
            wb.write(out)
        }
        return file(out.toByteArray(), "transactions.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    }
}
