package com.openfamilybudget.importing

import com.openfamilybudget.auth.AppProperties
import com.openfamilybudget.common.badRequest
import com.openfamilybudget.family.CurrentUser
import com.openfamilybudget.family.FamilyRepository
import com.openfamilybudget.txn.TxnType
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate

/** Extracts transactions from a bank-statement PDF using an OpenAI-compatible chat API (OpenAI, Ollama, ...). */
@Service
class PdfImportService(
    private val props: AppProperties,
    private val families: FamilyRepository,
    private val current: CurrentUser,
    private val json: JsonMapper,
) {
    fun extract(pdf: ByteArray): Pair<List<RawRow>, List<String>> {
        val family = families.findById(current.familyId()).orElseThrow()
        if (!family.llmImportEnabled) throw badRequest("LLM_DISABLED", "PDF import with an LLM is disabled for this family. Enable it in family settings.")
        val text = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }
        if (text.isBlank()) throw badRequest("PDF_NO_TEXT", "No text found in the PDF (scanned documents are not supported)")
        val reply = ask(mask(text).take(60_000))
        return parse(reply)
    }

    /** Hides long digit runs (account/card numbers, IBAN tails) before text leaves the server. */
    fun mask(text: String): String =
        text.replace(Regex("\\b[A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]{4}){2,7}\\s?[A-Z0-9]{1,4}\\b"), "[IBAN]")
            .replace(Regex("\\b\\d{9,}\\b"), "[NUMBER]")

    private fun ask(statement: String): String {
        val llm = props.llm
        val prompt = """Extract every transaction from this bank statement. Reply with ONLY a JSON array of objects
{"date":"YYYY-MM-DD","description":"...","amount":number (negative for money out, positive for money in)}.
No prose, no markdown.

$statement"""
        val body = mapOf(
            "model" to llm.model, "temperature" to 0,
            "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
        )
        val resp = try {
            RestClient.builder().baseUrl(llm.baseUrl).build().post().uri("/chat/completions")
                .headers { h -> if (llm.apiKey.isNotBlank()) h.setBearerAuth(llm.apiKey) }
                .body(body).retrieve().body(String::class.java)
        } catch (e: Exception) {
            throw badRequest("LLM_FAILED", "LLM request failed: ${e.message}")
        }
        return json.readTree(resp).path("choices").path(0).path("message").path("content").asString("")
    }

    fun parse(reply: String): Pair<List<RawRow>, List<String>> {
        val start = reply.indexOf('[')
        val end = reply.lastIndexOf(']')
        if (start < 0 || end < start) throw badRequest("LLM_BAD_REPLY", "The model did not return a transaction list")
        val errors = mutableListOf<String>()
        val rows = json.readTree(reply.substring(start, end + 1)).mapIndexedNotNull { i, n ->
            try {
                val amt = BigDecimal(n.path("amount").asString())
                if (amt.signum() == 0) return@mapIndexedNotNull null
                RawRow(LocalDate.parse(n.path("date").asString()), n.path("description").asString(""), amt.abs(), if (amt.signum() < 0) TxnType.EXPENSE else TxnType.INCOME)
            } catch (e: Exception) {
                errors += "Item ${i + 1}: ${e.message}"
                null
            }
        }
        return rows to errors
    }
}
