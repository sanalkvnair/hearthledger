package com.hearthledger.buffer

import com.hearthledger.account.AccountType
import com.hearthledger.common.expenseDelta
import com.hearthledger.common.money
import com.hearthledger.common.reportIncluded
import com.hearthledger.currency.CurrencyService
import com.hearthledger.family.CurrentUser
import com.hearthledger.family.FamilyRepository
import com.hearthledger.recurring.RecurringRuleRepository
import com.hearthledger.savings.SavingsController
import com.hearthledger.txn.TxnType
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
    /** Closed credit card statements still to be paid; already deducted from [liquidSavings]. */
    val cardBillsDue: BigDecimal,
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
    private val periods: com.hearthledger.period.PeriodService,
    private val cards: com.hearthledger.creditcard.CreditCardService,
    private val loans: com.hearthledger.loan.LoanRepository,
    private val loanService: com.hearthledger.loan.LoanService,
    private val current: CurrentUser,
) {
    @GetMapping
    fun buffer(@RequestParam(required = false) userId: Long?): BufferDto {
        val familyId = current.familyId()
        val family = families.findById(familyId).orElseThrow()
        val today = LocalDate.now()
        fun toBase(amount: BigDecimal, cur: String) =
            amount * currency.rateToBase(familyId, family.baseCurrency, cur.trim(), today)

        val recurring = rules.findByFamilyId(familyId)
            .filter { it.active && it.type == TxnType.EXPENSE && it.loanId == null && (it.endDate == null || !it.endDate.isBefore(today)) }
            .filter { userId == null || it.userId == userId }
            .sumOf { toBase(it.amount, it.currency) * it.frequency.perMonth }

        val loanMonthly = loans.findByFamilyId(familyId)
            .filter { it.active && (userId == null || it.ownerUserId == userId) }
            .sumOf { loan ->
                val rows = loanService.schedule(loan, today)
                loanService.nextMonthlyPayment(loan, rows, loan.generatedThrough)?.let { toBase(it, loan.currency) } ?: BigDecimal.ZERO
            }

        val essential = essentialAverage(family, userId)

        val need = (recurring + loanMonthly + essential).money()
        val months = (listOf(3, 6, family.bufferMonths)).distinct().sorted()
        val savingsTotal = savings.overview().accounts
            .filter { it.type == AccountType.SAVINGS && (userId == null || userId in it.ownerIds) }
            .sumOf { it.currentValue }.money()
        val cardBills = cards.unpaidTotal(familyId, userId)
        val liquid = (savingsTotal - cardBills).money()
        val configured = need * BigDecimal(family.bufferMonths)
        return BufferDto(
            family.baseCurrency, userId, recurring.money(), loanMonthly.money(), essential, need, family.bufferMonths,
            months.map { BufferRequirement(it, (need * BigDecimal(it)).money()) }, liquid, cardBills,
            if (need.signum() > 0) liquid.divide(need, 1, RoundingMode.HALF_UP) else null,
            (configured - liquid).max(BigDecimal.ZERO).money(),
        )
    }

    /**
     * Average one-off essential spending per period over the last completed periods; periods before
     * the first recorded transaction are ignored so a new family isn't averaged down by empty history.
     */
    private fun essentialAverage(family: com.hearthledger.family.Family, userId: Long?): BigDecimal {
        val familyId = family.id!!
        val lookback = family.bufferLookbackMonths
        var period = periods.previous(family, periods.containing(family, LocalDate.now()))
        val first = jdbc.sql("SELECT min(date) FROM txn WHERE family_id = :f").param("f", familyId).query(java.sql.Date::class.java).optional().orElse(null)
            ?.toLocalDate() ?: return BigDecimal.ZERO
        var total = BigDecimal.ZERO
        var counted = 0
        repeat(lookback) {
            if (!period.end.isBefore(first)) {
                counted++
                total += jdbc.sql(
                    """SELECT COALESCE(sum(${expenseDelta()}), 0) FROM txn t WHERE t.family_id = :f AND ${reportIncluded()} AND t.type IN ('EXPENSE', 'REFUND') AND t.necessity = 'ESSENTIAL'
                         AND t.recurring_rule_id IS NULL AND t.loan_id IS NULL AND t.date BETWEEN :from AND :to
                         AND (CAST(:u AS bigint) IS NULL OR t.user_id = :u)""",
                ).param("f", familyId).param("from", period.start).param("to", period.end).param("u", userId, java.sql.Types.BIGINT)
                    .query(BigDecimal::class.java).single()
            }
            period = periods.previous(family, period)
        }
        return if (counted == 0) BigDecimal.ZERO else total.divide(BigDecimal(counted), 4, RoundingMode.HALF_UP)
    }
}
