package com.hearthledger.budget

import com.hearthledger.category.CategoryRepository
import com.hearthledger.common.money
import com.hearthledger.common.notFound
import com.hearthledger.family.CurrentUser
import com.hearthledger.family.FamilyRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDate

data class BudgetRequest(val categoryId: Long, @field:DecimalMin("0.01") val amount: BigDecimal)

data class BudgetProgress(
    val id: Long, val categoryId: Long, val categoryName: String, val amount: BigDecimal, val spent: BigDecimal,
    val remaining: BigDecimal, val percent: Int,
    /** OK below 80%, WARNING from 80%, EXCEEDED above 100%. */
    val status: String,
)

@RestController
@RequestMapping("/api/v1/budgets")
class BudgetController(
    private val jdbc: JdbcClient,
    private val categories: CategoryRepository,
    private val families: FamilyRepository,
    private val periods: com.hearthledger.period.PeriodService,
    private val current: CurrentUser,
) {
    /** Budgets are monthly limits in the base currency; a parent category's limit includes its subcategories. */
    @GetMapping
    fun progress(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?): List<BudgetProgress> {
        val familyId = current.familyId()
        val period = periods.containing(families.findById(familyId).orElseThrow(), date ?: LocalDate.now())
        return jdbc.sql(
            """SELECT b.id, b.category_id, c.name, b.amount,
                 COALESCE((SELECT sum(t.base_amount) FROM txn t JOIN category tc ON tc.id = t.category_id
                           WHERE t.family_id = b.family_id AND t.type = 'EXPENSE' AND t.date BETWEEN :from AND :to
                             AND (tc.id = b.category_id OR tc.parent_id = b.category_id)), 0) AS spent
               FROM budget b JOIN category c ON c.id = b.category_id WHERE b.family_id = :f ORDER BY c.name""",
        ).param("f", familyId).param("from", period.start).param("to", period.end).query { rs, _ ->
            val amount = rs.getBigDecimal("amount")
            val spent = rs.getBigDecimal("spent").money()
            val percent = spent.multiply(BigDecimal(100)).divide(amount, 0, java.math.RoundingMode.HALF_UP).toInt()
            BudgetProgress(
                rs.getLong("id"), rs.getLong("category_id"), rs.getString("name"), amount.money(), spent, (amount - spent).money(), percent,
                when { percent > 100 -> "EXCEEDED"; percent >= 80 -> "WARNING"; else -> "OK" },
            )
        }.list()
    }

    @PutMapping
    fun upsert(@Valid @RequestBody r: BudgetRequest) {
        val familyId = current.requireAdmin().familyId
        categories.findByIdAndFamilyId(r.categoryId, familyId)?.takeIf { it.type == com.hearthledger.category.CategoryType.EXPENSE }
            ?: throw notFound("Expense category")
        jdbc.sql(
            """INSERT INTO budget (family_id, category_id, amount) VALUES (:f, :c, :a)
               ON CONFLICT (family_id, category_id) DO UPDATE SET amount = EXCLUDED.amount""",
        ).param("f", familyId).param("c", r.categoryId).param("a", r.amount).update()
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) {
        jdbc.sql("DELETE FROM budget WHERE id = :id AND family_id = :f").param("id", id).param("f", current.requireAdmin().familyId).update()
    }
}
