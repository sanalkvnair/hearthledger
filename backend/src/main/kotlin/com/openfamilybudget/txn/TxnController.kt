package com.openfamilybudget.txn

import com.openfamilybudget.common.Commitment
import com.openfamilybudget.common.Necessity
import com.openfamilybudget.family.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Pattern
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDate

data class TxnRequest(
    val type: TxnType,
    @field:DecimalMin("0.0001") val amount: BigDecimal,
    @field:Pattern(regexp = "[A-Za-z]{3}") val currency: String,
    /** Optional manual override; otherwise the family's stored rate for the date is used. */
    @field:DecimalMin("0.00000001") val fxRate: BigDecimal? = null,
    val date: LocalDate,
    val accountId: Long? = null,
    /** Set on transfers; a savings/FD/investment destination is counted as savings. */
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    val commitment: Commitment? = null,
    val necessity: Necessity? = null,
    val note: String? = null,
    val loanId: Long? = null,
    /** Set by the importer for duplicate detection. */
    val importHash: String? = null,
)

data class TxnDto(
    val id: Long, val userId: Long, val userName: String, val type: TxnType,
    val amount: BigDecimal, val currency: String, val fxRate: BigDecimal, val baseAmount: BigDecimal,
    val date: LocalDate, val accountId: Long?, val accountName: String?, val toAccountId: Long?, val toAccountName: String?,
    val categoryId: Long?, val categoryName: String?, val commitment: Commitment?, val necessity: Necessity?, val note: String?,
    val loanId: Long?, val recurringRuleId: Long?,
)

data class TxnPage(val items: List<TxnDto>, val total: Long, val page: Int, val size: Int)

@RestController
@RequestMapping("/api/v1/transactions")
class TxnController(private val service: TxnService, private val jdbc: JdbcClient, private val current: CurrentUser) {

    @GetMapping
    fun list(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) type: TxnType?,
        @RequestParam(required = false) categoryId: Long?,
        @RequestParam(required = false) accountId: Long?,
        @RequestParam(required = false) userId: Long?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): TxnPage {
        val where = StringBuilder("t.family_id = :family")
        val params = mutableMapOf<String, Any>("family" to current.familyId())
        fun filter(v: Any?, name: String, sql: String) { if (v != null) { where.append(" AND $sql"); params[name] = v } }
        filter(from, "from", "t.date >= :from")
        filter(to, "to", "t.date <= :to")
        filter(type?.name, "type", "t.type = :type")
        // a parent category filter also matches its subcategories
        filter(categoryId, "cat", "(t.category_id = :cat OR c.parent_id = :cat)")
        filter(accountId, "acc", "(t.account_id = :acc OR t.to_account_id = :acc)")
        filter(userId, "user", "t.user_id = :user")

        val limit = size.coerceIn(1, 200)
        val from_ = """FROM txn t JOIN app_user u ON u.id = t.user_id
            LEFT JOIN category c ON c.id = t.category_id LEFT JOIN account a ON a.id = t.account_id
            LEFT JOIN account ta ON ta.id = t.to_account_id WHERE $where"""
        val total = jdbc.sql("SELECT count(*) $from_").params(params).query(Long::class.java).single()
        val items = jdbc.sql(
            """SELECT t.*, u.name AS user_name, c.name AS category_name, a.name AS account_name, ta.name AS to_account_name
               $from_ ORDER BY t.date DESC, t.id DESC LIMIT :limit OFFSET :offset""",
        ).params(params).param("limit", limit).param("offset", page.coerceAtLeast(0) * limit).query { rs, _ ->
            TxnDto(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("user_name"), TxnType.valueOf(rs.getString("type")),
                rs.getBigDecimal("amount"), rs.getString("currency").trim(), rs.getBigDecimal("fx_rate"), rs.getBigDecimal("base_amount"),
                rs.getDate("date").toLocalDate(), rs.getObject("account_id") as Long?, rs.getString("account_name"),
                rs.getObject("to_account_id") as Long?, rs.getString("to_account_name"),
                rs.getObject("category_id") as Long?, rs.getString("category_name"),
                rs.getString("commitment")?.let(Commitment::valueOf), rs.getString("necessity")?.let(Necessity::valueOf), rs.getString("note"),
                rs.getObject("loan_id") as Long?, rs.getObject("recurring_rule_id") as Long?,
            )
        }.list()
        return TxnPage(items, total, page, limit)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody r: TxnRequest) = service.create(r)

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody r: TxnRequest) = service.update(id, r)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) = service.delete(id)
}
