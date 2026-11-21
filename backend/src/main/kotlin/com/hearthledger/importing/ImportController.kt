package com.hearthledger.importing

import com.hearthledger.category.CategoryRepository
import com.hearthledger.common.notFound
import com.hearthledger.family.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import tools.jackson.databind.json.JsonMapper

data class CsvParseResult(val delimiter: String, val headers: List<String>, val sample: List<List<String>>)
data class CommitRequest(val accountId: Long, val rows: List<ImportRow>)
data class RuleRequest(@field:NotBlank val keyword: String, val categoryId: Long, val priority: Int = 0)
data class RuleDto(val id: Long, val keyword: String, val categoryId: Long, val categoryName: String, val priority: Int)
data class TemplateRequest(@field:NotBlank val name: String, val mapping: CsvMapping)
data class TemplateDto(val id: Long, val name: String, val mapping: CsvMapping)

@RestController
@RequestMapping("/api/v1/import")
class ImportController(
    private val service: ImportService,
    private val pdf: PdfImportService,
    private val jdbc: JdbcClient,
    private val categories: CategoryRepository,
    private val current: CurrentUser,
    private val json: JsonMapper,
) {
    private fun text(f: MultipartFile) = String(f.bytes, Charsets.UTF_8)

    @PostMapping("/csv/inspect", consumes = ["multipart/form-data"])
    fun inspect(@RequestPart file: MultipartFile, @RequestParam(required = false) delimiter: String?): CsvParseResult {
        current.requireWriter()
        val csv = CsvParser.parse(text(file), delimiter)
        return CsvParseResult(csv.delimiter, csv.headers, csv.rows.take(5))
    }

    @PostMapping("/csv/preview", consumes = ["multipart/form-data"])
    fun preview(@RequestPart file: MultipartFile, @RequestParam accountId: Long, @RequestParam mapping: String): ImportPreview {
        current.requireWriter()
        val m = json.readValue(mapping, CsvMapping::class.java)
        val (raw, errors) = service.mapCsv(CsvParser.parse(text(file), m.delimiter), m)
        return service.enrich(accountId, raw, errors)
    }

    @PostMapping("/pdf/preview", consumes = ["multipart/form-data"])
    fun pdfPreview(@RequestPart file: MultipartFile, @RequestParam accountId: Long): ImportPreview {
        current.requireWriter()
        val (raw, errors) = pdf.extract(file.bytes)
        return service.enrich(accountId, raw, errors)
    }

    @PostMapping("/commit")
    fun commit(@RequestBody r: CommitRequest): Map<String, Int> = mapOf("imported" to service.commit(r.accountId, r.rows))

    @GetMapping("/templates")
    fun templates(): List<TemplateDto> =
        jdbc.sql("SELECT id, name, mapping FROM import_template WHERE family_id = :f ORDER BY name").param("f", current.familyId())
            .query { rs, _ -> TemplateDto(rs.getLong(1), rs.getString(2), json.readValue(rs.getString(3), CsvMapping::class.java)) }.list()

    @PutMapping("/templates")
    fun saveTemplate(@Valid @RequestBody r: TemplateRequest): TemplateDto {
        val m = current.requireWriter()
        val id = jdbc.sql(
            """INSERT INTO import_template (family_id, name, mapping) VALUES (:f, :n, :m)
               ON CONFLICT (family_id, name) DO UPDATE SET mapping = EXCLUDED.mapping RETURNING id""",
        ).param("f", m.familyId).param("n", r.name).param("m", json.writeValueAsString(r.mapping)).query(Long::class.java).single()
        return TemplateDto(id, r.name, r.mapping)
    }

    @DeleteMapping("/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteTemplate(@PathVariable id: Long) {
        val m = current.requireWriter()
        jdbc.sql("DELETE FROM import_template WHERE id = :i AND family_id = :f").param("i", id).param("f", m.familyId).update()
    }

    @GetMapping("/rules")
    fun rules(): List<RuleDto> =
        jdbc.sql(
            """SELECT r.id, r.keyword, r.category_id, c.name, r.priority FROM categorization_rule r
               JOIN category c ON c.id = r.category_id WHERE r.family_id = :f ORDER BY r.priority DESC, r.keyword""",
        ).param("f", current.familyId()).query { rs, _ -> RuleDto(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getInt(5)) }.list()

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    fun createRule(@Valid @RequestBody r: RuleRequest): RuleDto {
        val m = current.requireWriter()
        val cat = categories.findByIdAndFamilyId(r.categoryId, m.familyId) ?: throw notFound("Category")
        val id = jdbc.sql("INSERT INTO categorization_rule (family_id, keyword, category_id, priority) VALUES (:f, :k, :c, :p) RETURNING id")
            .param("f", m.familyId).param("k", r.keyword.trim()).param("c", cat.id).param("p", r.priority).query(Long::class.java).single()
        return RuleDto(id, r.keyword.trim(), cat.id!!, cat.name, r.priority)
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteRule(@PathVariable id: Long) {
        val m = current.requireWriter()
        jdbc.sql("DELETE FROM categorization_rule WHERE id = :i AND family_id = :f").param("i", id).param("f", m.familyId).update()
    }
}
