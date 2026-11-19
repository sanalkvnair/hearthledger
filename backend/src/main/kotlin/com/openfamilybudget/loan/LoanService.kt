package com.openfamilybudget.loan

import com.openfamilybudget.common.Commitment
import com.openfamilybudget.common.money
import com.openfamilybudget.currency.CurrencyService
import com.openfamilybudget.family.FamilyRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

data class RateChangeDto(val id: Long, val effectiveFrom: LocalDate, val annualRate: BigDecimal)

@Service
class LoanService(
    private val loans: LoanRepository,
    private val families: FamilyRepository,
    private val currency: CurrencyService,
    private val jdbc: JdbcClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun rateChanges(loanId: Long): List<RateChangeDto> =
        jdbc.sql("SELECT id, effective_from, annual_rate FROM loan_rate WHERE loan_id = :l ORDER BY effective_from")
            .param("l", loanId).query { rs, _ -> RateChangeDto(rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getBigDecimal(3)) }.list()

    fun schedule(loan: Loan, today: LocalDate = LocalDate.now()): List<ScheduleRow> =
        LoanCalculator.schedule(loan, rateChanges(loan.id!!).map { RateChange(it.effectiveFrom, it.annualRate) }, today)

    /** Monthly cost to plan for: the next unpaid payment, without the final balloon. Null when the loan is repaid. */
    fun nextMonthlyPayment(loan: Loan, rows: List<ScheduleRow>, paid: Int): BigDecimal? {
        val next = rows.getOrNull(paid) ?: return null
        val balloon = if (loan.loanType == LoanType.BALLOON_ANNUITY && next.number == rows.size) loan.balloonAmount else BigDecimal.ZERO
        return (next.total - balloon).max(BigDecimal.ZERO)
    }

    @EventListener(ApplicationReadyEvent::class)
    @Scheduled(cron = "0 20 0 * * *")
    fun generateAllDue() {
        loans.findByActiveTrue().forEach { runCatching { generateDue(it) }.onFailure { e -> log.warn("Loan {} failed: {}", it.id, e.message) } }
    }

    /** Books every scheduled payment up to [today] that has not been booked yet. Idempotent. */
    @Transactional
    fun generateDue(loan: Loan, today: LocalDate = LocalDate.now()): Int {
        if (!loan.active) return 0
        val baseCurrency = families.findById(loan.familyId).orElseThrow().baseCurrency
        val userId = loan.ownerUserId ?: loan.createdBy ?: return 0
        val commitment = if (loan.term == LoanTerm.SHORT_TERM) Commitment.SHORT_TERM_LOAN else Commitment.LONG_TERM_LOAN
        var through = loan.generatedThrough
        var created = 0
        for (row in schedule(loan, today)) {
            if (row.date.isAfter(today)) break
            if (row.number <= through) continue
            val amount = row.total
            if (amount.signum() > 0) {
                val rate = try {
                    currency.rateToBase(loan.familyId, baseCurrency, loan.currency.trim(), row.date)
                } catch (e: Exception) {
                    log.warn("Loan {} paused at {}: {}", loan.id, row.date, e.message)
                    break
                }
                created += jdbc.sql(
                    """INSERT INTO txn (family_id, user_id, type, amount, currency, fx_rate, base_amount, date, account_id, category_id,
                                        commitment, necessity, note, loan_id, loan_interest_part, loan_principal_part, loan_schedule_no)
                       VALUES (:f, :u, 'EXPENSE', :amount, :cur, :rate, :base, :date, :acc, :cat, :com, 'ESSENTIAL', :note, :loan, :int, :prin, :no)
                       ON CONFLICT DO NOTHING""",
                ).param("f", loan.familyId).param("u", userId).param("amount", amount.money()).param("cur", loan.currency.trim())
                    .param("rate", rate).param("base", (amount * rate).money()).param("date", row.date)
                    .param("acc", loan.accountId).param("cat", loan.categoryId).param("com", commitment.name).param("note", loan.name)
                    .param("loan", loan.id).param("int", row.interest).param("prin", row.principal).param("no", row.number).update()
            }
            through = row.number
        }
        if (through != loan.generatedThrough) jdbc.sql("UPDATE loan SET generated_through = :t WHERE id = :l").param("t", through).param("l", loan.id).update()
        return created
    }
}
