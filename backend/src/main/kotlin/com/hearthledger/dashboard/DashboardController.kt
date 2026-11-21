package com.hearthledger.dashboard

import com.hearthledger.common.money
import com.hearthledger.common.reportIncluded
import com.hearthledger.family.CurrentUser
import com.hearthledger.family.FamilyRepository
import com.hearthledger.period.Period
import com.hearthledger.period.PeriodService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate

data class CategoryTotal(val categoryId: Long?, val name: String, val amount: BigDecimal)
data class PeriodSummary(val start: LocalDate, val end: LocalDate, val income: BigDecimal, val expense: BigDecimal, val savings: BigDecimal)
data class OpeningBalanceDto(val computed: BigDecimal, val adjusted: BigDecimal?, val effective: BigDecimal)

data class DashboardDto(
    val period: Period,
    val baseCurrency: String,
    val income: BigDecimal,
    val expense: BigDecimal,
    val savings: BigDecimal,
    /** income − expense − savings for this period alone (without the previous balance). */
    val balanceWithoutPrevious: BigDecimal,
    val openingBalance: OpeningBalanceDto,
    /** Previous balance + this period's net. */
    val balanceWithPrevious: BigDecimal,
    val expenseByCategory: List<CategoryTotal>,
    val incomeByCategory: List<CategoryTotal>,
    val history: List<PeriodSummary>,
)

@RestController
@RequestMapping("/api/v1/dashboard")
class DashboardController(
    private val periods: PeriodService,
    private val families: FamilyRepository,
    private val current: CurrentUser,
    private val jdbc: JdbcClient,
) {
    @GetMapping
    fun dashboard(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?,
        @RequestParam(defaultValue = "6") history: Int,
        /** Limits the category breakdowns to purchases paid by CARD, BANK or CASH accounts (default ALL). */
        @RequestParam(defaultValue = "ALL") paymentMethod: String,
    ): DashboardDto {
        val familyId = current.familyId()
        val family = families.findById(familyId).orElseThrow()
        val period = periods.containing(family, date ?: LocalDate.now())
        val totals = periods.totals(familyId, period.start, period.end)
        val opening = periods.openingBalance(familyId, period.start)

        val past = generateSequence(period) { periods.previous(family, it) }
            .take(history.coerceIn(1, 24)).toList().reversed()
            .map { p -> periods.totals(familyId, p.start, p.end).let { PeriodSummary(p.start, p.end, it.income, it.expense, it.savings) } }

        return DashboardDto(
            period = period,
            baseCurrency = family.baseCurrency,
            income = totals.income, expense = totals.expense, savings = totals.savings,
            balanceWithoutPrevious = totals.net,
            openingBalance = OpeningBalanceDto(opening.computed, opening.adjusted, opening.effective),
            balanceWithPrevious = (opening.effective + totals.net).money(),
            expenseByCategory = byCategory(familyId, "EXPENSE", period, paymentMethod),
            incomeByCategory = byCategory(familyId, "INCOME", period, paymentMethod),
            history = past,
        )
    }

    /** Groups by top-level category (subcategories roll up into their parent). */
    private fun byCategory(familyId: Long, type: String, p: Period, paymentMethod: String): List<CategoryTotal> {
        val accountType = when (paymentMethod.uppercase()) {
            "CARD" -> "CREDIT_CARD"
            "BANK" -> "BANK"
            "CASH" -> "CASH"
            else -> null
        }
        return jdbc.sql(
            """SELECT COALESCE(parent.id, c.id) AS id, COALESCE(parent.name, c.name, 'Uncategorized') AS name, sum(CASE WHEN t.type = 'REFUND' THEN -t.base_amount ELSE t.base_amount END) AS amount
               FROM txn t LEFT JOIN category c ON c.id = t.category_id LEFT JOIN category parent ON parent.id = c.parent_id
               LEFT JOIN account acc ON acc.id = t.account_id
               WHERE t.family_id = :f AND ${reportIncluded()} AND t.type IN (:type, :refundType) AND t.date BETWEEN :from AND :to
                 AND (CAST(:acct AS text) IS NULL OR acc.type = :acct)
               GROUP BY 1, 2 HAVING sum(CASE WHEN t.type = 'REFUND' THEN -t.base_amount ELSE t.base_amount END) <> 0 ORDER BY amount DESC""",
        ).param("f", familyId).param("type", type).param("refundType", if (type == "EXPENSE") "REFUND" else type).param("from", p.start).param("to", p.end).param("acct", accountType, java.sql.Types.VARCHAR)
            .query { rs, _ -> CategoryTotal(rs.getObject("id") as Long?, rs.getString("name"), rs.getBigDecimal("amount").money()) }.list()
    }
}
