package com.openfamilybudget.loan

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.pow

enum class LoanTerm { SHORT_TERM, LONG_TERM }

enum class LoanType {
    /** Constant instalment (re-calculated when the rate changes). */
    ANNUITY,

    /** A fixed share of the original principal is repaid each year, plus interest on the remaining balance. */
    FIXED_AMORTIZATION,

    /** Only interest is paid and the principal is never repaid by the schedule. */
    INTEREST_ONLY,

    /** Annuity that leaves a residual (balloon) amount to pay at the end, with optional fees. */
    BALLOON_ANNUITY,
}

@Table("loan")
data class Loan(
    @Id val id: Long? = null,
    val familyId: Long,
    val ownerUserId: Long? = null,
    val name: String,
    val lender: String? = null,
    val currency: String,
    /** Loan amount; for a balloon loan this is the purchase price before the down payment. */
    val principal: BigDecimal,
    val interestRate: BigDecimal,
    val tenureMonths: Int? = null,
    val emi: BigDecimal,
    /** Date of the first scheduled payment. */
    val startDate: LocalDate,
    val term: LoanTerm,
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val active: Boolean = true,
    val loanType: LoanType = LoanType.ANNUITY,
    /** Yearly repayment as a percentage of the original principal (FIXED_AMORTIZATION). */
    val amortizationRate: BigDecimal? = null,
    val balloonAmount: BigDecimal = BigDecimal.ZERO,
    val downPayment: BigDecimal = BigDecimal.ZERO,
    val setupFee: BigDecimal = BigDecimal.ZERO,
    val monthlyFee: BigDecimal = BigDecimal.ZERO,
    /** For loans that are already running: outstanding balance before the first scheduled payment. */
    val openingBalance: BigDecimal? = null,
    val generatedThrough: Int = 0,
    val createdBy: Long? = null,
)

interface LoanRepository : CrudRepository<Loan, Long> {
    fun findByFamilyId(familyId: Long): List<Loan>
    fun findByIdAndFamilyId(id: Long, familyId: Long): Loan?
    fun findByActiveTrue(): List<Loan>
}

data class RateChange(val effectiveFrom: LocalDate, val annualRate: BigDecimal)

/** [payment] = interest + principal (the final row of a balloon loan includes the balloon); [fee] is on top of it. */
data class ScheduleRow(
    val number: Int, val date: LocalDate, val payment: BigDecimal, val interest: BigDecimal, val principal: BigDecimal,
    val fee: BigDecimal, val balance: BigDecimal,
) {
    val total: BigDecimal get() = payment + fee
}

object Amortization {
    private val mc = MathContext(20)

    fun emi(principal: BigDecimal, annualPercent: BigDecimal, months: Int): BigDecimal = LoanCalculator.pmt(principal, annualPercent, months, BigDecimal.ZERO)

    fun monthlyRate(annualPercent: BigDecimal): BigDecimal = annualPercent.divide(BigDecimal(1200), mc)
}

object LoanCalculator {
    private val mc = MathContext(20)
    private const val MAX_ROWS = 1200

    /** Amount actually borrowed: the opening balance for a running loan, otherwise principal minus down payment. */
    fun financed(loan: Loan): BigDecimal = loan.openingBalance ?: (loan.principal - loan.downPayment)

    /** Instalment that repays [balance] over [months] months leaving [balloon] outstanding. */
    fun pmt(balance: BigDecimal, annualPercent: BigDecimal, months: Int, balloon: BigDecimal): BigDecimal {
        val r = Amortization.monthlyRate(annualPercent)
        if (r.signum() == 0) return (balance - balloon).divide(BigDecimal(months), 4, RoundingMode.HALF_UP)
        val factor = BigDecimal.ONE.add(r).pow(months, mc)
        val numerator = balance.multiply(factor, mc).subtract(balloon).multiply(r, mc)
        return numerator.divide(factor.subtract(BigDecimal.ONE), 4, RoundingMode.HALF_UP)
    }

    fun rateAt(loan: Loan, changes: List<RateChange>, date: LocalDate): BigDecimal =
        changes.sortedBy { it.effectiveFrom }.lastOrNull { !it.effectiveFrom.isAfter(date) }?.annualRate ?: loan.interestRate

