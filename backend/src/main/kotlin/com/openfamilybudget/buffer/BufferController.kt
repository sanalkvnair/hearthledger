package com.openfamilybudget.buffer

import com.openfamilybudget.account.AccountType
import com.openfamilybudget.common.money
import com.openfamilybudget.currency.CurrencyService
import com.openfamilybudget.family.CurrentUser
import com.openfamilybudget.family.FamilyRepository
import com.openfamilybudget.period.PeriodCalculator
import com.openfamilybudget.recurring.RecurringRuleRepository
import com.openfamilybudget.savings.SavingsController
import com.openfamilybudget.txn.TxnType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class BufferRequirement(val months: Int, val amount: BigDecimal)

data class BufferDto(
    val baseCurrency: String,
    /** Null for the whole family, otherwise the member the figures are scoped to. */
    val userId: Long?,
    val recurringMonthly: BigDecimal,
    val loanMonthly: BigDecimal,
    val essentialMonthlyAverage: BigDecimal,
    /** Recurring commitments + loan EMIs + average essential spending per period. */
    val monthlyNeed: BigDecimal,
    val configuredMonths: Int,
    val requirements: List<BufferRequirement>,
    /** Value of savings accounts (not FD / investments) held for this scope. */
    val liquidSavings: BigDecimal,
    val coverageMonths: BigDecimal?,
    val shortfall: BigDecimal,
)

@RestController
@RequestMapping("/api/v1/buffer")
class BufferController(
    private val jdbc: JdbcClient,
    private val rules: RecurringRuleRepository,
    private val families: FamilyRepository,
    private val currency: CurrencyService,
    private val savings: SavingsController,
    private val current: CurrentUser,
) {
    @GetMapping
    fun buffer(@RequestParam(required = false) userId: Long?): BufferDto {
        val familyId = current.familyId()
        val family = families.findById(familyId).orElseThrow()
        val today = LocalDate.now()
        fun toBase(amount: BigDecimal, cur: String) =
            amount * runCatching { currency.rateToBase(familyId, family.baseCurrency, cur.trim(), today) }.getOrDefault(BigDecimal.ONE)

        val recurring = rules.findByFamilyId(familyId)
            .filter { it.active && it.type == TxnType.EXPENSE && it.loanId == null && (it.endDate == null || !it.endDate.isBefore(today)) }
            .filter { userId == null || it.userId == userId }
            .sumOf { toBase(it.amount, it.currency) * it.frequency.perMonth }

        val loanMonthly = jdbc.sql(
            """SELECT l.emi, l.currency FROM loan l WHERE l.family_id = :f AND l.active
                 AND (SELECT count(*) FROM txn WHERE loan_id = l.id) < l.tenure_months
                 AND (CAST(:u AS bigint) IS NULL OR l.owner_user_id = :u)""",
        ).param("f", familyId).param("u", userId, java.sql.Types.BIGINT)
            .query { rs, _ -> toBase(rs.getBigDecimal(1), rs.getString(2)) }.list().sumOf { it }

        val essential = essentialAverage(familyId, family.cycleStartDay, family.bufferLookbackMonths, userId)

        val need = (recurring + loanMonthly + essential).money()
        val months = (listOf(3, 6, family.bufferMonths)).distinct().sorted()
        val liquid = savings.overview().accounts
            .filter { it.type == AccountType.SAVINGS && (userId == null || it.ownerUserId == userId) }
            .sumOf { it.currentValue }.money()
        val configured = need * BigDecimal(family.bufferMonths)
        return BufferDto(
            family.baseCurrency, userId, recurring.money(), loanMonthly.money(), essential, need, family.bufferMonths,
            months.map { BufferRequirement(it, (need * BigDecimal(it)).money()) }, liquid,
            if (need.signum() > 0) liquid.divide(need, 1, RoundingMode.HALF_UP) else null,
            (configured - liquid).max(BigDecimal.ZERO).money(),
        )
    }

    /**
     * Average one-off essential spending per period over the last completed periods; periods before
     * the first recorded transaction are ignored so a new family isn't averaged down by empty history.
     */
    private fun essentialAverage(familyId: Long, startDay: Int, lookback: Int, userId: Long?): BigDecimal {
        var period = PeriodCalculator.previous(PeriodCalculator.containing(LocalDate.now(), startDay), startDay)
        val first = jdbc.sql("SELECT min(date) FROM txn WHERE family_id = :f").param("f", familyId).query(java.sql.Date::class.java).optional().orElse(null)
            ?.toLocalDate() ?: return BigDecimal.ZERO
        var total = BigDecimal.ZERO
        var counted = 0
        repeat(lookback) {
            if (!period.end.isBefore(first)) {
                counted++
                total += jdbc.sql(
                    """SELECT COALESCE(sum(base_amount), 0) FROM txn WHERE family_id = :f AND type = 'EXPENSE' AND necessity = 'ESSENTIAL'
                         AND recurring_rule_id IS NULL AND loan_id IS NULL AND date BETWEEN :from AND :to
                         AND (CAST(:u AS bigint) IS NULL OR user_id = :u)""",
                ).param("f", familyId).param("from", period.start).param("to", period.end).param("u", userId, java.sql.Types.BIGINT)
                    .query(BigDecimal::class.java).single()
            }
            period = PeriodCalculator.previous(period, startDay)
        }
        return if (counted == 0) BigDecimal.ZERO else total.divide(BigDecimal(counted), 4, RoundingMode.HALF_UP)
    }
}