    /**
     * Full payment schedule. Open-ended loans (interest only) are projected for [openEndedUntil] plus two months, at least 24 rows.
     */
    fun schedule(loan: Loan, changes: List<RateChange> = emptyList(), openEndedUntil: LocalDate = loan.startDate): List<ScheduleRow> {
        var balance = financed(loan)
        val tenure = loan.tenureMonths
        val rows = ArrayList<ScheduleRow>()
        val count = when (loan.loanType) {
            LoanType.ANNUITY, LoanType.BALLOON_ANNUITY -> tenure ?: 0
            LoanType.FIXED_AMORTIZATION -> MAX_ROWS
            LoanType.INTEREST_ONLY -> maxOf(24, java.time.temporal.ChronoUnit.MONTHS.between(loan.startDate, openEndedUntil).toInt() + 2)
        }
        val balloon = if (loan.loanType == LoanType.BALLOON_ANNUITY) loan.balloonAmount else BigDecimal.ZERO
        var payment = loan.emi
        var previousRate: BigDecimal? = null
        for (i in 1..count) {
            val date = loan.startDate.plusMonths((i - 1).toLong())
            val rate = rateAt(loan, changes, date)
            val interest = balance.multiply(Amortization.monthlyRate(rate), mc).setScale(4, RoundingMode.HALF_UP)
            val last = tenure != null && i == tenure
            val principalPart = when (loan.loanType) {
                LoanType.ANNUITY, LoanType.BALLOON_ANNUITY -> {
                    val firstOfAnnuity = i == 1 && loan.loanType == LoanType.ANNUITY && loan.emi.signum() > 0
                    if (!firstOfAnnuity && (i == 1 || rate.compareTo(previousRate!!) != 0)) {
                        payment = pmt(balance, rate, tenure!! - i + 1, balloon)
                    }
                    // the last instalment absorbs rounding (and the balloon) so the balance ends at exactly zero
                    if (last) balance else (payment - interest).min(balance)
                }
                LoanType.FIXED_AMORTIZATION ->
                    loan.principal.multiply(loan.amortizationRate ?: BigDecimal.ZERO).divide(BigDecimal(1200), 4, RoundingMode.HALF_UP).min(balance)
                LoanType.INTEREST_ONLY -> BigDecimal.ZERO
            }
            previousRate = rate
            balance = (balance - principalPart).max(BigDecimal.ZERO)
            val fee = loan.monthlyFee + if (i == 1) loan.setupFee else BigDecimal.ZERO
            rows += ScheduleRow(i, date, interest + principalPart, interest, principalPart, fee, balance)
            if (loan.loanType == LoanType.FIXED_AMORTIZATION && balance.signum() == 0) break
        }
        return rows
    }

    /** Balance still owed after [paid] payments. */
    fun balanceAfter(loan: Loan, rows: List<ScheduleRow>, paid: Int): BigDecimal =
        if (paid <= 0) financed(loan) else if (rows.isEmpty() || paid >= rows.size && loan.loanType != LoanType.INTEREST_ONLY) BigDecimal.ZERO else rows[minOf(paid, rows.size) - 1].balance

    /**
     * Effective annual rate (percent) of the whole schedule, including fees, from the monthly IRR found with
     * Newton-Raphson (bisection as a fallback). Null when the loan is never fully repaid.
     */
    fun effectiveRate(loan: Loan, rows: List<ScheduleRow>): BigDecimal? {
        if (rows.isEmpty() || rows.last().balance.signum() != 0) return null
        val cash = DoubleArray(rows.size + 1)
        cash[0] = (financed(loan) - loan.setupFee).toDouble()
        rows.forEachIndexed { i, r -> cash[i + 1] = -(r.payment.toDouble() + r.fee.toDouble() - (if (r.number == 1) loan.setupFee.toDouble() else 0.0)) }
        val monthly = irr(cash) ?: return null
        return BigDecimal(((1 + monthly).pow(12) - 1) * 100).setScale(4, RoundingMode.HALF_UP)
    }

    fun irr(cash: DoubleArray): Double? {
        fun npv(x: Double) = cash.indices.sumOf { cash[it] / (1 + x).pow(it) }
        fun slope(x: Double) = cash.indices.sumOf { -it * cash[it] / (1 + x).pow(it + 1) }
        var x = 0.005
        repeat(100) {
            val f = npv(x)
            if (abs(f) < 1e-9) return x
            val d = slope(x)
            if (d == 0.0 || d.isNaN()) return@repeat
            val next = x - f / d
            if (next.isNaN() || next <= -0.99) return@repeat
            if (abs(next - x) < 1e-14) return next
            x = next
        }
        if (abs(npv(x)) < 1e-6) return x
        var lo = -0.5
        var hi = 1.0
        if (npv(lo) * npv(hi) > 0) return null
        repeat(200) {
            val mid = (lo + hi) / 2
            if (npv(lo) * npv(mid) <= 0) hi = mid else lo = mid
        }
        return (lo + hi) / 2
    }
}
